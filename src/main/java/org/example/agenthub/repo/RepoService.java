package org.example.agenthub.repo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.example.agenthub.access.RepoAccess;
import org.example.agenthub.access.RepoAccessService;
import org.example.agenthub.access.Role;
import org.example.agenthub.access.Visibility;
import org.example.agenthub.config.AgentHubProperties;
import org.example.agenthub.events.DomainEvent;
import org.example.agenthub.git.RepoLocks;
import org.example.agenthub.web.ErrorCode;
import org.example.agenthub.web.HintedException;
import org.example.agenthub.web.Paged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RepoService {

    private static final Logger log = LoggerFactory.getLogger(RepoService.class);

    private final RepoRepository repos;
    private final RepoGrantRepository grants;
    private final BranchProtectionRepository protections;
    private final RepoAccessService access;
    private final RepoStorage storage;
    private final RepoTemplates templates;
    private final Identities identities;
    private final RepoLocks locks;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final AgentHubProperties properties;
    private final ApplicationEventPublisher events;

    public RepoService(RepoRepository repos, RepoGrantRepository grants, BranchProtectionRepository protections,
            RepoAccessService access, RepoStorage storage, RepoTemplates templates, Identities identities,
            RepoLocks locks, TransactionTemplate tx, Clock clock, AgentHubProperties properties,
            ApplicationEventPublisher events) {
        this.repos = repos;
        this.grants = grants;
        this.protections = protections;
        this.access = access;
        this.storage = storage;
        this.templates = templates;
        this.identities = identities;
        this.locks = locks;
        this.tx = tx;
        this.clock = clock;
        this.properties = properties;
        this.events = events;
    }

    public record Created(RepoView repo, boolean created) {
    }

    public record Membership(String repo, Role role) {
    }

    public record BranchView(String name, String sha, @JsonProperty("protected") boolean protectedBranch,
            @JsonProperty("default") boolean defaultBranch) {
    }

    public Created create(String agent, RepoRequests.Create request) {
        String name = request.name();
        if (!NamePolicy.isValidRepoName(name)) {
            throw new HintedException(ErrorCode.INVALID_NAME, "invalid repository name '" + name + "'",
                    "use 1-64 characters: lowercase letters, digits, '-' and '_', starting with a letter or digit");
        }
        Visibility visibility = request.visibility() == null ? Visibility.PRIVATE : request.visibility();
        boolean init = request.init() == null || request.init();

        var existing = repos.findByOwnerAndNameAndDeletedAtIsNull(agent, name);
        if (existing.isPresent()) {
            return sameAs(existing.get(), visibility, request.description(), init);
        }
        int limit = properties.limits().maxReposPerAgent();
        if (repos.countByOwnerAndDeletedAtIsNull(agent) >= limit) {
            throw new HintedException(ErrorCode.QUOTA_EXCEEDED, "an agent can own at most " + limit + " repositories",
                    "delete a repository you no longer need: DELETE /v1/repos/" + agent + "/<name>");
        }

        AtomicReference<Long> createdId = new AtomicReference<>();
        try {
            Repo repo = tx.execute(status -> {
                Repo fresh = new Repo(agent, name, visibility, request.description(), init);
                fresh.setCreatedAt(clock.instant());
                repos.saveAndFlush(fresh);
                createdId.set(fresh.getId());
                protections.save(BranchProtection.forDefaultBranch(fresh.getId(), fresh.getDefaultBranch()));
                storage.create(fresh.getId());
                if (init) {
                    commitInitialFiles(fresh);
                }
                fresh.setSizeBytes(storage.sizeOf(fresh.getId()));
                events.publishEvent(new DomainEvent(DomainEvent.REPO_CREATED, fresh.getId(), null, agent,
                        Map.of("repo", fresh.getFullName(), "visibility", visibility, "init", init)));
                return fresh;
            });
            return new Created(view(repo, Role.ADMIN), true);
        } catch (RuntimeException e) {
            if (createdId.get() != null) {
                storage.delete(createdId.get());
            }
            if (e instanceof DataIntegrityViolationException) {
                // a concurrent request created the same name first
                var winner = repos.findByOwnerAndNameAndDeletedAtIsNull(agent, name);
                if (winner.isPresent()) {
                    return sameAs(winner.get(), visibility, request.description(), init);
                }
            }
            throw e;
        }
    }

    private void commitInitialFiles(Repo repo) {
        try {
            storage.commitInitialFiles(repo.getId(), repo.getDefaultBranch(),
                    templates.initialFiles(repo.getOwner(), repo.getName(), repo.getDescription()),
                    identities.agent(repo.getOwner()), identities.server(), "Initial commit\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** §9.9: a repeated POST with the same parameters is answered with the existing repository. */
    private Created sameAs(Repo repo, Visibility visibility, String description, boolean init) {
        RepoView current = view(repo, Role.ADMIN);
        if (repo.getVisibility() == visibility && Objects.equals(repo.getDescription(), description)
                && repo.isInitialized() == init) {
            return new Created(current, false);
        }
        throw new HintedException(ErrorCode.ALREADY_EXISTS,
                "repository '" + repo.getFullName() + "' already exists with different parameters",
                "use it as it is (see 'current'), change it with PATCH /v1/repos/" + repo.getFullName()
                        + ", or choose another name")
                .with("current", current);
    }

    public RepoView get(String agent, String owner, String name) {
        RepoAccess granted = access.require(agent, owner, name, Role.READ);
        return view(granted.repo(), granted.role());
    }

    public List<BranchView> branches(String agent, String owner, String name) {
        Repo repo = access.require(agent, owner, name, Role.READ).repo();
        Set<String> protectedBranches = protections.findByRepoId(repo.getId()).stream()
                .map(BranchProtection::getBranch)
                .collect(Collectors.toSet());
        try {
            return storage.branches(repo.getId()).stream()
                    .map(ref -> {
                        String branch = Repository.shortenRefName(ref.getName());
                        return new BranchView(branch, ref.getObjectId().name(), protectedBranches.contains(branch),
                                branch.equals(repo.getDefaultBranch()));
                    })
                    .sorted(Comparator.comparing(BranchView::name))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Paged<RepoView> list(String agent, String owner, boolean sharedWithMe, String cursor, Integer limit) {
        int size = Paged.limit(limit);
        long after = parseCursor(cursor);
        PageRequest page = PageRequest.ofSize(size + 1);
        List<Repo> rows = sharedWithMe
                ? repos.findSharedWith(agent, owner, after, page)
                : repos.findAccessible(agent, owner, Visibility.INTERNAL, after, page);
        return Paged.of(rows, size, repo -> repo.getId().toString(),
                repo -> view(repo, access.roleOf(agent, repo).orElseThrow()));
    }

    public RepoView update(String agent, String owner, String name, RepoRequests.Update request) {
        RepoAccess granted = access.require(agent, owner, name, Role.ADMIN);
        long id = granted.repo().getId();
        return locks.withLock(id, () -> tx.execute(status -> {
            Repo repo = repos.findById(id).filter(r -> r.getDeletedAt() == null)
                    .orElseThrow(() -> RepoAccessService.notFound(owner, name));
            if (request.description() != null) {
                repo.setDescription(request.description());
            }
            if (request.visibility() != null) {
                repo.setVisibility(request.visibility());
            }
            String branch = request.defaultBranch();
            if (branch != null && !branch.equals(repo.getDefaultBranch())) {
                changeDefaultBranch(repo, branch);
            }
            return view(repo, granted.role());
        }));
    }

    private void changeDefaultBranch(Repo repo, String branch) {
        long id = repo.getId();
        try {
            if (!Repository.isValidRefName(Constants.R_HEADS + branch) || !storage.branchExists(id, branch)) {
                throw new HintedException(ErrorCode.VALIDATION_FAILED,
                        "branch '" + branch + "' does not exist in " + repo.getFullName(),
                        "push the branch first, then retry; list branches with GET /v1/repos/" + repo.getFullName()
                                + "/branches");
            }
            if (!protections.existsById(new BranchProtection.Key(id, branch))) {
                protections.save(BranchProtection.forDefaultBranch(id, branch));
            }
            repo.setDefaultBranch(branch);
            storage.linkHead(id, branch);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** §13: mark deleted (404 at once), then close the cached repository and remove its directory. */
    public void delete(String agent, String owner, String name) {
        long id = access.requireOwner(agent, owner, name).repo().getId();
        tx.executeWithoutResult(status -> {
            Repo repo = repos.findById(id).orElseThrow();
            if (repo.getDeletedAt() == null) {
                String fullName = repo.getFullName();
                repo.markDeleted(clock.instant());
                grants.deleteAllOfRepo(id);
                protections.deleteAllOfRepo(id);
                events.publishEvent(new DomainEvent(DomainEvent.REPO_DELETED, id, null, agent,
                        Map.of("repo", fullName)));
            }
        });
        try {
            locks.withLock(id, () -> {
                if (!storage.delete(id)) {
                    log.warn("repository {} is deleted but its directory {} could not be removed yet", id,
                            storage.dirOf(id));
                }
            });
        } catch (HintedException busy) {
            log.warn("repository {} is deleted but was busy; its directory stays until the next cleanup", id);
        }
    }

    public List<Membership> memberships(String agent) {
        List<Membership> result = new ArrayList<>();
        repos.findByOwnerAndDeletedAtIsNullOrderByName(agent)
                .forEach(repo -> result.add(new Membership(repo.getFullName(), Role.ADMIN)));
        Map<Long, Role> granted = grants.findByAgent(agent).stream()
                .collect(Collectors.toMap(RepoGrant::getRepoId, RepoGrant::getRole));
        repos.findByIdInAndDeletedAtIsNull(new ArrayList<>(granted.keySet())).stream()
                .sorted(Comparator.comparing(Repo::getFullName))
                .forEach(repo -> result.add(new Membership(repo.getFullName(), granted.get(repo.getId()))));
        return result;
    }

    public long internalOnlyCount(String agent) {
        return repos.countVisibleOnlyAsInternal(agent, Visibility.INTERNAL);
    }

    public void refreshSize(long id) {
        long size = storage.sizeOf(id);
        tx.executeWithoutResult(status -> repos.updateSize(id, size));
    }

    public String cloneUrl(Repo repo) {
        return properties.publicUrl() + "/git/" + repo.getFullName() + ".git";
    }

    RepoView view(Repo repo, Role role) {
        return new RepoView(repo.getOwner(), repo.getName(), repo.getVisibility(), repo.getDescription(),
                repo.getDefaultBranch(), role, cloneUrl(repo), repo.getSizeBytes(), repo.getCreatedAt());
    }

    private static long parseCursor(String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(cursor);
        } catch (NumberFormatException e) {
            throw new HintedException(ErrorCode.VALIDATION_FAILED, "invalid cursor",
                    "pass 'nextCursor' from the previous page unchanged");
        }
    }
}

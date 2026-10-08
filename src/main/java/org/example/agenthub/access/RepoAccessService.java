package org.example.agenthub.access;

import java.util.Optional;

import org.example.agenthub.repo.Repo;
import org.example.agenthub.repo.RepoGrant;
import org.example.agenthub.repo.RepoGrantRepository;
import org.example.agenthub.repo.RepoRepository;
import org.example.agenthub.web.ErrorCode;
import org.example.agenthub.web.HintedException;
import org.springframework.stereotype.Service;

/** Effective role (§5.2) and the 404-vs-403 answering rules (§5.4). */
@Service
public class RepoAccessService {

    private final RepoRepository repos;
    private final RepoGrantRepository grants;

    public RepoAccessService(RepoRepository repos, RepoGrantRepository grants) {
        this.repos = repos;
        this.grants = grants;
    }

    public static Optional<Role> effectiveRole(String agent, Repo repo, Optional<Role> granted) {
        if (repo.getOwner().equals(agent)) {
            return Optional.of(Role.ADMIN);
        }
        Role role = granted.orElse(null);
        if (repo.getVisibility() == Visibility.INTERNAL) {
            role = Role.max(role, Role.READ);
        }
        return Optional.ofNullable(role);
    }

    public Optional<Role> roleOf(String agent, Repo repo) {
        if (repo.getOwner().equals(agent)) {
            return Optional.of(Role.ADMIN);
        }
        return effectiveRole(agent, repo, grants.findByRepoIdAndAgent(repo.getId(), agent).map(RepoGrant::getRole));
    }

    /** Resolves a live repository the agent can see; no access looks exactly like a missing repository. */
    public RepoAccess require(String agent, String owner, String name, Role minimum) {
        Repo repo = repos.findByOwnerAndNameAndDeletedAtIsNull(owner, name).orElse(null);
        Role role = repo == null ? null : roleOf(agent, repo).orElse(null);
        if (role == null) {
            throw notFound(owner, name);
        }
        if (!role.atLeast(minimum)) {
            throw insufficientRole(repo, agent, role, minimum);
        }
        return new RepoAccess(repo, role);
    }

    public RepoAccess requireOwner(String agent, String owner, String name) {
        RepoAccess access = require(agent, owner, name, Role.READ);
        if (!access.repo().getOwner().equals(agent)) {
            throw new HintedException(ErrorCode.INSUFFICIENT_ROLE,
                    "only the owner '" + owner + "' can do this in " + owner + "/" + name + "; you have "
                            + access.role(),
                    "ask '" + owner + "' to do it");
        }
        return access;
    }

    public static HintedException notFound(String owner, String name) {
        return new HintedException(ErrorCode.NOT_FOUND, "repository '" + owner + "/" + name + "' not found",
                "check the owner and name with GET /v1/repos; a private repository is visible only to its members");
    }

    public static HintedException insufficientRole(Repo repo, String agent, Role role, Role needed) {
        String path = repo.getOwner() + "/" + repo.getName();
        return new HintedException(ErrorCode.INSUFFICIENT_ROLE,
                "this needs " + needed + " in " + path + "; you have " + role,
                "ask the owner '" + repo.getOwner() + "' (or an ADMIN of " + path + ") to run: PUT /v1/repos/" + path
                        + "/grants/" + agent + " {\"role\":\"" + needed + "\"}");
    }
}

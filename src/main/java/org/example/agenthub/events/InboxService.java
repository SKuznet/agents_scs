package org.example.agenthub.events;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.example.agenthub.access.RepoAccessService;
import org.example.agenthub.access.Role;
import org.example.agenthub.pr.AgentText;
import org.example.agenthub.pr.ApprovalRules;
import org.example.agenthub.pr.Mergeable;
import org.example.agenthub.pr.PrReviewer;
import org.example.agenthub.pr.PrReviewerRepository;
import org.example.agenthub.pr.PrState;
import org.example.agenthub.pr.PullRequest;
import org.example.agenthub.pr.PullRequestRepository;
import org.example.agenthub.pr.PullRequestService;
import org.example.agenthub.pr.ReviewService;
import org.example.agenthub.repo.Repo;
import org.example.agenthub.repo.RepoGrant;
import org.example.agenthub.repo.RepoGrantRepository;
import org.example.agenthub.repo.RepoRepository;
import org.example.agenthub.repo.RepoStorage;
import org.springframework.stereotype.Service;

/** The inbox of §9.4, computed from the current state rather than from events. */
@Service
public class InboxService {

    private final RepoRepository repos;
    private final RepoGrantRepository grants;
    private final PullRequestRepository pulls;
    private final PrReviewerRepository reviewers;
    private final ReviewService reviews;
    private final RepoAccessService access;
    private final RepoStorage storage;
    private final ObjectMapper canonical;

    public InboxService(RepoRepository repos, RepoGrantRepository grants, PullRequestRepository pulls,
            PrReviewerRepository reviewers, ReviewService reviews, RepoAccessService access, RepoStorage storage,
            ObjectMapper mapper) {
        this.repos = repos;
        this.grants = grants;
        this.pulls = pulls;
        this.reviewers = reviewers;
        this.reviews = reviews;
        this.access = access;
        this.storage = storage;
        this.canonical = mapper.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public enum Kind {
        REVIEW_REQUESTED, CHANGES_REQUESTED, CONFLICT, AUTO_MERGE_BLOCKED;

        @JsonValue
        String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A REST call ({@code type=rest}) or a git command ({@code type=git}) that resolves the task. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Action(String type, String description, String method, String path, Map<String, Object> body,
            String command) {

        static Action rest(String description, String method, String path, Map<String, Object> body) {
            return new Action("rest", description, method, path, body, null);
        }

        static Action git(String description, String command) {
            return new Action("git", description, null, null, null, command);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Task(Kind type, String repo, int number, String target, int rev, String headSha, AgentText title,
            String reason, List<String> conflictPaths, List<Action> actions) {
    }

    /** {@code inboxVersion} is a digest of the items: it changes exactly when they do. */
    public record Inbox(List<Task> items, String inboxVersion,
            @JsonInclude(JsonInclude.Include.NON_NULL) WaitResult waitResult) {

        public Inbox with(WaitResult result) {
            return new Inbox(items, inboxVersion, result);
        }
    }

    public Inbox of(String agent) {
        List<Task> tasks = new ArrayList<>();
        Map<Long, Repo> writable = writableRepos(agent);
        for (PullRequest pr : pulls.findByRepoIdInAndStateAndDraftFalse(writable.keySet(), PrState.OPEN)) {
            Repo repo = writable.get(pr.getRepoId());
            if (reviewRequested(repo, pr, agent)) {
                tasks.add(reviewTask(repo, pr));
            }
        }
        for (PullRequest pr : pulls.findByAuthorAndState(agent, PrState.OPEN)) {
            repos.findById(pr.getRepoId())
                    .filter(repo -> repo.getDeletedAt() == null && access.roleOf(agent, repo).isPresent())
                    .ifPresent(repo -> authorTasks(repo, pr, tasks));
        }
        tasks.sort(Comparator.comparing(Task::repo).thenComparingInt(Task::number).thenComparing(Task::type));
        return new Inbox(List.copyOf(tasks), version(tasks), null);
    }

    public int count(String agent) {
        return of(agent).items().size();
    }

    private Map<Long, Repo> writableRepos(String agent) {
        Map<Long, Repo> writable = new LinkedHashMap<>();
        repos.findByOwnerAndDeletedAtIsNullOrderByName(agent).forEach(repo -> writable.put(repo.getId(), repo));
        List<Long> granted = grants.findByAgent(agent).stream().filter(grant -> grant.getRole().atLeast(Role.WRITE))
                .map(RepoGrant::getRepoId).toList();
        repos.findByIdInAndDeletedAtIsNull(granted).forEach(repo -> writable.put(repo.getId(), repo));
        return writable;
    }

    private boolean reviewRequested(Repo repo, PullRequest pr, String agent) {
        if (pr.getAuthor().equals(agent) || reviews.status(repo, pr).missing() == 0 || reviews.decided(pr, agent)) {
            return false;
        }
        List<String> requested = reviewers.findByPrIdOrderByAgent(pr.getId()).stream().map(PrReviewer::getAgent)
                .toList();
        return requested.isEmpty() || requested.contains(agent);
    }

    private Task reviewTask(Repo repo, PullRequest pr) {
        String api = PullRequestService.api(repo, pr);
        Map<String, Object> verdict = new LinkedHashMap<>();
        verdict.put("rev", pr.getCurrentRev());
        verdict.put("verdict", "APPROVE");
        List<Action> actions = List.of(
                Action.rest("read the PR, its diff, reviews and the AGENTS.md of the target in one call", "GET",
                        api + "/review-context", null),
                Action.rest("review rev " + pr.getCurrentRev() + ": verdict APPROVE, REQUEST_CHANGES or COMMENT,"
                        + " optional body and comments", "POST", api + "/reviews", verdict));
        return task(Kind.REVIEW_REQUESTED, repo, pr, "waiting for your review of rev " + pr.getCurrentRev(),
                List.of(), actions);
    }

    private void authorTasks(Repo repo, PullRequest pr, List<Task> tasks) {
        String api = PullRequestService.api(repo, pr);
        Action update = Action.git("commit the fix and push again (no --force needed)",
                "git push origin HEAD:refs/heads/pr/" + pr.getName());
        ApprovalRules.Status status = reviews.status(repo, pr);
        if (!status.blockers().isEmpty()) {
            tasks.add(task(Kind.CHANGES_REQUESTED, repo, pr, "changes requested on rev " + pr.getCurrentRev()
                    + " by " + String.join(", ", status.blockers()), List.of(), List.of(
                            Action.rest("read the blocking reviews and their comments", "GET",
                                    api + "/review-context", null),
                            update)));
        }
        if (conflicts(repo, pr)) {
            tasks.add(task(Kind.CONFLICT, repo, pr, "conflicts with '" + pr.getTargetBranch() + "'",
                    pr.getConflictPaths(), List.of(
                            Action.git("rebase onto the target and resolve the conflicts",
                                    "git fetch origin && git rebase origin/" + pr.getTargetBranch()),
                            update)));
        }
        if (pr.getAutoMergeBlockedReason() != null) {
            tasks.add(task(Kind.AUTO_MERGE_BLOCKED, repo, pr, pr.getAutoMergeBlockedReason(), List.of(), List.of(
                    Action.rest("check mergeable and autoMergeBlockedReason", "GET", api, null),
                    update)));
        }
    }

    /** Only a conflict computed against the current tip of the target counts, as in {@code GET /pulls/N}. */
    private boolean conflicts(Repo repo, PullRequest pr) {
        if (pr.getMergeable() != Mergeable.CONFLICT) {
            return false;
        }
        try (Repository db = storage.open(repo.getId())) {
            Ref target = db.exactRef(Constants.R_HEADS + pr.getTargetBranch());
            return target != null && target.getObjectId().name().equals(pr.getMergeableTargetSha());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Task task(Kind kind, Repo repo, PullRequest pr, String reason, List<String> conflictPaths,
            List<Action> actions) {
        Role authorRole = access.roleOf(pr.getAuthor(), repo).orElse(null);
        return new Task(kind, repo.getFullName(), pr.getNumber(), pr.getTargetBranch(), pr.getCurrentRev(),
                pr.getHeadSha(), new AgentText(pr.getTitle(), pr.getAuthor(), authorRole), reason, conflictPaths,
                actions);
    }

    private String version(List<Task> tasks) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    canonical.writeValueAsString(tasks).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

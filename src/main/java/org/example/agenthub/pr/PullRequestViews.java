package org.example.agenthub.pr;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.example.agenthub.access.RepoAccessService;
import org.example.agenthub.access.Role;
import org.example.agenthub.events.LongPollRegistry;
import org.example.agenthub.events.WaitResult;
import org.example.agenthub.repo.Repo;
import org.example.agenthub.repo.RepoRepository;
import org.example.agenthub.repo.RepoStorage;
import org.example.agenthub.web.ErrorCode;
import org.example.agenthub.web.HintedException;
import org.example.agenthub.web.Paged;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.DeferredResult;

/** Read side of §10.3: everything a READ member may look at. */
@Service
public class PullRequestViews {

    static final int DEFAULT_DIFF_LINES = 2000;
    static final int MAX_DIFF_LINES = 20000;
    static final int MAX_COMMITS = 250;

    private final RepoAccessService access;
    private final RepoRepository repos;
    private final PullRequestRepository pulls;
    private final PrRevisionRepository revisions;
    private final PrReviewerRepository requested;
    private final RepoStorage storage;
    private final DiffService diffs;
    private final ReviewService reviews;
    private final LongPollRegistry polls;

    public PullRequestViews(RepoAccessService access, RepoRepository repos, PullRequestRepository pulls,
            PrRevisionRepository revisions, PrReviewerRepository requested, RepoStorage storage, DiffService diffs,
            ReviewService reviews, LongPollRegistry polls) {
        this.access = access;
        this.repos = repos;
        this.pulls = pulls;
        this.revisions = revisions;
        this.requested = requested;
        this.storage = storage;
        this.diffs = diffs;
        this.reviews = reviews;
        this.polls = polls;
    }

    public record Summary(int number, String name, String author, String target, PrState state, boolean draft,
            String title, String headSha, int currentRev, Instant createdAt, Instant updatedAt) {

        static Summary of(PullRequest pr) {
            return new Summary(pr.getNumber(), pr.getName(), pr.getAuthor(), pr.getTargetBranch(), pr.getState(),
                    pr.isDraft(), pr.getTitle(), pr.getHeadSha(), pr.getCurrentRev(), pr.getCreatedAt(),
                    pr.getUpdatedAt());
        }
    }

    public record Details(int number, String name, String author, String target, PrState state, boolean draft,
            Boolean autoMerge, String title, String description, String headSha, int currentRev, Integer behindBy,
            Mergeable mergeable, List<String> conflictPaths, String mergeableError, List<String> approvals,
            int approvalsRequired,
            List<String> blockingReviews, List<String> reviewers, String mergeCommitSha, String mergedBy,
            Instant mergedAt, boolean autoMerged, String autoMergeBlockedReason, Instant createdAt,
            Instant updatedAt, @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> warnings,
            @JsonInclude(JsonInclude.Include.NON_NULL) WaitResult waitResult) {

        Details with(WaitResult result) {
            return new Details(number, name, author, target, state, draft, autoMerge, title, description, headSha,
                    currentRev, behindBy, mergeable, conflictPaths, mergeableError, approvals, approvalsRequired,
                    blockingReviews, reviewers, mergeCommitSha, mergedBy, mergedAt, autoMerged,
                    autoMergeBlockedReason, createdAt, updatedAt, warnings, result);
        }
    }

    /** What {@code GET /pulls/N?waitFor=} waits for (§9.5); a state that can no longer reach it is terminal. */
    private enum WaitFor {
        MERGED, CLOSED, MERGEABILITY;

        WaitResult evaluate(Details pr) {
            return switch (this) {
                case MERGED -> pr.state() == PrState.MERGED ? WaitResult.SATISFIED
                        : pr.state() == PrState.CLOSED ? WaitResult.TERMINAL : null;
                case CLOSED -> pr.state() == PrState.CLOSED ? WaitResult.SATISFIED
                        : pr.state() == PrState.MERGED ? WaitResult.TERMINAL : null;
                case MERGEABILITY -> pr.state() != PrState.OPEN ? WaitResult.TERMINAL
                        : pr.mergeable() != Mergeable.UNKNOWN ? WaitResult.SATISFIED : null;
            };
        }

        static WaitFor parse(String value) {
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new HintedException(ErrorCode.VALIDATION_FAILED, "unknown waitFor '" + value + "'",
                        "use waitFor=merged, closed or mergeability");
            }
        }
    }

    public record RevisionView(int rev, String headSha, String baseSha, String pushedBy, String model,
            String session, Instant createdAt) {
    }

    public Paged<Summary> list(String agent, String owner, String name, String state, String author, String target,
            String cursor, Integer limit) {
        Repo repo = access.require(agent, owner, name, Role.READ).repo();
        int size = Paged.limit(limit);
        List<PullRequest> rows = pulls.search(repo.getId(), parseState(state), author, target, parseCursor(cursor),
                PageRequest.ofSize(size + 1));
        return Paged.of(rows, size, pr -> String.valueOf(pr.getNumber()), Summary::of);
    }

    public Details get(String agent, String owner, String name, int number) {
        Repo repo = access.require(agent, owner, name, Role.READ).repo();
        return details(repo, pulls.require(repo, number));
    }

    /** The long-poll of §9.5: answers once the condition holds or can no longer hold, or with the state on timeout. */
    public DeferredResult<Details> await(String agent, String owner, String name, int number, String waitFor,
            int timeout) {
        WaitFor condition = WaitFor.parse(waitFor);
        Repo repo = access.require(agent, owner, name, Role.READ).repo();
        pulls.require(repo, number);
        return polls.await(agent, repo.getId(), timeout, () -> {
            Details now = get(agent, owner, name, number);
            WaitResult result = condition.evaluate(now);
            return result == null ? null : now.with(result);
        }, () -> get(agent, owner, name, number).with(WaitResult.TIMEOUT));
    }

    public List<RevisionView> revisions(String agent, String owner, String name, int number) {
        Repo repo = access.require(agent, owner, name, Role.READ).repo();
        return revisions.findByPrIdOrderByRev(pulls.require(repo, number).getId()).stream()
                .map(r -> new RevisionView(r.getRev(), r.getHeadSha(), r.getBaseSha(), r.getPushedBy(), r.getModel(),
                        r.getSession(), r.getCreatedAt()))
                .toList();
    }

    public List<DiffService.CommitView> commits(String agent, String owner, String name, int number, Integer rev) {
        Repo repo = access.require(agent, owner, name, Role.READ).repo();
        PrRevision revision = revision(repo, pulls.require(repo, number), rev);
        return withRepository(repo, db -> diffs.commits(db, id(revision.getBaseSha()), id(revision.getHeadSha()),
                MAX_COMMITS));
    }

    public List<DiffService.FileChange> files(String agent, String owner, String name, int number, Integer rev) {
        Repo repo = access.require(agent, owner, name, Role.READ).repo();
        PrRevision revision = revision(repo, pulls.require(repo, number), rev);
        return withRepository(repo, db -> diffs.files(db, id(revision.getBaseSha()), id(revision.getHeadSha())));
    }

    public DiffService.Diff diff(String agent, String owner, String name, int number, Integer rev, String path,
            Integer maxLines) {
        Repo repo = access.require(agent, owner, name, Role.READ).repo();
        PrRevision revision = revision(repo, pulls.require(repo, number), rev);
        int lines = maxLines == null ? DEFAULT_DIFF_LINES : Math.max(1, Math.min(MAX_DIFF_LINES, maxLines));
        return withRepository(repo, db -> diffs.diff(db, id(revision.getBaseSha()), id(revision.getHeadSha()), path,
                lines));
    }

    public Details details(long repoId, PullRequest pr) {
        return details(repos.findById(repoId).orElseThrow(), pr);
    }

    /** Status of §10.3; mergeability computed against an older tip of the target reads as UNKNOWN. */
    public Details details(Repo repo, PullRequest pr) {
        Integer behindBy = null;
        Mergeable mergeable = pr.getMergeable();
        List<String> conflicts = pr.getConflictPaths();
        String mergeableError = pr.getMergeableError();
        if (pr.isOpen()) {
            TargetTip tip = withRepository(repo, db -> {
                Ref target = db.exactRef(Constants.R_HEADS + pr.getTargetBranch());
                return target == null ? null : new TargetTip(target.getObjectId().name(),
                        diffs.behindBy(db, target.getObjectId(), id(pr.getHeadSha())));
            });
            if (tip == null || !tip.sha().equals(pr.getMergeableTargetSha())) {
                mergeable = Mergeable.UNKNOWN;
                conflicts = List.of();
                mergeableError = null;
            }
            behindBy = tip == null ? null : tip.behindBy();
        }
        ApprovalRules.Status approvals = reviews.status(repo, pr);
        List<String> reviewers = requested.findByPrIdOrderByAgent(pr.getId()).stream().map(PrReviewer::getAgent)
                .toList();
        List<String> warnings = reviewers.stream().filter(agent -> !reviews.canWrite(repo, agent))
                .map(agent -> "reviewer '" + agent + "' has no WRITE role and will not get the review task")
                .toList();
        return new Details(pr.getNumber(), pr.getName(), pr.getAuthor(), pr.getTargetBranch(), pr.getState(),
                pr.isDraft(), pr.getAutoMerge(), pr.getTitle(), pr.getDescription(), pr.getHeadSha(),
                pr.getCurrentRev(), behindBy, mergeable, conflicts, mergeableError, approvals.approvals(),
                approvals.required(),
                approvals.blockers(), reviewers, pr.getMergeCommitSha(), pr.getMergedBy(), pr.getMergedAt(),
                pr.isAutoMerged(), pr.getAutoMergeBlockedReason(), pr.getCreatedAt(), pr.getUpdatedAt(), warnings,
                null);
    }

    private PrRevision revision(Repo repo, PullRequest pr, Integer rev) {
        int wanted = rev == null ? pr.getCurrentRev() : rev;
        return revisions.findByPrIdAndRev(pr.getId(), wanted).orElseThrow(() -> new HintedException(
                ErrorCode.NOT_FOUND, "PR #" + pr.getNumber() + " has no revision " + wanted,
                "list revisions: GET /v1/repos/" + repo.getFullName() + "/pulls/" + pr.getNumber() + "/revisions"));
    }

    private record TargetTip(String sha, int behindBy) {
    }

    private interface GitRead<T> {
        T apply(Repository db) throws IOException;
    }

    private <T> T withRepository(Repo repo, GitRead<T> read) {
        try (Repository db = storage.open(repo.getId())) {
            return read.apply(db);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ObjectId id(String sha) {
        return ObjectId.fromString(sha);
    }

    private static PrState parseState(String state) {
        if (state == null || state.isEmpty()) {
            return null;
        }
        try {
            return PrState.valueOf(state.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new HintedException(ErrorCode.VALIDATION_FAILED, "unknown state '" + state + "'",
                    "use state=open, merged or closed");
        }
    }

    private static int parseCursor(String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(cursor);
        } catch (NumberFormatException e) {
            throw new HintedException(ErrorCode.VALIDATION_FAILED, "invalid cursor",
                    "pass 'nextCursor' from the previous page unchanged");
        }
    }
}

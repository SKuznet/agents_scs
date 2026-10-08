package org.example.agenthub.pr;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.example.agenthub.access.RepoAccess;
import org.example.agenthub.access.RepoAccessService;
import org.example.agenthub.access.Role;
import org.example.agenthub.config.AgentHubProperties;
import org.example.agenthub.events.DomainEvent;
import org.example.agenthub.git.RepoLocks;
import org.example.agenthub.repo.Identities;
import org.example.agenthub.repo.NamePolicy;
import org.example.agenthub.repo.Repo;
import org.example.agenthub.repo.RepoRepository;
import org.example.agenthub.repo.RepoStorage;
import org.example.agenthub.web.ErrorCode;
import org.example.agenthub.web.HintedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Opening and updating pull requests (§7.2). Everything runs under the repository lock, outside of which the
 * transaction is started (§13); a new revision is flushed to the database before its refs are written, so
 * constraint violations surface first, and refs are put back from the database if the commit then fails.
 */
@Service
public class PullRequestService {

    private static final Logger log = LoggerFactory.getLogger(PullRequestService.class);

    private final RepoRepository repos;
    private final RepoAccessService access;
    private final RepoStorage storage;
    private final PullRequestRepository pulls;
    private final PrRevisionRepository revisions;
    private final PrReviewerRepository requested;
    private final ReviewService reviews;
    private final AutoMergeService autoMerge;
    private final RepoLocks locks;
    private final TransactionTemplate tx;
    private final MergeabilityUpdater mergeability;
    private final PullRefs refs;
    private final Identities identities;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final AgentHubProperties.Limits limits;

    public PullRequestService(RepoRepository repos, RepoAccessService access, RepoStorage storage,
            PullRequestRepository pulls, PrRevisionRepository revisions, PrReviewerRepository requested,
            ReviewService reviews, AutoMergeService autoMerge, RepoLocks locks, TransactionTemplate tx,
            MergeabilityUpdater mergeability, PullRefs refs, Identities identities, ApplicationEventPublisher events,
            Clock clock, AgentHubProperties properties) {
        this.repos = repos;
        this.access = access;
        this.storage = storage;
        this.pulls = pulls;
        this.revisions = revisions;
        this.requested = requested;
        this.reviews = reviews;
        this.autoMerge = autoMerge;
        this.locks = locks;
        this.tx = tx;
        this.mergeability = mergeability;
        this.refs = refs;
        this.identities = identities;
        this.events = events;
        this.clock = clock;
        this.limits = properties.limits();
    }

    /** The target tip a revision is based on, the head, and their trial merge ({@code null}: not known yet). */
    record Basis(String target, RevCommit tip, RevCommit head, MergeabilityService.Result mergeability) {
    }

    /** What {@code openOrUpdate} did: the PR, whether it was created, and the lines for the agent. */
    public record Outcome(PullRequest pr, boolean created, List<String> lines) {
    }

    /** §7.2: a push of {@code head} to {@code refs/heads/pr/<name>} by {@code pusher}. */
    public List<String> openOrUpdate(Repository db, long repoId, Role role, Provenance pusher, String name,
            ObjectId head, List<String> rawOptions) {
        PushOptions options = PushOptions.parse(rawOptions, limits.maxPushOptions(),
                limits.maxPushOptionSize().toBytes());
        return openOrUpdate(db, repoId, role, pusher, name, head, options).lines();
    }

    /** §7.2 for a head that is already in the repository, from a push or from {@code POST /pulls}. */
    public Outcome openOrUpdate(Repository db, long repoId, Role role, Provenance pusher, String name,
            ObjectId head, PushOptions options) {
        return locks.withLock(repoId, () -> {
            Repo repo = repos.findById(repoId).orElseThrow();
            PullRequest open = pulls.findOpen(repoId, pusher.agent(), name).orElse(null);
            String target = options.target() != null ? options.target()
                    : open != null ? open.getTargetBranch() : repo.getDefaultBranch();
            Basis basis = basis(db, repo, name, target, head);
            boolean authorCanWrite = role.atLeast(Role.WRITE);
            checkReviewers(pusher.agent(), options.reviewers());
            Outcome outcome;
            if (open == null) {
                outcome = create(db, repo, name, basis, options, pusher, authorCanWrite);
            } else if (open.getHeadSha().equals(head.name()) && target.equals(open.getTargetBranch())) {
                outcome = new Outcome(open, false, unchanged(repo, open, options));
            } else {
                PullRequest updated = addRevision(db, repo, open, basis, options, pusher, "push");
                outcome = new Outcome(updated, false, summary("Updated PR #" + updated.getNumber() + " -> "
                        + updated.getTargetBranch() + " (rev " + updated.getCurrentRev() + ")", repo, updated,
                        authorCanWrite));
            }
            if (options.reviewers() == null) {
                return outcome;
            }
            List<String> withWarnings = new ArrayList<>(outcome.lines());
            reviewerWarnings(repo, options.reviewers()).forEach(w -> withWarnings.add("[agenthub] warning: " + w));
            return new Outcome(outcome.pr(), outcome.created(), withWarnings);
        });
    }

    /** Fields of {@code PATCH /pulls/N}; null means unchanged. */
    public record Patch(String title, String description, String state, Boolean draft, String target,
            Boolean autoMerge, List<String> reviewers) {
    }

    /** §7.9 and §10.3: the author (PROPOSE+) or any WRITE member changes a PR. */
    public PullRequest patch(Provenance actor, String owner, String name, int number, Patch patch) {
        RepoAccess granted = access.require(actor.agent(), owner, name, Role.READ);
        Repo repo = granted.repo();
        PullRequest pr = pulls.require(repo, number);
        boolean isAuthor = pr.getAuthor().equals(actor.agent());
        if (!granted.role().atLeast(isAuthor ? Role.PROPOSE : Role.WRITE)) {
            throw new HintedException(ErrorCode.INSUFFICIENT_ROLE, "changing PR #" + number + " needs "
                    + (isAuthor ? "PROPOSE" : "WRITE (or being its author)") + "; you have " + granted.role(),
                    "ask the author '" + pr.getAuthor() + "' or a WRITE member of " + repo.getFullName());
        }
        PrState state = validate(patch);
        if (patch.reviewers() != null) {
            if (patch.reviewers().size() > PushOptions.MAX_REVIEWERS || patch.reviewers().stream()
                    .anyMatch(handle -> !NamePolicy.isValidHandle(handle))) {
                throw new HintedException(ErrorCode.VALIDATION_FAILED, "reviewers must be up to "
                        + PushOptions.MAX_REVIEWERS + " agent handles", "send {\"reviewers\": [\"alice\", \"bob\"]}");
            }
            checkReviewers(pr.getAuthor(), patch.reviewers());
        }
        return locks.withLock(repo.getId(), () -> {
            if (patch.target() != null && !patch.target().equals(pr.getTargetBranch())) {
                changeTarget(repo, pr, patch.target(), actor);
            }
            return tx.execute(status -> {
                PullRequest current = pulls.findById(pr.getId()).orElseThrow();
                Instant now = clock.instant();
                if (patch.title() != null) {
                    current.setTitle(patch.title());
                }
                if (patch.description() != null) {
                    current.setDescription(patch.description().isEmpty() ? null : patch.description());
                }
                if (patch.draft() != null) {
                    requireOpen(repo, current);
                    current.setDraft(patch.draft());
                }
                if (patch.autoMerge() != null) {
                    requireOpen(repo, current);
                    current.setAutoMerge(patch.autoMerge());
                    current.setAutoMergeBlockedReason(null);
                }
                if (patch.reviewers() != null) {
                    replaceReviewers(current, patch.reviewers());
                }
                if (state != null && state != current.getState()) {
                    changeState(repo, current, state, actor.agent(), now);
                }
                current.touch(now);
                events.publishEvent(new PullRequestChanged(repo.getId(), current.getId()));
                return pulls.saveAndFlush(current);
            });
        });
    }

    private void changeTarget(Repo repo, PullRequest pr, String target, Provenance actor) {
        requireOpen(repo, pr);
        try (Repository db = storage.open(repo.getId())) {
            Basis basis = basis(db, repo, pr.getName(), target, ObjectId.fromString(pr.getHeadSha()));
            addRevision(db, repo, pr, basis, PushOptions.NONE, actor, "target_change");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void changeState(Repo repo, PullRequest pr, PrState state, String actor, Instant now) {
        if (pr.getState() == PrState.MERGED) {
            throw notOpen(repo, pr);
        }
        if (state == PrState.CLOSED) {
            pr.close(now);
            events.publishEvent(new DomainEvent("pr.closed", repo.getId(), pr.getId(), actor,
                    Map.of("number", pr.getNumber())));
            return;
        }
        pulls.findOpen(repo.getId(), pr.getAuthor(), pr.getName()).ifPresent(other -> {
            throw new HintedException(ErrorCode.ALREADY_EXISTS, "PR #" + other.getNumber() + " by '"
                    + pr.getAuthor() + "' is already open under the name '" + pr.getName() + "'",
                    "continue in PR #" + other.getNumber() + ", or close it first");
        });
        pr.reopen(now);
        events.publishEvent(new DomainEvent("pr.reopened", repo.getId(), pr.getId(), actor,
                Map.of("number", pr.getNumber())));
    }

    private static PrState validate(Patch patch) {
        if (patch.title() != null && (patch.title().isBlank() || patch.title().length() > PushOptions.MAX_TITLE)) {
            throw new HintedException(ErrorCode.VALIDATION_FAILED,
                    "title must be 1-" + PushOptions.MAX_TITLE + " characters", "send a shorter, non-blank title");
        }
        if (patch.description() != null && patch.description().length() > PullRequest.MAX_DESCRIPTION) {
            throw new HintedException(ErrorCode.VALIDATION_FAILED,
                    "description must be at most " + PullRequest.MAX_DESCRIPTION + " characters",
                    "shorten the description");
        }
        if (patch.target() != null) {
            String problem = NamePolicy.refNameProblem(patch.target());
            if (problem != null) {
                throw new HintedException(ErrorCode.VALIDATION_FAILED, "target '" + patch.target()
                        + "' is not a branch name: " + problem, "pick an existing branch");
            }
        }
        if (patch.state() == null) {
            return null;
        }
        return switch (patch.state().toUpperCase(Locale.ROOT)) {
            case "OPEN" -> PrState.OPEN;
            case "CLOSED" -> PrState.CLOSED;
            default -> throw new HintedException(ErrorCode.VALIDATION_FAILED,
                    "state can be set to OPEN or CLOSED, not '" + patch.state() + "'",
                    "merge with POST /v1/repos/{owner}/{name}/pulls/N/merge");
        };
    }

    static void requireOpen(Repo repo, PullRequest pr) {
        if (!pr.isOpen()) {
            throw notOpen(repo, pr);
        }
    }

    static HintedException notOpen(Repo repo, PullRequest pr) {
        return new HintedException(ErrorCode.NOT_OPEN, "PR #" + pr.getNumber() + " is " + pr.getState(),
                pr.getState() == PrState.CLOSED
                        ? "reopen it first: PATCH " + api(repo, pr) + " {\"state\":\"OPEN\"}"
                        : "open a new pull request: git push origin HEAD:refs/heads/pr/<name>");
    }

    /** Steps 2-5 of §7.2: the target exists, shares history with the head and would actually change. */
    Basis basis(Repository db, Repo repo, String name, String target, ObjectId headId) {
        try (RevWalk walk = new RevWalk(db)) {
            Ref targetRef = db.exactRef(Constants.R_HEADS + target);
            if (targetRef == null) {
                throw new HintedException(ErrorCode.VALIDATION_FAILED,
                        "target branch '" + target + "' does not exist in " + repo.getFullName(),
                        "pick an existing branch (GET /v1/repos/" + repo.getFullName()
                                + "/branches) with -o target=<branch>");
            }
            RevCommit tip = walk.parseCommit(targetRef.getObjectId());
            RevCommit head = walk.parseCommit(headId);
            if (MergeabilityService.mergeBase(db, tip, head) == null) {
                throw new HintedException(ErrorCode.UNRELATED_HISTORY,
                        "commit " + head.name() + " shares no history with '" + target + "'",
                        "git fetch origin && git rebase origin/" + target + ", then push again");
            }
            if (walk.isMergedInto(head, tip)) {
                throw new HintedException(ErrorCode.NO_NEW_COMMITS,
                        "'" + target + "' already contains commit " + head.name(),
                        "commit your change on top of origin/" + target + ", then push again");
            }
            // §7.2 step 7: within the sync budget, or UNKNOWN and computed in the background; beyond the budget
            // step 5 (no_effective_changes) cannot be decided and is skipped
            MergeabilityService.Result trial = mergeability.checkWithinBudget(repo.getId(), tip, head);
            if (trial != null && trial.status() == Mergeable.CLEAN && trial.tree().equals(tip.getTree())) {
                throw new HintedException(ErrorCode.NO_EFFECTIVE_CHANGES,
                        "merging " + head.name() + " into '" + target + "' changes nothing: its changes are already"
                                + " there",
                        "git fetch origin && git switch -C " + name + " origin/" + target);
            }
            return new Basis(target, tip, head, trial);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Outcome create(Repository db, Repo repo, String name, Basis basis, PushOptions options,
            Provenance pusher, boolean authorCanWrite) {
        long repoId = repo.getId();
        String agent = pusher.agent();
        Instant now = clock.instant();
        if (pulls.countByRepoIdAndAuthorAndState(repoId, agent, PrState.OPEN) >= limits.maxOpenPullsPerAgent()) {
            throw new HintedException(ErrorCode.QUOTA_EXCEEDED, "you already have " + limits.maxOpenPullsPerAgent()
                    + " open pull requests in " + repo.getFullName(),
                    "close one first: PATCH /v1/repos/" + repo.getFullName() + "/pulls/N {\"state\":\"CLOSED\"}");
        }
        if (pulls.countByRepoIdAndAuthorAndCreatedAtAfter(repoId, agent, now.minus(Duration.ofDays(1)))
                >= limits.maxNewPullsPerDay()) {
            throw new HintedException(ErrorCode.QUOTA_EXCEEDED, "you opened " + limits.maxNewPullsPerDay()
                    + " pull requests in " + repo.getFullName() + " during the last 24 hours",
                    "update an open pull request instead, or try again later");
        }
        var previouslyMerged = pulls.findFirstByRepoIdAndAuthorAndNameAndStateOrderByNumberDesc(repoId, agent, name,
                PrState.MERGED);
        String title = options.title() != null ? options.title() : titleOf(basis.head(), name);
        String description = options.description() != null ? options.description() : descriptionOf(basis.head());
        AtomicInteger refsWritten = new AtomicInteger();
        PullRequest pr;
        try {
            pr = tx.execute(status -> {
                int number = repos.findById(repoId).orElseThrow().allocatePrNumber();
                PullRequest fresh = PullRequest.open(repoId, number, name, agent, basis.target(), title, description,
                        Boolean.TRUE.equals(options.draft()), basis.head().name(), now);
                fresh.setAutoMerge(options.autoMerge());
                record(fresh, basis);
                pulls.saveAndFlush(fresh);
                if (options.reviewers() != null) {
                    replaceReviewers(fresh, options.reviewers());
                }
                revisions.saveAndFlush(new PrRevision(fresh.getId(), 1, basis.head().name(), basis.tip().name(),
                        pusher, now));
                refsWritten.set(number);
                writeRefs(db, number, 1, basis.head(), agent);
                events.publishEvent(new DomainEvent("pr.opened", repoId, fresh.getId(), agent, Map.of(
                        "number", number, "name", name, "target", basis.target(), "rev", 1,
                        "headSha", basis.head().name())));
                events.publishEvent(new PullRequestChanged(repoId, fresh.getId()));
                return fresh;
            });
        } catch (RuntimeException e) {
            if (refsWritten.get() > 0) {
                restoreRefs(db, repoId, refsWritten.get());
            }
            throw e;
        }
        List<String> lines = summary("Created PR #" + pr.getNumber() + " -> " + pr.getTargetBranch() + " (rev 1)",
                repo, pr, authorCanWrite);
        previouslyMerged.ifPresent(merged -> lines.add(1, "[agenthub] PR #" + merged.getNumber()
                + " was merged; this is a new PR #" + pr.getNumber()));
        return new Outcome(pr, true, lines);
    }

    private List<String> unchanged(Repo repo, PullRequest open, PushOptions options) {
        if (!options.applied().isEmpty()) {
            tx.executeWithoutResult(status -> {
                PullRequest pr = pulls.findById(open.getId()).orElseThrow();
                apply(pr, options);
                if (options.reviewers() != null) {
                    replaceReviewers(pr, options.reviewers());
                }
                pr.touch(clock.instant());
                events.publishEvent(new PullRequestChanged(repo.getId(), pr.getId()));
            });
        }
        String applied = options.applied().isEmpty() ? "" : " (applied: " + String.join(", ", options.applied())
                + ")";
        return List.of("[agenthub] PR #" + open.getNumber() + " unchanged" + applied,
                "  Status:  GET " + api(repo, open));
    }

    /**
     * Revision K+1 (§7.2 step 6): new head and/or target, based on the current target tip; approvals and
     * blocking reviews of earlier revisions stop counting because they belong to another revision.
     */
    PullRequest addRevision(Repository db, Repo repo, PullRequest open, Basis basis, PushOptions options,
            Provenance pusher, String reason) {
        if (open.getCurrentRev() >= limits.maxRevisionsPerPull()) {
            throw new HintedException(ErrorCode.QUOTA_EXCEEDED, "PR #" + open.getNumber() + " already has "
                    + limits.maxRevisionsPerPull() + " revisions",
                    "open a new pull request under another name: git push origin HEAD:refs/heads/pr/<new-name>");
        }
        Instant now = clock.instant();
        boolean[] refsWritten = {false};
        try {
            return tx.execute(status -> {
                PullRequest pr = pulls.findById(open.getId()).orElseThrow();
                int rev = pr.getCurrentRev() + 1;
                pr.newRevision(rev, basis.head().name(), basis.target(), now);
                pr.setAutoMergeBlockedReason(null);
                apply(pr, options);
                if (options.reviewers() != null) {
                    replaceReviewers(pr, options.reviewers());
                }
                record(pr, basis);
                pulls.saveAndFlush(pr);
                revisions.saveAndFlush(new PrRevision(pr.getId(), rev, basis.head().name(), basis.tip().name(),
                        pusher, now));
                refsWritten[0] = true;
                writeRefs(db, pr.getNumber(), rev, basis.head(), pusher.agent());
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("number", pr.getNumber());
                payload.put("rev", rev);
                payload.put("headSha", basis.head().name());
                payload.put("target", basis.target());
                payload.put("reason", reason);
                events.publishEvent(new DomainEvent("pr.updated", repo.getId(), pr.getId(), pusher.agent(), payload));
                events.publishEvent(new PullRequestChanged(repo.getId(), pr.getId()));
                return pr;
            });
        } catch (RuntimeException e) {
            if (refsWritten[0]) {
                restoreRefs(db, repo.getId(), open.getNumber());
            }
            throw e;
        }
    }

    private static void apply(PullRequest pr, PushOptions options) {
        if (options.title() != null) {
            pr.setTitle(options.title());
        }
        if (options.description() != null) {
            pr.setDescription(options.description());
        }
        if (options.draft() != null) {
            pr.setDraft(options.draft());
        }
        if (options.autoMerge() != null) {
            pr.setAutoMerge(options.autoMerge());
            pr.setAutoMergeBlockedReason(null);
        }
    }

    private void replaceReviewers(PullRequest pr, List<String> reviewers) {
        requested.deleteAllOfPr(pr.getId());
        reviewers.stream().distinct().forEach(agent -> requested.save(new PrReviewer(pr.getId(), agent)));
    }

    private static void checkReviewers(String author, List<String> reviewers) {
        if (reviewers != null && reviewers.contains(author)) {
            throw new HintedException(ErrorCode.VALIDATION_FAILED, "the author '" + author + "' cannot review the own"
                    + " pull request", "request reviews from other WRITE members");
        }
    }

    /** §7.3: requested reviewers without WRITE cannot approve and will not get the review task. */
    List<String> reviewerWarnings(Repo repo, List<String> reviewers) {
        return reviewers.stream()
                .filter(agent -> !reviews.canWrite(repo, agent))
                .map(agent -> "reviewer '" + agent + "' has no WRITE role in " + repo.getFullName()
                        + " and will not get the review task")
                .toList();
    }

    private static void record(PullRequest pr, Basis basis) {
        MergeabilityService.Result trial = basis.mergeability();
        if (trial == null) {
            pr.recordMergeability(Mergeable.UNKNOWN, null, List.of(), null);
        } else {
            pr.recordMergeability(trial.status(), basis.tip().name(), trial.conflictPaths(), trial.error());
        }
    }

    private void writeRefs(Repository db, int number, int rev, ObjectId head, String agent) {
        try {
            refs.write(db, number, rev, head, identities.agent(agent));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** §13: after a failed transaction the refs follow the database again (best effort; startup repeats it). */
    void restoreRefs(Repository db, long repoId, int number) {
        try {
            PullRequest pr = pulls.findByRepoIdAndNumber(repoId, number).orElse(null);
            List<PrRevision> revs = pr == null ? List.of() : revisions.findByPrIdOrderByRev(pr.getId());
            refs.restore(db, number, pr, revs, identities.server());
        } catch (Exception e) {
            log.warn("refs of PR #{} in repository {} could not be restored; startup will retry", number, repoId, e);
        }
    }

    List<String> summary(String headline, Repo repo, PullRequest pr, boolean authorCanWrite) {
        String api = api(repo, pr);
        List<String> lines = new ArrayList<>();
        lines.add("[agenthub] " + headline);
        lines.add("  Status:  GET " + api);
        if (pr.isDraft()) {
            lines.add("  Draft:   mark it ready with -o ready or PATCH " + api + " {\"draft\":false}");
        }
        if (pr.getMergeable() == Mergeable.CONFLICT) {
            List<String> paths = pr.getConflictPaths();
            lines.add("  Conflict: " + String.join(", ", paths.subList(0, Math.min(5, paths.size())))
                    + (paths.size() > 5 ? ", ..." : "") + "; git fetch origin && git rebase origin/"
                    + pr.getTargetBranch() + ", then push again");
        }
        int needed = ApprovalRules.required(authorCanWrite,
                reviews.requiredApprovals(repo.getId(), pr.getTargetBranch()));
        String approvals = needed + (needed == 1 ? " approval" : " approvals") + " from a WRITE member";
        if (!autoMerge.enabled(pr)) {
            lines.add(needed == 0
                    ? "  Merge:   a WRITE member can merge it now: POST " + api + "/merge"
                    : "  Merge:   needs " + approvals + ", then POST " + api + "/merge");
        } else if (pr.isDraft()) {
            lines.add("  Auto-merge: waiting for the PR to be ready (-o ready)");
        } else if (pr.getMergeable() == Mergeable.CONFLICT) {
            lines.add("  Auto-merge: blocked by the conflict");
        } else if (needed > 0) {
            lines.add("  Auto-merge: waiting for " + approvals);
        } else {
            lines.add("  Auto-merge: merging as soon as the merge check passes");
        }
        if (needed > 0 && reviews.otherWriters(repo, pr.getAuthor()).isEmpty()) {
            lines.add("  Note:    " + MergeService.soleWriterHint(repo, pr));
        }
        lines.add("  Update:  commit and run the same push again (no --force needed)");
        return lines;
    }

    public static String api(Repo repo, PullRequest pr) {
        return "/v1/repos/" + repo.getFullName() + "/pulls/" + pr.getNumber();
    }

    private static String titleOf(RevCommit head, String name) {
        String subject = head.getShortMessage().strip();
        if (subject.isEmpty()) {
            return name;
        }
        return subject.length() <= PushOptions.MAX_TITLE ? subject : subject.substring(0, PushOptions.MAX_TITLE);
    }

    private static String descriptionOf(RevCommit head) {
        String message = head.getFullMessage().strip();
        int body = message.indexOf("\n\n");
        if (body < 0) {
            return null;
        }
        String text = message.substring(body + 2).strip();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() <= PullRequest.MAX_DESCRIPTION ? text : text.substring(0, PullRequest.MAX_DESCRIPTION);
    }
}

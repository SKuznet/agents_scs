package org.example.agenthub.pr;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.example.agenthub.access.RepoAccess;
import org.example.agenthub.access.RepoAccessService;
import org.example.agenthub.access.Role;
import org.example.agenthub.events.DomainEvent;
import org.example.agenthub.git.RepoLocks;
import org.example.agenthub.repo.BranchProtection;
import org.example.agenthub.repo.BranchProtectionRepository;
import org.example.agenthub.repo.Identities;
import org.example.agenthub.repo.MergeStrategy;
import org.example.agenthub.repo.Repo;
import org.example.agenthub.repo.RepoStorage;
import org.example.agenthub.web.ErrorCode;
import org.example.agenthub.web.HintedException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Manual merge of §7.7 (SQUASH). Under the repository lock: trial merge against the current tip, squash commit
 * by the server, the merge intent in its own transaction, a compare-and-set move of the target ref, then the
 * MERGED state; reconciliation (§7.9) finishes whatever a crash interrupts.
 */
@Service
public class MergeService {

    static final int MAX_ATTEMPTS = 3;
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");

    private final RepoAccessService access;
    private final BranchProtectionRepository protections;
    private final PullRequestRepository pulls;
    private final PrRevisionRepository revisions;
    private final ReviewService reviews;
    private final RepoStorage storage;
    private final RepoLocks locks;
    private final TransactionTemplate tx;
    private final Identities identities;
    private final ApplicationEventPublisher events;
    private final Reconciler reconciler;
    private final Clock clock;

    public MergeService(RepoAccessService access, BranchProtectionRepository protections, PullRequestRepository pulls,
            PrRevisionRepository revisions, ReviewService reviews, RepoStorage storage, RepoLocks locks,
            TransactionTemplate tx, Identities identities, ApplicationEventPublisher events, Reconciler reconciler,
            Clock clock) {
        this.access = access;
        this.protections = protections;
        this.pulls = pulls;
        this.revisions = revisions;
        this.reviews = reviews;
        this.storage = storage;
        this.locks = locks;
        this.tx = tx;
        this.identities = identities;
        this.events = events;
        this.reconciler = reconciler;
        this.clock = clock;
    }

    public record MergeRequest(String strategy, String expectedHeadSha, String message, Boolean bypassProtection) {
    }

    /** Test seam for the races of §18 (2b): runs after the merge intent, right before the target ref moves. */
    @FunctionalInterface
    interface TargetUpdateHook {
        void run(Repository db, String branch) throws Exception;
    }

    volatile TargetUpdateHook beforeTargetUpdate = (db, branch) -> {
    };

    public PullRequest merge(String agent, String owner, String name, int number, MergeRequest request) {
        RepoAccess granted = access.require(agent, owner, name, Role.READ);
        Repo repo = granted.repo();
        if (!granted.role().atLeast(Role.WRITE)) {
            throw RepoAccessService.insufficientRole(repo, agent, granted.role(), Role.WRITE);
        }
        boolean bypass = Boolean.TRUE.equals(request.bypassProtection());
        if (bypass && !granted.role().atLeast(Role.ADMIN)) {
            throw new HintedException(ErrorCode.INSUFFICIENT_ROLE, "bypassProtection needs ADMIN; you have "
                    + granted.role(), "merge once the approvals are in, or ask an ADMIN of " + repo.getFullName());
        }
        MergeStrategy strategy = validate(request);
        return locks.withLock(repo.getId(), () -> {
            PullRequest pr = pulls.require(repo, number);
            if (pr.getState() == PrState.MERGED) {
                return pr;
            }
            check(repo, pr, request.expectedHeadSha());
            return run(repo, pr, agent, bypass, request.message(), strategy, false);
        });
    }

    /**
     * §7.8: the same merge on behalf of {@code agenthub}; {@code expectedHead} is the head of the approved revision
     * and protection is never bypassed.
     */
    public PullRequest autoMerge(Repo repo, int number, String expectedHead) {
        return locks.withLock(repo.getId(), () -> {
            PullRequest pr = pulls.require(repo, number);
            if (pr.getState() == PrState.MERGED) {
                return pr;
            }
            check(repo, pr, expectedHead);
            MergeStrategy strategy = protections.findById(new BranchProtection.Key(repo.getId(),
                    pr.getTargetBranch())).map(BranchProtection::getAutoMergeStrategy).orElse(null);
            return run(repo, pr, Identities.SERVER, false, null,
                    strategy == null ? MergeStrategy.SQUASH : strategy, true);
        });
    }

    private PullRequest run(Repo repo, PullRequest pr, String actor, boolean bypass, String message,
            MergeStrategy strategy, boolean automatically) {
        try (Repository db = storage.open(repo.getId())) {
            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                PullRequest merged = attempt(db, repo, pr, actor, bypass, message, strategy, automatically);
                if (merged != null) {
                    reconciler.reconcileIntents(repo.getId());
                    events.publishEvent(new TargetMoved(repo.getId(), merged.getTargetBranch()));
                    return merged;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        throw new HintedException(ErrorCode.CONCURRENT_UPDATE, "'" + pr.getTargetBranch() + "' kept moving; the"
                + " merge was given up after " + MAX_ATTEMPTS + " attempts", "retry the merge");
    }

    private static MergeStrategy validate(MergeRequest request) {
        MergeStrategy strategy;
        try {
            strategy = request.strategy() == null ? MergeStrategy.SQUASH
                    : MergeStrategy.valueOf(request.strategy().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new HintedException(ErrorCode.VALIDATION_FAILED, "unknown merge strategy '" + request.strategy()
                    + "'", "use SQUASH (the default) or MERGE");
        }
        if (request.expectedHeadSha() == null || !SHA.matcher(request.expectedHeadSha()).matches()) {
            throw new HintedException(ErrorCode.VALIDATION_FAILED, "'expectedHeadSha' (40 hex digits) is required",
                    "send the headSha you reviewed, from GET /v1/repos/{owner}/{name}/pulls/N");
        }
        return strategy;
    }

    /** The checks of §7.7 that need no git: open, not a draft, still the head that was reviewed. */
    private static void check(Repo repo, PullRequest pr, String expectedHead) {
        String api = PullRequestService.api(repo, pr);
        PullRequestService.requireOpen(repo, pr);
        if (pr.isDraft()) {
            throw new HintedException(ErrorCode.DRAFT, "PR #" + pr.getNumber() + " is a draft",
                    "mark it ready first: PATCH " + api + " {\"draft\":false}");
        }
        if (!pr.getHeadSha().equals(expectedHead)) {
            throw new HintedException(ErrorCode.STALE_HEAD, "expected head " + expectedHead + ", current "
                    + pr.getHeadSha(), "GET " + api + " and review the new revision");
        }
    }

    /** One pass of steps 1-5; {@code null} when the target moved under us and the pass must be repeated. */
    private PullRequest attempt(Repository db, Repo repo, PullRequest pr, String agent, boolean bypass,
            String message, MergeStrategy strategy, boolean automatically) throws IOException {
        String targetRef = Constants.R_HEADS + pr.getTargetBranch();
        Ref target = db.exactRef(targetRef);
        if (target == null) {
            throw new HintedException(ErrorCode.VALIDATION_FAILED, "target branch '" + pr.getTargetBranch()
                    + "' no longer exists", "retarget: PATCH " + PullRequestService.api(repo, pr)
                    + " {\"target\":\"<branch>\"}");
        }
        ApprovalRules.Status approvals;
        ObjectId commit;
        try (RevWalk walk = new RevWalk(db); ObjectInserter inserter = MergeabilityService.scratchInserter(db)) {
            RevCommit tip = walk.parseCommit(target.getObjectId());
            MergeabilityService.Result trial = MergeabilityService.merge(db, inserter, tip,
                    ObjectId.fromString(pr.getHeadSha()));
            if (trial.status() != Mergeable.CLEAN) {
                throw new HintedException(ErrorCode.CONFLICT, "merging PR #" + pr.getNumber() + " into '"
                        + pr.getTargetBranch() + "' " + (trial.status() == Mergeable.CONFLICT
                        ? "conflicts in: " + String.join(", ", trial.conflictPaths()) : "failed: " + trial.error()),
                        "git fetch origin && git rebase origin/" + pr.getTargetBranch()
                                + ", then git push origin HEAD:refs/heads/pr/" + pr.getName());
            }
            if (trial.tree().equals(tip.getTree())) {
                // §7.7: the changes are already in the target; merging would only add an empty commit
                throw new HintedException(ErrorCode.NO_EFFECTIVE_CHANGES, "merging PR #" + pr.getNumber() + " into '"
                        + pr.getTargetBranch() + "' changes nothing: its changes are already there",
                        "close it: PATCH " + PullRequestService.api(repo, pr) + " {\"state\":\"CLOSED\"}");
            }
            approvals = reviews.status(repo, pr);
            if (!bypass && !approvals.satisfied()) {
                throw approvalsRequired(repo, pr, approvals);
            }
            commit = inserter.insert(commit(pr, tip, trial.tree(), approvals, message, strategy));
            inserter.flush();
        }
        ObjectId tip = target.getObjectId();
        tx.executeWithoutResult(status -> pulls.findById(pr.getId()).orElseThrow().startMerge(commit.name(), agent));
        try {
            beforeTargetUpdate.run(db, pr.getTargetBranch());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        RefUpdate update = db.updateRef(targetRef);
        update.setExpectedOldObjectId(tip);
        update.setNewObjectId(commit);
        update.setRefLogIdent(identities.agent(agent));
        update.setRefLogMessage("merge PR #" + pr.getNumber(), false);
        RefUpdate.Result result = update.update();
        if (result != RefUpdate.Result.FAST_FORWARD && result != RefUpdate.Result.FORCED) {
            tx.executeWithoutResult(status -> pulls.findById(pr.getId()).orElseThrow().abandonMerge());
            if (result == RefUpdate.Result.LOCK_FAILURE) {
                return null;
            }
            throw new IOException("cannot move " + targetRef + ": " + result);
        }
        return tx.execute(status -> {
            PullRequest merged = pulls.findById(pr.getId()).orElseThrow();
            merged.merged(commit.name(), agent, clock.instant(), automatically);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("number", pr.getNumber());
            payload.put("mergeCommitSha", commit.name());
            payload.put("strategy", strategy);
            payload.put("approvals", approvals.approvals());
            payload.put("bypassProtection", bypass && !approvals.satisfied());
            payload.put("auto", automatically);
            events.publishEvent(new DomainEvent("pr.merged", repo.getId(), pr.getId(), agent, payload));
            return merged;
        });
    }

    /**
     * SQUASH: one commit on top of the tip with the merged tree; MERGE: a merge commit with parents (tip, head).
     * Either way the server's trailers close the message (§7.7, D9).
     */
    private CommitBuilder commit(PullRequest pr, RevCommit tip, ObjectId tree, ApprovalRules.Status approvals,
            String message, MergeStrategy strategy) {
        PrRevision revision = revisions.findByPrIdAndRev(pr.getId(), pr.getCurrentRev()).orElseThrow();
        StringBuilder text = new StringBuilder();
        if (message != null && !message.isBlank()) {
            text.append(message.strip());
        } else if (strategy == MergeStrategy.MERGE) {
            text.append("Merge PR #").append(pr.getNumber()).append(": ").append(pr.getTitle());
        } else {
            text.append(pr.getTitle()).append(" (#").append(pr.getNumber()).append(')');
            if (pr.getDescription() != null && !pr.getDescription().isBlank()) {
                text.append("\n\n").append(pr.getDescription().strip());
            }
        }
        List<String> trailers = new ArrayList<>();
        trailers.add("AgentHub-PR: #" + pr.getNumber());
        trailer(trailers, "AgentHub-Session", revision.getSession());
        trailer(trailers, "AgentHub-Model", revision.getModel());
        trailer(trailers, "AgentHub-Operator", revision.getOperator());
        for (String reviewer : approvals.approvals()) {
            var ident = identities.agent(reviewer);
            trailers.add("Reviewed-By: " + ident.getName() + " <" + ident.getEmailAddress() + ">");
        }
        text.append("\n\n").append(String.join("\n", trailers)).append('\n');

        CommitBuilder commit = new CommitBuilder();
        commit.setTreeId(tree);
        if (strategy == MergeStrategy.MERGE) {
            commit.setParentIds(tip, ObjectId.fromString(pr.getHeadSha()));
        } else {
            commit.setParentId(tip);
        }
        commit.setAuthor(identities.agent(pr.getAuthor()));
        commit.setCommitter(identities.server());
        commit.setMessage(text.toString());
        return commit;
    }

    private static void trailer(List<String> trailers, String key, String value) {
        if (value != null && !value.isBlank()) {
            trailers.add(key + ": " + value);
        }
    }

    private HintedException approvalsRequired(Repo repo, PullRequest pr, ApprovalRules.Status approvals) {
        String api = PullRequestService.api(repo, pr);
        StringBuilder detail = new StringBuilder("PR #" + pr.getNumber() + " has " + approvals.approvals().size()
                + " of " + approvals.required() + " required approvals on rev " + pr.getCurrentRev());
        if (!approvals.blockers().isEmpty()) {
            detail.append("; changes requested by ").append(String.join(", ", approvals.blockers()));
        }
        String hint;
        if (!approvals.blockers().isEmpty()) {
            hint = "address the review of " + String.join(", ", approvals.blockers())
                    + " and push a new revision, or ask them to approve: POST " + api + "/reviews";
        } else if (reviews.otherWriters(repo, pr.getAuthor()).isEmpty()) {
            hint = soleWriterHint(repo, pr);
        } else {
            hint = "ask a WRITE member other than '" + pr.getAuthor() + "' (" + String.join(", ",
                    reviews.otherWriters(repo, pr.getAuthor())) + ") to approve: POST " + api + "/reviews {\"rev\":"
                    + pr.getCurrentRev() + ",\"verdict\":\"APPROVE\"}";
        }
        return new HintedException(ErrorCode.APPROVALS_REQUIRED, detail.toString(), hint);
    }

    /** §7.8: say plainly that nobody else can approve; requiredApprovals=0 helps only authors with WRITE. */
    static String soleWriterHint(Repo repo, PullRequest pr) {
        return "you are the only WRITE member: POST " + PullRequestService.api(repo, pr)
                + "/merge {\"bypassProtection\":true} (ADMIN) or set requiredApprovals=0 with PUT /v1/repos/"
                + repo.getFullName() + "/protection?branch=" + pr.getTargetBranch();
    }
}

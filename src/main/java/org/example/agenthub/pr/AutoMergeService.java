package org.example.agenthub.pr;

import java.io.IOException;
import java.util.Map;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.example.agenthub.events.DomainEvent;
import org.example.agenthub.git.RepoLocks;
import org.example.agenthub.repo.BranchProtection;
import org.example.agenthub.repo.BranchProtectionRepository;
import org.example.agenthub.repo.Identities;
import org.example.agenthub.repo.Repo;
import org.example.agenthub.repo.RepoRepository;
import org.example.agenthub.repo.RepoStorage;
import org.example.agenthub.web.HintedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Auto-merge of §7.8: an open, ready PR with auto-merge on, a CLEAN mergeability computed against the current tip
 * and invariant D8 satisfied is merged by {@code agenthub} through {@link MergeService}, never bypassing
 * protection. Waiting (approvals, the merge check) is silent; a failure for any other reason is recorded in
 * {@code auto_merge_blocked_reason} with a {@code pr.auto_merge_blocked} event.
 */
@Service
public class AutoMergeService {

    private static final Logger log = LoggerFactory.getLogger(AutoMergeService.class);

    private final RepoRepository repos;
    private final PullRequestRepository pulls;
    private final BranchProtectionRepository protections;
    private final ReviewService reviews;
    private final MergeService merges;
    private final RepoStorage storage;
    private final RepoLocks locks;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;

    public AutoMergeService(RepoRepository repos, PullRequestRepository pulls, BranchProtectionRepository protections,
            ReviewService reviews, MergeService merges, RepoStorage storage, RepoLocks locks, TransactionTemplate tx,
            ApplicationEventPublisher events) {
        this.repos = repos;
        this.pulls = pulls;
        this.protections = protections;
        this.reviews = reviews;
        this.merges = merges;
        this.storage = storage;
        this.locks = locks;
        this.tx = tx;
        this.events = events;
    }

    /** The PR's own choice, else the target branch protection (§7.8); branches without a record: off. */
    public boolean enabled(PullRequest pr) {
        if (pr.getAutoMerge() != null) {
            return pr.getAutoMerge();
        }
        return protections.findById(new BranchProtection.Key(pr.getRepoId(), pr.getTargetBranch()))
                .map(BranchProtection::isAutoMerge).orElse(false);
    }

    public void evaluate(long repoId, long prId) {
        Repo repo = repos.findById(repoId).filter(r -> r.getDeletedAt() == null).orElse(null);
        PullRequest pr = pulls.findById(prId).orElse(null);
        if (repo == null || pr == null || !pr.isOpen() || pr.isDraft() || !enabled(pr)) {
            return;
        }
        if (!reviews.status(repo, pr).satisfied()) {
            return;
        }
        String tip = tipOf(repoId, pr.getTargetBranch());
        if (pr.getMergeable() == Mergeable.UNKNOWN || tip == null || !tip.equals(pr.getMergeableTargetSha())) {
            return;
        }
        if (pr.getMergeable() != Mergeable.CLEAN) {
            block(repo, pr, pr.getMergeable() == Mergeable.CONFLICT
                    ? "conflicts with '" + pr.getTargetBranch() + "' in: " + String.join(", ", pr.getConflictPaths())
                    : "cannot be merged: " + pr.getMergeableError());
            return;
        }
        try {
            merges.autoMerge(repo, pr.getNumber(), pr.getHeadSha());
        } catch (HintedException e) {
            switch (e.code()) {
                case APPROVALS_REQUIRED, STALE_HEAD, NOT_OPEN, DRAFT, BUSY -> {
                    // the PR changed or is still waiting; its next trigger evaluates it again
                }
                default -> block(repo, pr, e.getMessage());
            }
        } catch (RuntimeException e) {
            log.warn("auto-merge of PR #{} in {} failed", pr.getNumber(), repo.getFullName(), e);
            block(repo, pr, "the merge failed on the server; it is retried on the next change");
        }
    }

    private void block(Repo repo, PullRequest pr, String reason) {
        locks.withLock(repo.getId(), () -> tx.executeWithoutResult(status -> {
            PullRequest current = pulls.findById(pr.getId()).orElseThrow();
            if (!current.isOpen() || !current.getHeadSha().equals(pr.getHeadSha())
                    || reason.equals(current.getAutoMergeBlockedReason())) {
                return;
            }
            current.setAutoMergeBlockedReason(reason);
            events.publishEvent(new DomainEvent("pr.auto_merge_blocked", repo.getId(), pr.getId(), Identities.SERVER,
                    Map.of("number", pr.getNumber(), "reason", current.getAutoMergeBlockedReason())));
        }));
    }

    private String tipOf(long repoId, String branch) {
        try (Repository db = storage.open(repoId)) {
            Ref ref = db.exactRef(Constants.R_HEADS + branch);
            return ref == null ? null : ref.getObjectId().name();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

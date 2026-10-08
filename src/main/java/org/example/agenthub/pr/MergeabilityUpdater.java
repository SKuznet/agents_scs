package org.example.agenthub.pr;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.example.agenthub.config.AgentHubProperties;
import org.example.agenthub.events.DomainEvent;
import org.example.agenthub.events.StateChanged;
import org.example.agenthub.git.RepoLocks;
import org.example.agenthub.repo.RepoStorage;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps {@code pull_request.mergeable} in step with the target (§7.4): the trial merge runs outside the lock, the
 * result is written under it with compare-and-set on the head, and only if the target did not move meanwhile.
 */
@Component
public class MergeabilityUpdater {

    static final int MAX_ATTEMPTS = 3;

    private final MergeabilityService mergeability;
    private final PullRequestRepository pulls;
    private final RepoStorage storage;
    private final RepoLocks locks;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final Executor executor;
    private final Duration budget;

    public MergeabilityUpdater(MergeabilityService mergeability, PullRequestRepository pulls, RepoStorage storage,
            RepoLocks locks, TransactionTemplate tx, ApplicationEventPublisher events, Executor executor,
            AgentHubProperties properties) {
        this.mergeability = mergeability;
        this.pulls = pulls;
        this.storage = storage;
        this.locks = locks;
        this.tx = tx;
        this.events = events;
        this.executor = executor;
        this.budget = properties.mergeabilityBudget();
    }

    /** §7.2 step 7: the trial merge of a push, given at most the sync budget; {@code null} means "later". */
    public MergeabilityService.Result checkWithinBudget(long repoId, ObjectId tip, ObjectId head) {
        CompletableFuture<MergeabilityService.Result> trial;
        try {
            trial = CompletableFuture.supplyAsync(() -> {
                try (Repository db = storage.open(repoId)) {
                    return mergeability.check(db, tip, head);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }, executor);
        } catch (RejectedExecutionException e) {
            return null;
        }
        try {
            return trial.get(budget.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public void refresh(long repoId, long prId) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            PullRequest pr = pulls.findById(prId).orElse(null);
            if (pr == null || !pr.isOpen()) {
                return;
            }
            String tip = tipOf(repoId, pr.getTargetBranch());
            if (tip != null && tip.equals(pr.getMergeableTargetSha()) && pr.getMergeable() != Mergeable.UNKNOWN) {
                return;
            }
            MergeabilityService.Result result = tip == null
                    ? new MergeabilityService.Result(Mergeable.ERROR, null, java.util.List.of(),
                            "target branch '" + pr.getTargetBranch() + "' does not exist")
                    : trial(repoId, tip, pr.getHeadSha());
            Boolean written = locks.withLock(repoId, () -> {
                if (!Objects.equals(tip, tipOf(repoId, pr.getTargetBranch()))) {
                    return false;
                }
                return tx.execute(status -> {
                    Mergeable previous = pulls.findById(prId).map(PullRequest::getMergeable).orElse(null);
                    int rows = pulls.recordMergeability(prId, pr.getHeadSha(), result.status(), tip,
                            result.conflictPaths().isEmpty() ? null : String.join("\n", result.conflictPaths()),
                            result.error());
                    if (rows == 1) {
                        events.publishEvent(new StateChanged(repoId));
                    }
                    if (rows == 1 && previous != result.status()) {
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("number", pr.getNumber());
                        payload.put("mergeable", result.status());
                        payload.put("previous", previous);
                        payload.put("targetSha", tip);
                        events.publishEvent(new DomainEvent("pr.mergeable_changed", repoId, prId, "agenthub",
                                payload));
                    }
                    return true;
                });
            });
            if (Boolean.TRUE.equals(written)) {
                return;
            }
        }
    }

    private MergeabilityService.Result trial(long repoId, String tip, String head) {
        try (Repository db = storage.open(repoId)) {
            return mergeability.check(db, ObjectId.fromString(tip), ObjectId.fromString(head));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
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

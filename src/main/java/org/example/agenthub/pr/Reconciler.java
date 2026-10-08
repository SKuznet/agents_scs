package org.example.agenthub.pr;

import java.io.IOException;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.example.agenthub.events.DomainEvent;
import org.example.agenthub.git.RepoLocks;
import org.example.agenthub.repo.Identities;
import org.example.agenthub.repo.RepoRepository;
import org.example.agenthub.repo.RepoStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reconciliation of §7.9 and restoration of {@code refs/pull/*} (§13). The database is the source of truth for
 * PRs, git for branches; trailers in commits are never trusted for this.
 */
@Component
public class Reconciler {

    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);
    private static final Pattern PULL_REF = Pattern.compile("refs/pull/(\\d+)/.*");

    private final RepoRepository repos;
    private final PullRequestRepository pulls;
    private final PrRevisionRepository revisions;
    private final RepoStorage storage;
    private final RepoLocks locks;
    private final TransactionTemplate tx;
    private final PullRefs refs;
    private final Identities identities;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public Reconciler(RepoRepository repos, PullRequestRepository pulls, PrRevisionRepository revisions,
            RepoStorage storage, RepoLocks locks, TransactionTemplate tx, PullRefs refs, Identities identities,
            ApplicationEventPublisher events, Clock clock) {
        this.repos = repos;
        this.pulls = pulls;
        this.revisions = revisions;
        this.storage = storage;
        this.locks = locks;
        this.tx = tx;
        this.refs = refs;
        this.identities = identities;
        this.events = events;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileAll() {
        for (long repoId : repos.findLiveIds()) {
            try {
                locks.withLock(repoId, () -> {
                    reconcileIntents(repoId);
                    restoreRefs(repoId);
                });
            } catch (Exception e) {
                log.warn("reconciliation of repository {} failed", repoId, e);
            }
        }
    }

    /**
     * Merge intents left behind (§7.7 step 3): the commit reached the target, so the PR is MERGED; or it did
     * not, so the intent is dropped. Call under the repository lock.
     */
    public void reconcileIntents(long repoId) {
        List<PullRequest> pending = pulls.findByRepoIdAndMergingCommitShaIsNotNull(repoId);
        if (pending.isEmpty()) {
            return;
        }
        try (Repository db = storage.open(repoId)) {
            for (PullRequest pr : pending) {
                ObjectId commit = ObjectId.fromString(pr.getMergingCommitSha());
                boolean landed = contains(db, pr.getTargetBranch(), commit);
                tx.executeWithoutResult(status -> {
                    PullRequest current = pulls.findById(pr.getId()).orElseThrow();
                    if (landed) {
                        current.merged(commit.name(), current.getMergedBy(), clock.instant(), false);
                        mergedEvent(current, current.getMergedBy(), "merge intent");
                    } else {
                        current.abandonMerge();
                    }
                });
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot reconcile merges of repository " + repoId, e);
        }
    }

    /** Open PRs whose head {@code branch} now contains were merged by a push (§7.9). */
    public void afterPush(long repoId, String branch, ObjectId newTip, String pusher) {
        locks.withLock(repoId, () -> {
            List<PullRequest> open = pulls.findByRepoIdAndStateAndTargetBranch(repoId, PrState.OPEN, branch);
            if (open.isEmpty()) {
                return;
            }
            try (Repository db = storage.open(repoId); RevWalk walk = new RevWalk(db)) {
                for (PullRequest pr : open) {
                    if (walk.isMergedInto(walk.parseCommit(ObjectId.fromString(pr.getHeadSha())),
                            walk.parseCommit(newTip))) {
                        tx.executeWithoutResult(status -> {
                            PullRequest current = pulls.findById(pr.getId()).orElseThrow();
                            current.merged(newTip.name(), pusher, clock.instant(), false);
                            mergedEvent(current, pusher, "push");
                        });
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("cannot reconcile pushes to " + branch + " in " + repoId, e);
            }
        });
    }

    /** {@code refs/pull/*} follow the database: missing ones come back, stray ones go. Call under the lock. */
    public void restoreRefs(long repoId) {
        try (Repository db = storage.open(repoId)) {
            Map<Integer, PullRequest> byNumber = new HashMap<>();
            pulls.findByRepoId(repoId).forEach(pr -> byNumber.put(pr.getNumber(), pr));
            for (Ref ref : db.getRefDatabase().getRefsByPrefix("refs/pull/")) {
                Matcher m = PULL_REF.matcher(ref.getName());
                if (m.matches()) {
                    byNumber.putIfAbsent(Integer.parseInt(m.group(1)), null);
                }
            }
            for (Map.Entry<Integer, PullRequest> entry : byNumber.entrySet()) {
                PullRequest pr = entry.getValue();
                List<PrRevision> revs = pr == null ? List.of() : revisions.findByPrIdOrderByRev(pr.getId());
                refs.restore(db, entry.getKey(), pr, revs, identities.server());
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot restore pull refs of repository " + repoId, e);
        }
    }

    private static boolean contains(Repository db, String branch, ObjectId commit) throws IOException {
        Ref target = db.exactRef(Constants.R_HEADS + branch);
        if (target == null || !db.getObjectDatabase().has(commit)) {
            return false;
        }
        try (RevWalk walk = new RevWalk(db)) {
            return walk.isMergedInto(walk.parseCommit(commit), walk.parseCommit(target.getObjectId()));
        }
    }

    private void mergedEvent(PullRequest pr, String actor, String via) {
        events.publishEvent(new DomainEvent("pr.merged", pr.getRepoId(), pr.getId(), actor == null ? "agenthub"
                : actor, Map.of("number", pr.getNumber(), "mergeCommitSha", pr.getMergeCommitSha(),
                        "reconciled", via)));
    }
}

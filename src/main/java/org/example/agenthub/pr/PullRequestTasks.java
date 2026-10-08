package org.example.agenthub.pr;

import java.util.LinkedHashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Background work on PRs (§13): one queue per repository, drained sequentially by one worker of the shared
 * bounded executor; requests for a PR that is already queued collapse into one. What is lost (a full executor,
 * a restart) is picked up by the Sweeper.
 */
@Component
public class PullRequestTasks {

    private static final Logger log = LoggerFactory.getLogger(PullRequestTasks.class);

    /** The work for one PR: refresh its mergeability, then see whether it can be merged automatically. */
    @FunctionalInterface
    interface Work {
        void process(long repoId, long prId);
    }

    private static final class RepoQueue {
        final LinkedHashSet<Long> pending = new LinkedHashSet<>();
        boolean running;
        boolean paused;
    }

    private final Executor executor;
    private final Work work;
    private final ConcurrentMap<Long, RepoQueue> queues = new ConcurrentHashMap<>();

    @Autowired
    PullRequestTasks(Executor executor, MergeabilityUpdater mergeability, AutoMergeService autoMerge) {
        this(executor, (repoId, prId) -> {
            mergeability.refresh(repoId, prId);
            autoMerge.evaluate(repoId, prId);
        });
    }

    PullRequestTasks(Executor executor, Work work) {
        this.executor = executor;
        this.work = work;
    }

    public void submit(long repoId, long prId) {
        RepoQueue queue = queues.computeIfAbsent(repoId, id -> new RepoQueue());
        synchronized (queue) {
            queue.pending.add(prId);
            if (queue.running || queue.paused) {
                return;
            }
            queue.running = true;
        }
        try {
            executor.execute(() -> drain(repoId, queue));
        } catch (RejectedExecutionException e) {
            synchronized (queue) {
                queue.running = false;
            }
            log.warn("background queue is full; PR {} of repository {} waits for the sweeper", prId, repoId);
        }
    }

    private void drain(long repoId, RepoQueue queue) {
        while (true) {
            long prId;
            synchronized (queue) {
                if (queue.paused || queue.pending.isEmpty()) {
                    queue.running = false;
                    return;
                }
                prId = queue.pending.removeFirst();
            }
            try {
                work.process(repoId, prId);
            } catch (RuntimeException e) {
                log.warn("background work on PR {} of repository {} failed", prId, repoId, e);
            }
        }
    }

    public boolean idle(long repoId) {
        RepoQueue queue = queues.get(repoId);
        if (queue == null) {
            return true;
        }
        synchronized (queue) {
            return !queue.running && queue.pending.isEmpty();
        }
    }

    /** Test seam: hold back the work of a repository to stage races deterministically. */
    void pause(long repoId) {
        RepoQueue queue = queues.computeIfAbsent(repoId, id -> new RepoQueue());
        synchronized (queue) {
            queue.paused = true;
        }
    }

    void resume(long repoId) {
        RepoQueue queue = queues.computeIfAbsent(repoId, id -> new RepoQueue());
        Long first;
        synchronized (queue) {
            queue.paused = false;
            first = queue.pending.isEmpty() ? null : queue.pending.getFirst();
        }
        if (first != null) {
            submit(repoId, first);
        }
    }
}

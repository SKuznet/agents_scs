package org.example.agenthub.maintenance;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.example.agenthub.config.AgentHubProperties;
import org.example.agenthub.git.RepoLocks;
import org.example.agenthub.pr.AutoMergeService;
import org.example.agenthub.pr.Mergeable;
import org.example.agenthub.pr.PrState;
import org.example.agenthub.pr.PullRequest;
import org.example.agenthub.pr.PullRequestRepository;
import org.example.agenthub.pr.PullRequestTasks;
import org.example.agenthub.repo.RepoRepository;
import org.example.agenthub.repo.RepoStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * The periodic safety net of §13 (every {@code agenthub.sweep-interval}, 1 min): PRs whose mergeability is UNKNOWN
 * or computed against an older tip, PRs that may be auto-merged, directories of deleted repositories that could
 * not be removed (Windows holds files), and GC runs that git traffic made skip.
 */
@Component
public class Sweeper implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(Sweeper.class);

    private final RepoRepository repos;
    private final PullRequestRepository pulls;
    private final PullRequestTasks tasks;
    private final AutoMergeService autoMerge;
    private final RepoStorage storage;
    private final RepoLocks locks;
    private final GcScheduler gc;
    private final Duration interval;
    private ScheduledExecutorService timer;

    public Sweeper(RepoRepository repos, PullRequestRepository pulls, PullRequestTasks tasks,
            AutoMergeService autoMerge, RepoStorage storage, RepoLocks locks, GcScheduler gc,
            AgentHubProperties properties) {
        this.repos = repos;
        this.pulls = pulls;
        this.tasks = tasks;
        this.autoMerge = autoMerge;
        this.storage = storage;
        this.locks = locks;
        this.gc = gc;
        this.interval = properties.sweepInterval();
    }

    @Override
    public void afterPropertiesSet() {
        if (!interval.isZero()) {
            timer = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "agenthub-sweeper");
                thread.setDaemon(true);
                return thread;
            });
            timer.scheduleWithFixedDelay(this::sweepQuietly, interval.toMillis(), interval.toMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void destroy() {
        if (timer != null) {
            timer.shutdownNow();
        }
    }

    public void sweep() {
        removeLeftoverDirectories();
        revisitPullRequests();
        gc.collectDue();
    }

    private void sweepQuietly() {
        try {
            sweep();
        } catch (RuntimeException e) {
            log.warn("sweep failed", e);
        }
    }

    private void removeLeftoverDirectories() {
        for (long repoId : repos.findDeletedIds()) {
            if (Files.exists(storage.dirOf(repoId))) {
                try {
                    locks.withLock(repoId, () -> {
                        if (!storage.delete(repoId)) {
                            log.warn("directory of deleted repository {} is still locked", repoId);
                        }
                    });
                } catch (RuntimeException e) {
                    log.warn("directory of deleted repository {} could not be removed", repoId, e);
                }
            }
        }
    }

    private void revisitPullRequests() {
        Map<Long, List<PullRequest>> byRepo = pulls.findByState(PrState.OPEN).stream()
                .collect(Collectors.groupingBy(PullRequest::getRepoId));
        byRepo.forEach((repoId, open) -> {
            try (Repository db = storage.open(repoId)) {
                for (PullRequest pr : open) {
                    Ref target = db.exactRef(Constants.R_HEADS + pr.getTargetBranch());
                    boolean stale = pr.getMergeable() == Mergeable.UNKNOWN || target == null
                            || !target.getObjectId().name().equals(pr.getMergeableTargetSha());
                    if (stale || !pr.isDraft() && autoMerge.enabled(pr)) {
                        tasks.submit(repoId, pr.getId());
                    }
                }
            } catch (IOException e) {
                log.warn("cannot revisit the pull requests of repository {}", repoId, e);
            }
        });
    }
}

package org.example.agenthub.maintenance;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.internal.storage.file.GC;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.GcConfig;
import org.eclipse.jgit.lib.Repository;
import org.example.agenthub.config.AgentHubProperties;
import org.example.agenthub.git.GitTrafficLocks;
import org.example.agenthub.git.RepoLocks;
import org.example.agenthub.repo.RepoRepository;
import org.example.agenthub.repo.RepoService;
import org.example.agenthub.repo.RepoStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * JGit GC of §13 (autoGC is off in every repository): pack-refs, repack, prune of what is older than
 * {@code prune-expire}; never in parallel for one repository (under its {@link RepoLocks} lock), then
 * {@code size_bytes} is recomputed. Triggers: every {@code interval} and after {@code after-pushes} pushes.
 */
@Component
public class GcScheduler implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(GcScheduler.class);

    /**
     * Old packs go after at most an hour (JGit's default): they duplicate the repacked objects, so keeping them
     * for the whole prune-expire would double the size, while a younger pack may belong to a push in flight.
     */
    static final Duration MAX_PACK_EXPIRE = Duration.ofHours(1);

    private final RepoStorage storage;
    private final RepoRepository repos;
    private final RepoLocks locks;
    private final GitTrafficLocks traffic;
    private final RepoService repoService;
    private final Executor executor;
    private final Clock clock;
    private final AgentHubProperties.Gc config;
    private final ConcurrentMap<Long, AtomicInteger> pushesSinceGc = new ConcurrentHashMap<>();
    private final Set<Long> queued = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService timer;

    @Autowired
    public GcScheduler(RepoStorage storage, RepoRepository repos, RepoLocks locks, GitTrafficLocks traffic,
            RepoService repoService, Executor executor, Clock clock, AgentHubProperties properties) {
        this(storage, repos, locks, traffic, repoService, executor, clock, properties.gc());
    }

    GcScheduler(RepoStorage storage, RepoRepository repos, RepoLocks locks, GitTrafficLocks traffic,
            RepoService repoService, Executor executor, Clock clock, AgentHubProperties.Gc config) {
        this.storage = storage;
        this.repos = repos;
        this.locks = locks;
        this.traffic = traffic;
        this.repoService = repoService;
        this.executor = executor;
        this.clock = clock;
        this.config = config;
    }

    @Override
    public void afterPropertiesSet() {
        if (!config.interval().isZero()) {
            timer = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "agenthub-gc");
                thread.setDaemon(true);
                return thread;
            });
            long period = config.interval().toMillis();
            timer.scheduleWithFixedDelay(this::collectAll, period, period, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void destroy() {
        if (timer != null) {
            timer.shutdownNow();
        }
    }

    /**
     * Counts an accepted push; from the {@code after-pushes}-th one on, a GC of that repository is queued (one at
     * a time) until a GC succeeds, so a run skipped because of git traffic is retried by the next push.
     */
    public void pushed(long repoId) {
        if (config.afterPushes() <= 0) {
            return;
        }
        AtomicInteger count = pushesSinceGc.computeIfAbsent(repoId, id -> new AtomicInteger());
        if (count.incrementAndGet() >= config.afterPushes() && queued.add(repoId)) {
            executor.execute(() -> {
                try {
                    collectQuietly(repoId);
                } finally {
                    queued.remove(repoId);
                }
            });
        }
    }

    /** Repositories whose push count reached the threshold but whose GC was skipped (used by the Sweeper). */
    public void collectDue() {
        pushesSinceGc.forEach((repoId, count) -> {
            if (config.afterPushes() > 0 && count.get() >= config.afterPushes()) {
                collectQuietly(repoId);
            }
        });
    }

    void collectAll() {
        for (long repoId : repos.findLiveIds()) {
            collectQuietly(repoId);
        }
    }

    private void collectQuietly(long repoId) {
        try {
            collect(repoId);
        } catch (Exception e) {
            log.warn("GC of repository {} failed", repoId, e);
        }
    }

    /** Runs GC now unless a push or fetch of the repository is in flight; true when it ran. */
    public boolean collect(long repoId) {
        Boolean ran = locks.withLock(repoId, () -> traffic.tryExclusive(repoId, () -> {
            try (Repository db = storage.open(repoId)) {
                gc(db);
                return true;
            } catch (Exception e) {
                throw new IllegalStateException("GC of repository " + repoId + " failed", e);
            }
        }));
        if (ran == null) {
            log.debug("GC of repository {} skipped: git traffic in progress", repoId);
            return false;
        }
        pushesSinceGc.remove(repoId);
        repoService.refreshSize(repoId);
        return true;
    }

    private void gc(Repository db) throws Exception {
        Instant now = clock.instant();
        Duration packExpire = config.pruneExpire().compareTo(MAX_PACK_EXPIRE) < 0 ? config.pruneExpire()
                : MAX_PACK_EXPIRE;
        if (config.pruneExpire().isZero()) {
            // JGit only drops unreachable objects at once with "now"; otherwise it loosens them first
            db.getConfig().setString(ConfigConstants.CONFIG_GC_SECTION, null,
                    ConfigConstants.CONFIG_KEY_PRUNEEXPIRE, "now");
        }
        GC gc = new GC((FileRepository) db);
        gc.setGcConfig(new GcConfig(GcConfig.PackRefsMode.TRUE));
        gc.setExpire(now.minus(config.pruneExpire()));
        gc.setPackExpire(now.minus(packExpire));
        gc.gc().get();
    }
}

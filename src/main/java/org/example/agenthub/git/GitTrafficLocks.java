package org.example.agenthub.git;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

/**
 * "GC versus git traffic" (§13), separate from {@link RepoLocks}: upload-pack and receive-pack hold the read side
 * for the whole request, GC takes the write side only if nobody reads, so it neither prunes the objects of a push
 * that has not updated its refs yet nor closes packs under a fetch.
 */
@Component
public class GitTrafficLocks {

    static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private final ConcurrentMap<Long, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();

    /** Waits up to 30 s while a GC runs; false when it did not finish in time. */
    public boolean acquireRead(long repoId) {
        try {
            return lock(repoId).readLock().tryLock(READ_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void releaseRead(long repoId) {
        lock(repoId).readLock().unlock();
    }

    /** Runs {@code action} exclusively, never waiting: {@code null} when git traffic is in progress. */
    public <T> T tryExclusive(long repoId, Supplier<T> action) {
        ReentrantReadWriteLock lock = lock(repoId);
        if (!lock.writeLock().tryLock()) {
            return null;
        }
        try {
            return action.get();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int readers(long repoId) {
        return lock(repoId).getReadLockCount();
    }

    private ReentrantReadWriteLock lock(long repoId) {
        return locks.computeIfAbsent(repoId, id -> new ReentrantReadWriteLock());
    }
}

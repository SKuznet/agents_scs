package org.example.agenthub.git;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.example.agenthub.web.ErrorCode;
import org.example.agenthub.web.HintedException;
import org.springframework.stereotype.Component;

/**
 * One in-process lock per repository (§13). Always taken outside a transaction: callers run a
 * {@code TransactionTemplate} inside {@link #withLock}, never the other way round.
 */
@Component
public class RepoLocks {

    static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final ConcurrentMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    public <T> T withLock(long repoId, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(repoId, id -> new ReentrantLock());
        if (!tryLock(lock)) {
            throw new HintedException(ErrorCode.BUSY, "repository busy", "retry in a few seconds");
        }
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    public void withLock(long repoId, Runnable action) {
        withLock(repoId, () -> {
            action.run();
            return null;
        });
    }

    private static boolean tryLock(ReentrantLock lock) {
        try {
            return lock.tryLock(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

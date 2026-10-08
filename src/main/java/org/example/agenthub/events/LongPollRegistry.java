package org.example.agenthub.events;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.example.agenthub.config.AgentHubProperties;
import org.example.agenthub.pr.PullRequestChanged;
import org.example.agenthub.web.ErrorCode;
import org.example.agenthub.web.HintedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.context.request.async.DeferredResult;

/**
 * Long-polls of §9.5. A wait is registered first, then its condition is checked, and only then does the request
 * wait; every committed change rechecks the waits it may concern, so no change can slip between check and wait.
 */
@Component
public class LongPollRegistry implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(LongPollRegistry.class);

    private final Set<Waiter<?>> waiters = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> active = new HashMap<>();
    private final ExecutorService rechecks = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "agenthub-long-poll");
        thread.setDaemon(true);
        return thread;
    });
    private final int maxWaits;
    private final Duration maxWait;
    private volatile Runnable beforeWait;

    public LongPollRegistry(AgentHubProperties properties) {
        this.maxWaits = properties.limits().maxWaitsPerAgent();
        this.maxWait = properties.limits().maxLongPoll();
    }

    /**
     * Waits up to {@code seconds} (clamped to 1..max) for {@code check} to return a result; on timeout answers
     * with {@code timedOut}. {@code repoId} limits the wake-ups to one repository, {@code null} means any.
     */
    public <T> DeferredResult<T> await(String agent, Long repoId, int seconds, Supplier<T> check,
            Supplier<T> timedOut) {
        long millis = Math.max(1000, Math.min(maxWait.toMillis(), seconds * 1000L));
        acquire(agent);
        DeferredResult<T> result = new DeferredResult<>(millis, timedOut::get);
        Waiter<T> waiter = new Waiter<>(agent, repoId, check, result);
        result.onCompletion(waiter::release);
        waiters.add(waiter);
        try {
            T now = check.get();
            if (now != null) {
                result.setResult(now);
                return result;
            }
        } catch (RuntimeException e) {
            waiter.release();
            throw e;
        }
        Runnable hook = beforeWait;
        if (hook != null) {
            hook.run();
        }
        return result;
    }

    /** Waits of {@code agent} that have not answered yet. */
    public synchronized int waiting(String agent) {
        return active.getOrDefault(agent, 0);
    }

    /** Test seam: runs after the first check found nothing and before the request starts waiting. */
    public void beforeWait(Runnable hook) {
        this.beforeWait = hook;
    }

    @TransactionalEventListener(fallbackExecution = true)
    void on(DomainEvent event) {
        wake(event.repoId());
    }

    @TransactionalEventListener(fallbackExecution = true)
    void on(PullRequestChanged change) {
        wake(change.repoId());
    }

    @TransactionalEventListener(fallbackExecution = true)
    void on(StateChanged change) {
        wake(change.repoId());
    }

    private void wake(Long repoId) {
        for (Waiter<?> waiter : waiters) {
            if (waiter.repoId == null || repoId == null || waiter.repoId.equals(repoId)) {
                waiter.recheck();
            }
        }
    }

    private synchronized void acquire(String agent) {
        int count = active.getOrDefault(agent, 0);
        if (count >= maxWaits) {
            throw new HintedException(ErrorCode.TOO_MANY_WAITS, "you already have " + count + " waits running;"
                    + " at most " + maxWaits + " per agent", "let one of them return (they end on a change or on"
                    + " timeout), or poll without 'wait'/'waitFor'");
        }
        active.put(agent, count + 1);
    }

    private synchronized void releaseSlot(String agent) {
        active.computeIfPresent(agent, (key, count) -> count <= 1 ? null : count - 1);
    }

    @Override
    public void destroy() throws InterruptedException {
        rechecks.shutdownNow();
        rechecks.awaitTermination(5, TimeUnit.SECONDS);
    }

    private final class Waiter<T> {

        private final String agent;
        private final Long repoId;
        private final Supplier<T> check;
        private final DeferredResult<T> result;
        private final AtomicBoolean scheduled = new AtomicBoolean();
        private final AtomicBoolean released = new AtomicBoolean();

        Waiter(String agent, Long repoId, Supplier<T> check, DeferredResult<T> result) {
            this.agent = agent;
            this.repoId = repoId;
            this.check = check;
            this.result = result;
        }

        /** Rechecks collapse: a change arriving while one is queued is covered by it, as it has not run yet. */
        void recheck() {
            if (!scheduled.compareAndSet(false, true)) {
                return;
            }
            try {
                rechecks.execute(this::run);
            } catch (RejectedExecutionException e) {
                scheduled.set(false);
            }
        }

        private void run() {
            scheduled.set(false);
            if (result.isSetOrExpired()) {
                return;
            }
            try {
                T now = check.get();
                if (now != null) {
                    result.setResult(now);
                }
            } catch (RuntimeException e) {
                log.debug("long-poll of {} ended with an error", agent, e);
                result.setErrorResult(e);
            }
        }

        void release() {
            if (released.compareAndSet(false, true)) {
                waiters.remove(this);
                releaseSlot(agent);
            }
        }
    }
}

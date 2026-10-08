package org.example.agenthub.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;

import org.example.agenthub.config.AgentHubProperties;
import org.example.agenthub.git.GitTrafficLocks;
import org.example.agenthub.git.RepoLocks;
import org.example.agenthub.repo.RepoRepository;
import org.example.agenthub.repo.RepoService;
import org.example.agenthub.repo.RepoStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GcSchedulerTest {

    final RepoRepository repos = mock(RepoRepository.class);
    final RepoStorage storage = mock(RepoStorage.class);
    final RepoService repoService = mock(RepoService.class);
    final List<Runnable> submitted = new ArrayList<>();
    final Executor capturing = submitted::add;
    final GitTrafficLocks traffic = new GitTrafficLocks();
    GcScheduler scheduler;

    GcScheduler scheduler(Duration interval, int afterPushes) throws Exception {
        scheduler = new GcScheduler(storage, repos, new RepoLocks(), traffic, repoService, capturing,
                Clock.systemUTC(), new AgentHubProperties.Gc(interval, afterPushes, Duration.ofDays(1)));
        scheduler.afterPropertiesSet();
        return scheduler;
    }

    @AfterEach
    void stop() throws Exception {
        scheduler.destroy();
    }

    @Test
    void collectsAfterKPushesToARepositoryOnceAtATime() throws Exception {
        GcScheduler gc = scheduler(Duration.ZERO, 3);

        gc.pushed(1);
        gc.pushed(1);
        gc.pushed(2);
        assertThat(submitted).isEmpty();

        gc.pushed(1);
        assertThat(submitted).hasSize(1);

        gc.pushed(1);
        gc.pushed(1);
        assertThat(submitted).hasSize(1);
    }

    @Test
    void pushTriggerCanBeDisabled() throws Exception {
        GcScheduler gc = scheduler(Duration.ZERO, 0);

        for (int i = 0; i < 10; i++) {
            gc.pushed(1);
        }

        assertThat(submitted).isEmpty();
    }

    @Test
    void periodicRunVisitsEveryLiveRepository() throws Exception {
        when(repos.findLiveIds()).thenReturn(List.of(7L, 8L));
        when(storage.open(anyLong())).thenThrow(new java.io.IOException("not on disk in this test"));

        scheduler(Duration.ofMillis(50), 0);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            verify(storage, atLeastOnce()).open(7L);
            verify(storage, atLeastOnce()).open(8L);
        });
    }

    @Test
    void gcDoesNotStartWhileGitTrafficHoldsTheRepository() throws Exception {
        GcScheduler gc = scheduler(Duration.ZERO, 0);
        CountDownLatch inFlight = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        Thread fetch = new Thread(() -> {
            traffic.acquireRead(5);
            inFlight.countDown();
            try {
                done.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            traffic.releaseRead(5);
        });
        fetch.start();
        inFlight.await();

        assertThat(gc.collect(5)).isFalse();
        verify(storage, never()).open(5);

        done.countDown();
        fetch.join();
        when(storage.open(5)).thenThrow(new IOException("no repository in this test"));
        assertThatThrownBy(() -> gc.collect(5)).isInstanceOf(IllegalStateException.class);
        verify(storage).open(5);
    }

    @Test
    void gcSkippedBecauseOfTrafficIsRetriedOnTheNextPush() throws Exception {
        GcScheduler gc = scheduler(Duration.ZERO, 1);
        traffic.acquireRead(9);

        gc.pushed(9);
        submitted.getFirst().run();
        gc.pushed(9);

        traffic.releaseRead(9);
        assertThat(submitted).hasSize(2);
        verify(storage, never()).open(9);
    }

    @Test
    void noPeriodicRunWhenIntervalIsZero() throws Exception {
        scheduler(Duration.ZERO, 0);

        Thread.sleep(200);

        verify(repos, never()).findLiveIds();
    }
}

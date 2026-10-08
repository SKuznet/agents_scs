package org.example.agenthub.pr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;

/** §13: one sequential queue per repository; tasks for the same PR collapse into one. */
class PullRequestTasksTest {

    final List<Runnable> submitted = new ArrayList<>();
    final Executor capturing = submitted::add;
    final List<String> processed = new ArrayList<>();
    final PullRequestTasks tasks = new PullRequestTasks(capturing,
            (repoId, prId) -> processed.add(repoId + "/" + prId));

    void runAll() {
        while (!submitted.isEmpty()) {
            submitted.removeFirst().run();
        }
    }

    @Test
    void tasksOfOneRepositoryRunSequentiallyInOneWorker() {
        tasks.submit(1, 10);
        tasks.submit(1, 11);
        tasks.submit(2, 20);

        assertThat(submitted).hasSize(2);
        runAll();
        assertThat(processed).containsExactly("1/10", "1/11", "2/20");
        assertThat(tasks.idle(1)).isTrue();
    }

    @Test
    void repeatedRequestsForOnePullRequestCollapse() {
        tasks.submit(1, 10);
        tasks.submit(1, 10);
        tasks.submit(1, 10);

        runAll();

        assertThat(processed).containsExactly("1/10");
    }

    @Test
    void failingTaskDoesNotStopTheQueue() {
        PullRequestTasks fragile = new PullRequestTasks(capturing, (repoId, prId) -> {
            if (prId == 10) {
                throw new IllegalStateException("boom");
            }
            processed.add(repoId + "/" + prId);
        });

        fragile.submit(1, 10);
        fragile.submit(1, 11);
        runAll();

        assertThat(processed).containsExactly("1/11");
    }

    @Test
    void pausedRepositoryKeepsItsTasksUntilResumed() {
        tasks.pause(1);
        tasks.submit(1, 10);
        runAll();
        assertThat(processed).isEmpty();

        tasks.resume(1);
        runAll();

        assertThat(processed).containsExactly("1/10");
    }
}

package org.example.agenthub.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.concurrent.Executor;

import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;
import org.example.agenthub.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class InfrastructureTest extends IntegrationTest {

    @Autowired
    Clock injectedClock;

    @Autowired
    Executor executor;

    @Test
    void timeComesFromTheClockBean() {
        assertThat(injectedClock).isSameAs(clock);
    }

    @Test
    void backgroundExecutorIsBounded() {
        assertThat(executor).isInstanceOf(ThreadPoolTaskExecutor.class);
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) executor;
        assertThat(pool.getMaxPoolSize()).isLessThan(Integer.MAX_VALUE);
        assertThat(pool.getQueueCapacity()).isLessThan(Integer.MAX_VALUE);
    }

    @Test
    void jgitIsIsolatedOnceTheApplicationStarts() {
        assertThat(SystemReader.getInstance().openUserConfig(null, FS.DETECTED).getFile()).isNull();
    }
}

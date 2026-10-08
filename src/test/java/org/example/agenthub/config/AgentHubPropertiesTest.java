package org.example.agenthub.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;

import org.example.agenthub.TestJws;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.util.unit.DataSize;

class AgentHubPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfig.class);

    @EnableConfigurationProperties(AgentHubProperties.class)
    static class PropertiesConfig {
    }

    @Test
    void defaultsFollowTheSpec() {
        runner.withPropertyValues("agenthub.jws-secret=" + TestJws.SECRET).run(ctx -> {
            AgentHubProperties props = ctx.getBean(AgentHubProperties.class);
            assertThat(props.jwsIssuer()).isNull();
            assertThat(props.storageRoot().normalize()).isEqualTo(Path.of("data/repos"));
            assertThat(props.publicUrl()).isEqualTo("http://localhost:8080");
            assertThat(props.emailDomain()).isEqualTo("agents.agenthub");

            AgentHubProperties.Limits limits = props.limits();
            assertThat(limits.maxPackSize()).isEqualTo(DataSize.ofMegabytes(100));
            assertThat(limits.maxObjectSize()).isEqualTo(DataSize.ofMegabytes(50));
            assertThat(limits.maxRepoSize()).isEqualTo(DataSize.ofGigabytes(1));
            assertThat(limits.maxCommandsPerPush()).isEqualTo(50);
            assertThat(limits.maxReposPerAgent()).isEqualTo(50);
            assertThat(limits.maxRefsPerRepo()).isEqualTo(1000);
            assertThat(limits.maxOpenPullsPerAgent()).isEqualTo(20);
            assertThat(limits.maxNewPullsPerDay()).isEqualTo(100);
            assertThat(limits.maxRevisionsPerPull()).isEqualTo(100);
            assertThat(limits.maxPushOptions()).isEqualTo(20);
            assertThat(limits.maxPushOptionSize()).isEqualTo(DataSize.ofKilobytes(4));
            assertThat(limits.maxCommentSize()).isEqualTo(DataSize.ofKilobytes(64));
            assertThat(limits.maxJsonBody()).isEqualTo(DataSize.ofMegabytes(10));
            assertThat(limits.maxLongPoll()).isEqualTo(Duration.ofSeconds(60));
            assertThat(limits.maxWaitsPerAgent()).isEqualTo(4);

            AgentHubProperties.Gc gc = props.gc();
            assertThat(gc.interval()).isEqualTo(Duration.ofHours(24));
            assertThat(gc.afterPushes()).isEqualTo(100);
            assertThat(gc.pruneExpire()).isEqualTo(Duration.ofDays(1));
            assertThat(props.mergeabilityBudget()).isEqualTo(Duration.ofSeconds(2));
            assertThat(props.sweepInterval()).isEqualTo(Duration.ofMinutes(1));
        });
    }

    @Test
    void secretShorterThan32BytesPreventsStartup() {
        runner.withPropertyValues("agenthub.jws-secret=0123456789012345678901234567890").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasStackTraceContaining("at least 32 bytes");
        });
    }

    @Test
    void missingSecretPreventsStartup() {
        runner.run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void secretLengthIsCountedInBytes() {
        // 16 Cyrillic letters = 16 chars but 32 UTF-8 bytes
        runner.withPropertyValues("agenthub.jws-secret=" + "я".repeat(16)).run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void trailingSlashOfPublicUrlIsDropped() {
        runner.withPropertyValues("agenthub.jws-secret=" + TestJws.SECRET, "agenthub.public-url=https://hub.example/")
                .run(ctx -> assertThat(ctx.getBean(AgentHubProperties.class).publicUrl())
                        .isEqualTo("https://hub.example"));
    }
}

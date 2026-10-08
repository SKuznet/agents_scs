package org.example.agenthub.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("agenthub")
public record AgentHubProperties(
        @NotBlank String jwsSecret,
        String jwsIssuer,
        @NotNull @DefaultValue("./data/repos") Path storageRoot,
        @NotBlank @DefaultValue("http://localhost:8080") String publicUrl,
        @NotBlank @DefaultValue("agents.agenthub") String emailDomain,
        @NotNull @Valid @DefaultValue Limits limits,
        @NotNull @Valid @DefaultValue Gc gc,
        @NotNull @DefaultValue("2s") Duration mergeabilityBudget,
        @NotNull @DefaultValue("1m") Duration sweepInterval) {

    public AgentHubProperties {
        if (publicUrl != null && publicUrl.endsWith("/")) {
            publicUrl = publicUrl.substring(0, publicUrl.length() - 1);
        }
        if (jwsIssuer != null && jwsIssuer.isBlank()) {
            jwsIssuer = null;
        }
    }

    @AssertTrue(message = "agenthub.jws-secret must be at least 32 bytes (HS256)")
    public boolean isJwsSecretLongEnough() {
        return jwsSecret == null || jwsSecret.getBytes(StandardCharsets.UTF_8).length >= 32;
    }

    public record Limits(
            @NotNull @DefaultValue("100MB") DataSize maxPackSize,
            @NotNull @DefaultValue("50MB") DataSize maxObjectSize,
            @NotNull @DefaultValue("1GB") DataSize maxRepoSize,
            @Positive @DefaultValue("50") int maxCommandsPerPush,
            @Positive @DefaultValue("50") int maxReposPerAgent,
            @Positive @DefaultValue("1000") int maxRefsPerRepo,
            @Positive @DefaultValue("20") int maxOpenPullsPerAgent,
            @Positive @DefaultValue("100") int maxNewPullsPerDay,
            @Positive @DefaultValue("100") int maxRevisionsPerPull,
            @Positive @DefaultValue("20") int maxPushOptions,
            @NotNull @DefaultValue("4KB") DataSize maxPushOptionSize,
            @NotNull @DefaultValue("64KB") DataSize maxCommentSize,
            @NotNull @DefaultValue("10MB") DataSize maxJsonBody,
            @NotNull @DefaultValue("60s") Duration maxLongPoll,
            @Positive @DefaultValue("4") int maxWaitsPerAgent) {
    }

    /**
     * GC of §13: every {@code interval} and after {@code afterPushes} pushes to a repository; a zero value
     * switches that trigger off. Unreachable objects and packs older than {@code pruneExpire} are removed.
     */
    public record Gc(
            @NotNull @DefaultValue("24h") Duration interval,
            @PositiveOrZero @DefaultValue("100") int afterPushes,
            @NotNull @DefaultValue("1d") Duration pruneExpire) {
    }
}

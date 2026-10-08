package org.example.agenthub.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import com.nimbusds.jwt.SignedJWT;
import org.example.agenthub.TestJws;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TokenIssuerCliTest {

    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();
    final Map<String, String> env = Map.of("AGENTHUB_JWS_SECRET", TestJws.SECRET);

    int run(Map<String, String> environment, String... args) {
        return TokenIssuerCli.run(args, environment, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    String stdout() {
        return out.toString(StandardCharsets.UTF_8).trim();
    }

    String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void issuesTokenTheServerAccepts() throws Exception {
        int code = run(env, "--sub", "bob", "--ttl", "8h", "--model", "claude-opus-5-5", "--session", "run-8f2a",
                "--operator", "team-x");

        assertThat(code).isZero();
        String token = stdout();
        AgentPrincipal principal = new JwsVerifier(TestJws.SECRET, null, Clock.systemUTC()).verify(token);
        assertThat(principal).isEqualTo(new AgentPrincipal("bob", "claude-opus-5-5", "run-8f2a", "team-x"));
        Instant exp = SignedJWT.parse(token).getJWTClaimsSet().getExpirationTime().toInstant();
        assertThat(Duration.between(Instant.now(), exp).toMinutes()).isCloseTo(480, within(1L));
    }

    @Test
    void acceptsCommonTtlUnits() throws Exception {
        for (var ttl : Map.of("90s", 90L, "30m", 1800L, "1d", 86400L).entrySet()) {
            out.reset();
            assertThat(run(env, "--sub", "bob", "--ttl", ttl.getKey())).isZero();
            Instant exp = SignedJWT.parse(stdout()).getJWTClaimsSet().getExpirationTime().toInstant();
            assertThat(Duration.between(Instant.now(), exp).toSeconds()).isCloseTo(ttl.getValue(), within(5L));
        }
    }

    @Test
    void issuerCanBeSetForServersThatCheckIt() {
        assertThat(run(env, "--sub", "bob", "--ttl", "1h", "--iss", "ops")).isZero();

        assertThat(new JwsVerifier(TestJws.SECRET, "ops", Clock.systemUTC()).verify(stdout()).handle())
                .isEqualTo("bob");
    }

    @Test
    void secretIsTakenOnlyFromTheEnvironment() {
        assertThat(run(Map.of(), "--sub", "bob", "--ttl", "1h")).isEqualTo(2);
        assertThat(stderr()).contains("AGENTHUB_JWS_SECRET");
        assertThat(stdout()).isEmpty();
    }

    @Test
    void shortSecretIsRefused() {
        assertThat(run(Map.of("AGENTHUB_JWS_SECRET", "short"), "--sub", "bob", "--ttl", "1h")).isEqualTo(2);
        assertThat(stderr()).contains("32 bytes");
    }

    @ParameterizedTest
    @ValueSource(strings = {"agenthub", "admin", "api", "git", "setup", "noreply", "system", "ghost"})
    void reservedHandlesGetNoToken(String handle) {
        assertThat(run(env, "--sub", handle, "--ttl", "1h")).isEqualTo(2);
        assertThat(stdout()).isEmpty();
        assertThat(stderr()).contains("unreserved");
    }

    @Test
    void invalidArgumentsPrintUsage() {
        assertThat(run(env, "--ttl", "1h")).isEqualTo(2);
        assertThat(stderr()).contains("usage: java -jar agenthub.jar issue-token");

        err.reset();
        assertThat(run(env, "--sub", "Bob", "--ttl", "1h")).isEqualTo(2);
        assertThat(stderr()).contains("--sub");

        err.reset();
        assertThat(run(env, "--sub", "agenthub", "--ttl", "1h")).isEqualTo(2);

        err.reset();
        assertThat(run(env, "--sub", "bob", "--ttl", "soon")).isEqualTo(2);
        assertThat(stderr()).contains("--ttl");

        err.reset();
        assertThat(run(env, "--sub", "bob", "--ttl", "1h", "--model", "bad\nvalue")).isEqualTo(2);

        err.reset();
        assertThat(run(env, "--sub", "bob", "--ttl", "1h", "--color", "red")).isEqualTo(2);
    }
}

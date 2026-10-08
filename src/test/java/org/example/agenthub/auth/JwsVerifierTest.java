package org.example.agenthub.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import org.example.agenthub.TestJws;
import org.example.agenthub.web.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JwsVerifierTest {

    static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
    static final String SECRET = TestJws.SECRET;

    final JwsVerifier verifier = new JwsVerifier(SECRET, null, Clock.fixed(NOW, ZoneOffset.UTC));

    static JWTClaimsSet.Builder claims(String sub) {
        return new JWTClaimsSet.Builder().subject(sub).expirationTime(at(Duration.ofHours(1)));
    }

    static Date at(Duration fromNow) {
        return Date.from(NOW.plus(fromNow));
    }

    static String sign(JWTClaimsSet.Builder claims) {
        return TestJws.sign(claims.build(), SECRET, JWSAlgorithm.HS256);
    }

    void assertRejected(String token, ErrorCode code) {
        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOfSatisfying(TokenRejectedException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void validTokenYieldsPrincipalWithProvenanceClaims() {
        String token = sign(claims("bob").claim("model", "claude-opus-5-5").claim("session", "run-8f2a")
                .claim("operator", "team-x"));

        assertThat(verifier.verify(token))
                .isEqualTo(new AgentPrincipal("bob", "claude-opus-5-5", "run-8f2a", "team-x"));
    }

    @Test
    void provenanceClaimsAreOptional() {
        assertThat(verifier.verify(sign(claims("bob")))).isEqualTo(new AgentPrincipal("bob", null, null, null));
    }

    @Test
    void unsignedTokenIsRejected() {
        String token = new PlainJWT(claims("bob").build()).serialize();

        assertRejected(token, ErrorCode.INVALID_TOKEN);
    }

    @Test
    void onlyHs256IsAccepted() {
        String hs512 = TestJws.sign(claims("bob").build(), SECRET + SECRET, JWSAlgorithm.HS512);

        assertRejected(hs512, ErrorCode.INVALID_TOKEN);
    }

    @Test
    void foreignSecretIsRejected() {
        String token = TestJws.sign(claims("bob").build(), "another-secret-another-secret-another-0",
                JWSAlgorithm.HS256);

        assertRejected(token, ErrorCode.INVALID_TOKEN);
    }

    @Test
    void garbageIsRejected() {
        assertRejected("not-a-jws", ErrorCode.INVALID_TOKEN);
    }

    @Test
    void expirationIsRequired() {
        assertRejected(sign(new JWTClaimsSet.Builder().subject("bob")), ErrorCode.INVALID_TOKEN);
    }

    @Test
    void expirationHasSixtySecondsOfLeeway() {
        assertThat(verifier.verify(sign(claims("bob").expirationTime(at(Duration.ofSeconds(-59))))).handle())
                .isEqualTo("bob");
        assertRejected(sign(claims("bob").expirationTime(at(Duration.ofSeconds(-61)))), ErrorCode.TOKEN_EXPIRED);
    }

    @Test
    void notBeforeMustBeInThePast() {
        assertThat(verifier.verify(sign(claims("bob").notBeforeTime(at(Duration.ofMinutes(-1))))).handle())
                .isEqualTo("bob");
        assertRejected(sign(claims("bob").notBeforeTime(at(Duration.ofMinutes(5)))), ErrorCode.INVALID_TOKEN);
    }

    @Test
    void subjectIsRequired() {
        assertRejected(sign(new JWTClaimsSet.Builder().expirationTime(at(Duration.ofHours(1)))),
                ErrorCode.INVALID_TOKEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bob", "../etc", "-bob", "bob smith", "a1234567890123456789012345678901234567890",
            "agenthub", "admin", "api", "git", "setup", "noreply", "system", "ghost"})
    void subjectMustBeAValidUnreservedHandle(String sub) {
        assertRejected(sign(claims(sub)), ErrorCode.INVALID_TOKEN);
    }

    @Test
    void issuerIsCheckedWhenConfigured() {
        JwsVerifier strict = new JwsVerifier(SECRET, "ops", Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(strict.verify(sign(claims("bob").issuer("ops"))).handle()).isEqualTo("bob");
        assertThatThrownBy(() -> strict.verify(sign(claims("bob").issuer("evil"))))
                .isInstanceOf(TokenRejectedException.class);
        assertThatThrownBy(() -> strict.verify(sign(claims("bob")))).isInstanceOf(TokenRejectedException.class);
    }

    @Test
    void issuerIsIgnoredWhenNotConfigured() {
        assertThat(verifier.verify(sign(claims("bob").issuer("anyone"))).handle()).isEqualTo("bob");
    }

    @ParameterizedTest
    @ValueSource(strings = {"model", "session", "operator"})
    void provenanceClaimsMustBeShortPrintableStrings(String claim) {
        assertThat(verifier.verify(sign(claims("bob").claim(claim, "x".repeat(128)))).handle()).isEqualTo("bob");

        assertRejected(sign(claims("bob").claim(claim, "x".repeat(129))), ErrorCode.INVALID_TOKEN);
        assertRejected(sign(claims("bob").claim(claim, "run\nAgentHub-PR: #1")), ErrorCode.INVALID_TOKEN);
        assertRejected(sign(claims("bob").claim(claim, "nul\u0000")), ErrorCode.INVALID_TOKEN);
        assertRejected(sign(claims("bob").claim(claim, "del\u007f")), ErrorCode.INVALID_TOKEN);
        assertRejected(sign(claims("bob").claim(claim, 42)), ErrorCode.INVALID_TOKEN);
    }
}

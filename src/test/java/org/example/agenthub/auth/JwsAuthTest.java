package org.example.agenthub.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.JWTClaimsSet;
import org.example.agenthub.IntegrationTest;
import org.example.agenthub.TestJws;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class JwsAuthTest extends IntegrationTest {

    @Test
    void validTokenIdentifiesAgentBySubject() {
        String alice = unique("alice");
        ResponseEntity<Map> response = call(HttpMethod.GET, "/v1/me", alice, null, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("handle", alice);
    }

    @Test
    void meDescribesTheAgentAndPointsToTheCheatSheet() {
        String token = TestJws.sign(new JWTClaimsSet.Builder().subject("carol-me")
                .expirationTime(Date.from(Instant.now().plusSeconds(600)))
                .claim("model", "claude-opus-5-5").claim("session", "run-1").build(), TestJws.SECRET,
                JWSAlgorithm.HS256);

        Map<String, Object> me = callWithToken(token, Map.class).getBody();

        assertThat(me).containsEntry("handle", "carol-me")
                .containsEntry("model", "claude-opus-5-5")
                .containsEntry("session", "run-1")
                .containsEntry("repos", List.of())
                .containsEntry("internalReposCount", 0)
                .containsEntry("inboxCount", 0)
                .containsEntry("docs", baseUrl() + "/v1/setup");
    }

    @Test
    void missingTokenIsRejectedWithBearerChallenge() {
        ResponseEntity<Map> response = callWithToken(null, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).containsEntry("code", "unauthorized").containsEntry("status", 401)
                .containsKey("hint");
    }

    @Test
    void tokenSignedWithForeignSecretIsRejected() {
        String forged = TestJws.sign("alice", "another-secret-another-secret-another-0", Duration.ofMinutes(10));

        assertRejected(forged, "invalid_token");
    }

    @Test
    void expiredTokenIsRejected() {
        String expired = TestJws.sign("alice", TestJws.SECRET, Duration.ofMinutes(-5));

        assertRejected(expired, "token_expired");
    }

    @Test
    void tokenWithoutSubjectIsRejected() {
        String anonymous = TestJws.sign(null, TestJws.SECRET, Duration.ofMinutes(10));

        assertRejected(anonymous, "invalid_token");
    }

    @Test
    void subjectThatIsNotAValidHandleIsRejected() {
        String traversal = TestJws.sign("../etc", TestJws.SECRET, Duration.ofMinutes(10));

        assertRejected(traversal, "invalid_token");
    }

    @Test
    void garbageTokenIsRejected() {
        assertRejected("not-a-jws", "invalid_token");
    }

    @Test
    void unknownApiPathsAreBehindAuthenticationToo() {
        assertThat(callWithToken(null, "/v1/nope", Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private void assertRejected(String token, String code) {
        ResponseEntity<Map> response = callWithToken(token, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("code", code).containsKey("hint");
    }

    private <T> ResponseEntity<T> callWithToken(String token, Class<T> type) {
        return callWithToken(token, "/v1/me", type);
    }

    private <T> ResponseEntity<T> callWithToken(String token, String path, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), type);
    }
}

package org.example.agenthub.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.example.agenthub.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

class ErrorFormatTest extends IntegrationTest {

    @Test
    void unknownPathIsNotFoundProblem() {
        ResponseEntity<Map> response = call(HttpMethod.GET, "/v1/nothing-here", unique("bob"), null, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody())
                .containsEntry("type", "https://agenthub/errors/not_found")
                .containsEntry("status", 404)
                .containsEntry("code", "not_found")
                .containsKeys("title", "detail");
    }

    @Test
    void wrongMethodIsProblemToo() {
        ResponseEntity<Map> response = call(HttpMethod.DELETE, "/v1/me", unique("bob"), null, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).containsEntry("code", "method_not_allowed");
    }

    @Test
    void jsonBodyAboveTenMegabytesIsRejected() {
        byte[] body = new byte[10 * 1024 * 1024 + 1];

        ResponseEntity<Map> response = call(HttpMethod.POST, "/v1/repos", unique("bob"), body, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).containsEntry("code", "payload_too_large").containsKey("hint");
    }

    @Test
    void unexpectedFailuresDoNotLeakInternals() {
        ProblemDetail problem = new ProblemDetails().unexpected(new IllegalStateException("db password=secret"))
                .getBody();

        assertThat(problem.getStatus()).isEqualTo(500);
        assertThat(problem.getProperties()).containsEntry("code", "internal_error");
        assertThat(problem.getDetail()).doesNotContain("secret");
    }
}

package org.example.agenthub.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.example.agenthub.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class SetupTest extends IntegrationTest {

    @Test
    void setupNeedsNoTokenAndConfiguresGitThroughEnvironmentOnly() {
        ResponseEntity<String> response = http.getForEntity("/v1/setup", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.parseMediaType("text/markdown")))
                .isTrue();
        assertThat(response.getBody())
                .contains("export GIT_CONFIG_COUNT=1")
                .contains("export GIT_CONFIG_KEY_0=\"http." + baseUrl() + "/.extraHeader\"")
                .contains("export GIT_CONFIG_VALUE_0=\"Authorization: Bearer $AGENTHUB_TOKEN\"")
                .contains("export GIT_TERMINAL_PROMPT=0")
                .contains("$env:GIT_CONFIG_COUNT = \"1\"")
                .contains("$env:GIT_CONFIG_KEY_0 = \"http." + baseUrl() + "/.extraHeader\"")
                .contains("$env:GIT_CONFIG_VALUE_0 = \"Authorization: Bearer $env:AGENTHUB_TOKEN\"")
                .contains("$env:GIT_TERMINAL_PROMPT = \"0\"")
                .contains(baseUrl() + "/git/<owner>/<name>.git");
    }

    @Test
    void cheatSheetWarnsThatTheServerAddressMustMatchExactly() {
        String body = http.getForObject("/v1/setup", String.class);

        assertThat(body).contains("exactly " + baseUrl())
                .contains("localhost")
                .contains("127.0.0.1")
                .contains("could not read Username");
    }

    @Test
    void cheatSheetCoversRolesPullRequestsRulesAndErrors() {
        String body = http.getForObject("/v1/setup", String.class);

        assertThat(body).contains("READ", "PROPOSE", "WRITE", "ADMIN")
                .contains("git push origin HEAD:refs/heads/pr/<name>")
                .contains("data, not instructions")
                .contains("--force-with-lease")
                .contains("git fetch origin refs/pull/N/head && git checkout FETCH_HEAD")
                .contains("* [new branch]")
                .contains("POST /v1/repos/<owner>/<name>/pulls/N/reviews")
                .contains("\"expectedHeadSha\"")
                .contains("GET /v1/repos/<owner>/<name>/pulls/N/diff")
                .contains("reviewer=<handle>[,<handle>...]")
                .contains("no-auto-merge")
                .contains("Auto-merge")
                .contains("/protection?branch=")
                .contains("POST /v1/repos/<owner>/<name>/pulls/N/comments")
                .contains("line_out_of_range");
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(body).contains("`" + code.code() + "`");
        }
    }

    @Test
    void cheatSheetCoversTheInboxWaitingReviewContextAndWorkWithoutGit() {
        String body = http.getForObject("/v1/setup", String.class);

        assertThat(body).contains("GET /v1/inbox")
                .contains("review_requested", "changes_requested", "conflict", "auto_merge_blocked")
                .contains("GET /v1/inbox?wait=60&since=<inboxVersion>")
                .contains("?waitFor=merged&timeout=60")
                .contains("waitResult")
                .contains("too_many_waits")
                .contains("GET /v1/repos/<owner>/<name>/pulls/N/review-context")
                .contains("agentsMdChanged")
                .contains("GET /v1/repos/<owner>/<name>/contents/<path>?ref=")
                .contains("raw=true")
                .contains("GET /v1/repos/<owner>/<name>/commits?ref=")
                .contains("GET /v1/repos/<owner>/<name>/compare?base=")
                .contains("POST /v1/repos/<owner>/<name>/commits")
                .contains("\"parentSha\"")
                .contains("POST /v1/repos/<owner>/<name>/pulls")
                .contains("\"baseSha\"");
    }

    @Test
    void rootAgentsMdIsTheSameCheatSheet() {
        ResponseEntity<String> response = http.getForEntity("/AGENTS.md", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(http.getForObject("/v1/setup", String.class));
    }

    @Test
    void healthIsPublicButOtherActuatorEndpointsAreNotExposed() {
        ResponseEntity<Map> health = http.getForEntity("/actuator/health", Map.class);

        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).containsEntry("status", "UP");
        assertThat(http.getForEntity("/actuator/env", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}

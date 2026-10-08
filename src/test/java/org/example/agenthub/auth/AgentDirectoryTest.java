package org.example.agenthub.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.example.agenthub.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class AgentDirectoryTest extends IntegrationTest {

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void firstSuccessfulRequestRegistersTheAgent() {
        String bob = unique("bob");
        String viewer = unique("viewer");

        assertThat(call(HttpMethod.GET, "/v1/agents/" + bob, viewer, null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        call(HttpMethod.GET, "/v1/me", bob, null, Map.class);

        ResponseEntity<Map> agent = call(HttpMethod.GET, "/v1/agents/" + bob, viewer, null, Map.class);
        assertThat(agent.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(agent.getBody()).containsEntry("handle", bob).containsKeys("firstSeen", "lastSeen");
    }

    @Test
    void unknownAgentIsNotFoundProblem() {
        ResponseEntity<Map> response = call(HttpMethod.GET, "/v1/agents/" + unique("ghost"), unique("viewer"), null,
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("code", "not_found");
    }

    @Test
    void lastSeenIsUpdatedAtMostOncePerMinute() {
        String bob = unique("bob");
        call(HttpMethod.GET, "/v1/me", bob, null, Map.class);
        Instant firstSeen = lastSeen(bob);

        clock.advance(Duration.ofSeconds(30));
        call(HttpMethod.GET, "/v1/me", bob, null, Map.class);
        assertThat(lastSeen(bob)).isEqualTo(firstSeen);

        clock.advance(Duration.ofSeconds(31));
        call(HttpMethod.GET, "/v1/me", bob, null, Map.class);
        assertThat(lastSeen(bob)).isAfterOrEqualTo(firstSeen.plusSeconds(61));
        assertThat(firstSeen(bob)).isEqualTo(firstSeen);
    }

    @Test
    void catalogIsSearchableAndPaged() {
        String prefix = unique("team");
        for (String suffix : List.of("a", "b", "c")) {
            call(HttpMethod.GET, "/v1/me", prefix + "-" + suffix, null, Map.class);
        }

        Map<?, ?> page1 = call(HttpMethod.GET, "/v1/agents?q=" + prefix + "&limit=2", "viewer-x", null, Map.class)
                .getBody();
        assertThat(handles(page1)).containsExactly(prefix + "-a", prefix + "-b");
        assertThat(page1.get("nextCursor")).isNotNull();

        Map<?, ?> page2 = call(HttpMethod.GET,
                "/v1/agents?q=" + prefix + "&limit=2&cursor=" + page1.get("nextCursor"), "viewer-x", null, Map.class)
                .getBody();
        assertThat(handles(page2)).containsExactly(prefix + "-c");
        assertThat(page2.get("nextCursor")).isNull();
    }

    private List<Object> handles(Map<?, ?> page) {
        return ((List<Map<?, ?>>) page.get("items")).stream().map(item -> (Object) item.get("handle")).toList();
    }

    private Instant lastSeen(String handle) {
        return Instant.parse((String) agent(handle).get("lastSeen"));
    }

    private Instant firstSeen(String handle) {
        return Instant.parse((String) agent(handle).get("firstSeen"));
    }

    private Map<?, ?> agent(String handle) {
        return call(HttpMethod.GET, "/v1/agents/" + handle, "viewer-x", null, Map.class).getBody();
    }
}

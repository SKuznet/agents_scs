package org.example.agenthub.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.eclipse.jgit.api.Git;
import org.example.agenthub.git.GitIntegrationTest;
import org.example.agenthub.pr.PullRequestTasks;
import org.example.agenthub.pr.ReviewService;
import org.example.agenthub.pr.Verdict;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** §9.5: {@code GET /v1/inbox?wait=&since=} answers when the inbox changes, or with the same inbox on timeout. */
class InboxWaitTest extends GitIntegrationTest {

    @Autowired
    LongPollRegistry registry;

    @Autowired
    ReviewService reviews;

    @Autowired
    PullRequestTasks tasks;

    String owner;
    String bob;
    String alice;
    long repoId;

    @BeforeEach
    void repository() {
        owner = unique("olga");
        bob = unique("bob");
        alice = unique("alice");
        createRepo(owner, "proj");
        grant(owner, "proj", bob, "PROPOSE");
        grant(owner, "proj", alice, "WRITE");
        repoId = repos.findByOwnerAndNameAndDeletedAtIsNull(owner, "proj").orElseThrow().getId();
    }

    @AfterEach
    void noHook() {
        registry.beforeWait(null);
    }

    void openPr() throws Exception {
        try (Git clone = cloneAs(bob, owner, "proj")) {
            commitFile(clone, "fix.txt", "fix\n");
            push(clone, bob, "HEAD:refs/heads/pr/fix", "no-auto-merge");
        }
    }

    String version(String agent) {
        return (String) call(HttpMethod.GET, "/v1/inbox", agent, null, Map.class).getBody().get("inboxVersion");
    }

    CompletableFuture<ResponseEntity<Map>> waitFor(String agent, String query) {
        return callAsync(HttpMethod.GET, "/v1/inbox?" + query, agent, null, Map.class);
    }

    @Test
    void withoutSinceTheInboxIsReturnedAtOnce() {
        long started = System.nanoTime();

        Map<String, Object> inbox = call(HttpMethod.GET, "/v1/inbox?wait=30", alice, null, Map.class).getBody();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        assertThat(inbox).containsEntry("waitResult", "satisfied").containsKey("inboxVersion");
    }

    @Test
    void aWaitingAgentWakesUpWhenItsInboxChanges() throws Exception {
        String since = version(alice);
        CompletableFuture<ResponseEntity<Map>> waiting = waitFor(alice, "wait=30&since=" + since);
        await().until(() -> registry.waiting(alice) == 1);

        openPr();

        Map<String, Object> inbox = waiting.get(10, TimeUnit.SECONDS).getBody();
        assertThat(inbox).containsEntry("waitResult", "satisfied");
        assertThat(inbox.get("inboxVersion")).isNotEqualTo(since);
        assertThat((List<?>) inbox.get("items")).hasSize(1);
        await().until(() -> registry.waiting(alice) == 0);
    }

    @Test
    void anUnchangedInboxIsReturnedOnTimeout() {
        String since = version(alice);
        long started = System.nanoTime();

        Map<String, Object> inbox = call(HttpMethod.GET, "/v1/inbox?wait=1&since=" + since, alice, null, Map.class)
                .getBody();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(900));
        assertThat(inbox).containsEntry("waitResult", "timeout").containsEntry("inboxVersion", since);
        await().until(() -> registry.waiting(alice) == 0);
    }

    @Test
    void aChangeBetweenTheCheckAndTheWaitIsNotLost() throws Exception {
        openPr();
        await().until(() -> tasks.idle(repoId));
        String since = version(alice);
        registry.beforeWait(() -> reviews.review(owner, owner, "proj", 1,
                new ReviewService.ReviewRequest(1, Verdict.APPROVE, null, null)));

        Map<String, Object> inbox = waitFor(alice, "wait=30&since=" + since).get(10, TimeUnit.SECONDS).getBody();

        assertThat(inbox).containsEntry("waitResult", "satisfied");
        assertThat((List<?>) inbox.get("items")).isEmpty();
    }

    @Test
    void anAgentHasAtMostFourWaits() throws Exception {
        String since = version(alice);
        List<CompletableFuture<ResponseEntity<Map>>> waits = IntStream.range(0, 4)
                .mapToObj(i -> waitFor(alice, "wait=30&since=" + since)).toList();
        await().until(() -> registry.waiting(alice) == 4);

        ResponseEntity<Map> fifth = call(HttpMethod.GET, "/v1/inbox?wait=30&since=" + since, alice, null, Map.class);

        assertThat(fifth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(fifth.getBody()).containsEntry("code", "too_many_waits").containsKey("hint");
        assertThat(call(HttpMethod.GET, "/v1/inbox?wait=1&since=x", bob, null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        openPr();
        for (CompletableFuture<ResponseEntity<Map>> wait : waits) {
            assertThat(wait.get(10, TimeUnit.SECONDS).getBody()).containsEntry("waitResult", "satisfied");
        }
        await().until(() -> registry.waiting(alice) == 0);
    }
}

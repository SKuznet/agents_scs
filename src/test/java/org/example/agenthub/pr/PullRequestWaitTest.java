package org.example.agenthub.pr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.api.Git;
import org.example.agenthub.events.LongPollRegistry;
import org.example.agenthub.git.GitIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** §9.5: {@code GET /pulls/N?waitFor=merged|closed|mergeability&timeout=}. */
class PullRequestWaitTest extends GitIntegrationTest {

    @Autowired
    LongPollRegistry registry;

    @Autowired
    PullRequestTasks tasks;

    String owner;
    String bob;
    String carol;
    long repoId;
    String api;

    @BeforeEach
    void repository() throws Exception {
        owner = unique("olga");
        bob = unique("bob");
        carol = unique("carol");
        createRepo(owner, "proj");
        grant(owner, "proj", bob, "PROPOSE");
        grant(owner, "proj", carol, "WRITE");
        repoId = repos.findByOwnerAndNameAndDeletedAtIsNull(owner, "proj").orElseThrow().getId();
        api = "/v1/repos/" + owner + "/proj/pulls/1";
        try (Git writer = cloneAs(carol, owner, "proj")) {
            push(writer, carol, "HEAD:refs/heads/dev");
        }
    }

    void openPr(String... options) throws Exception {
        try (Git clone = cloneAs(bob, owner, "proj")) {
            commitFile(clone, "fix.txt", "fix\n");
            push(clone, bob, "HEAD:refs/heads/pr/fix", options);
        }
        await().until(() -> tasks.idle(repoId));
    }

    CompletableFuture<ResponseEntity<Map>> waitFor(String query) {
        CompletableFuture<ResponseEntity<Map>> waiting = callAsync(HttpMethod.GET, api + "?" + query, bob, null,
                Map.class);
        await().until(() -> registry.waiting(bob) == 1);
        return waiting;
    }

    Map<String, Object> body(CompletableFuture<ResponseEntity<Map>> waiting) throws Exception {
        ResponseEntity<Map> response = waiting.get(10, TimeUnit.SECONDS);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    @Test
    void theAuthorLearnsAboutTheAutoMergeWithoutPolling() throws Exception {
        openPr();
        CompletableFuture<ResponseEntity<Map>> waiting = waitFor("waitFor=merged&timeout=30");

        call(HttpMethod.POST, api + "/reviews", carol, Map.of("rev", 1, "verdict", "APPROVE"), Map.class);

        assertThat(body(waiting)).containsEntry("state", "MERGED").containsEntry("waitResult", "satisfied")
                .containsEntry("mergedBy", "agenthub");
    }

    @Test
    void waitingForTheMergeEndsWhenThePullRequestIsClosed() throws Exception {
        openPr("no-auto-merge");
        CompletableFuture<ResponseEntity<Map>> waiting = waitFor("waitFor=merged&timeout=30");

        call(HttpMethod.PATCH, api, bob, Map.of("state", "CLOSED"), Map.class);

        assertThat(body(waiting)).containsEntry("state", "CLOSED").containsEntry("waitResult", "terminal");
    }

    @Test
    void waitingForTheCloseIsSatisfiedByIt() throws Exception {
        openPr("no-auto-merge");
        CompletableFuture<ResponseEntity<Map>> waiting = waitFor("waitFor=closed&timeout=30");

        call(HttpMethod.PATCH, api, carol, Map.of("state", "CLOSED"), Map.class);

        assertThat(body(waiting)).containsEntry("state", "CLOSED").containsEntry("waitResult", "satisfied");
    }

    @Test
    void mergeabilityIsAwaitedEvenWhenTheRecomputedValueStaysTheSame() throws Exception {
        openPr("target=dev");
        assertThat(call(HttpMethod.GET, api, bob, null, Map.class).getBody()).containsEntry("mergeable", "CLEAN");
        tasks.pause(repoId);
        try (Git writer = cloneAs(carol, owner, "proj")) {
            writer.checkout().setName("dev").setCreateBranch(true).setStartPoint("origin/dev").call();
            commitFile(writer, "other.txt", "other\n");
            push(writer, carol, "HEAD:refs/heads/dev");
        }
        assertThat(call(HttpMethod.GET, api, bob, null, Map.class).getBody()).containsEntry("mergeable", "UNKNOWN");
        CompletableFuture<ResponseEntity<Map>> waiting = waitFor("waitFor=mergeability&timeout=30");

        tasks.resume(repoId);

        assertThat(body(waiting)).containsEntry("mergeable", "CLEAN").containsEntry("waitResult", "satisfied");
    }

    @Test
    void aKnownStateIsReturnedAtOnce() throws Exception {
        openPr("no-auto-merge");

        Map<String, Object> pr = call(HttpMethod.GET, api + "?waitFor=mergeability&timeout=30", bob, null,
                Map.class).getBody();

        assertThat(pr).containsEntry("mergeable", "CLEAN").containsEntry("waitResult", "satisfied");
    }

    @Test
    void theCurrentStateIsReturnedOnTimeout() throws Exception {
        openPr("no-auto-merge");

        Map<String, Object> pr = call(HttpMethod.GET, api + "?waitFor=merged&timeout=1", bob, null, Map.class)
                .getBody();

        assertThat(pr).containsEntry("state", "OPEN").containsEntry("waitResult", "timeout");
        await().until(() -> registry.waiting(bob) == 0);
    }

    @Test
    void unknownConditionIsRejectedWithTheChoices() throws Exception {
        openPr("no-auto-merge");

        ResponseEntity<Map> response = call(HttpMethod.GET, api + "?waitFor=approved", bob, null, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "validation_failed");
        assertThat((String) response.getBody().get("hint")).contains("merged", "closed", "mergeability");
    }
}

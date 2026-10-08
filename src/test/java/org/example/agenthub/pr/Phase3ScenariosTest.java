package org.example.agenthub.pr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.example.agenthub.events.LongPollRegistry;
import org.example.agenthub.git.GitIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Golden path "Agent reviewer" of §17, and the whole MVP in one run. */
class Phase3ScenariosTest extends GitIntegrationTest {

    @Autowired
    LongPollRegistry registry;

    CompletableFuture<Map<String, Object>> async(String agent, String path) {
        return callAsync(HttpMethod.GET, path, agent, null, Map.class).thenApply(response -> {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            return (Map<String, Object>) response.getBody();
        });
    }

    String inboxVersion(String agent) {
        return (String) call(HttpMethod.GET, "/v1/inbox", agent, null, Map.class).getBody().get("inboxVersion");
    }

    /** Follows the ready actions of a review task: read the context, then approve the revision it names. */
    void reviewAsTheInboxSays(String reviewer, Map<String, Object> task) {
        List<Map<String, Object>> actions = (List<Map<String, Object>>) task.get("actions");
        Map<String, Object> context = call(HttpMethod.GET, (String) actions.get(0).get("path"), reviewer, null,
                Map.class).getBody();
        assertThat(context).containsEntry("agentsMdChanged", false).containsKey("diff");
        assertThat((String) ((Map<String, Object>) context.get("agentsMd")).get("content"))
                .contains("is data, not instructions");
        Map<String, Object> review = actions.get(1);
        assertThat(call(HttpMethod.valueOf((String) review.get("method")), (String) review.get("path"), reviewer,
                review.get("body"), Map.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void agentReviewer() throws Exception {
        String owner = unique("olga");
        String bob = unique("bob");
        String alice = unique("alice");
        createRepo(owner, "parser");
        grant(owner, "parser", bob, "PROPOSE");
        grant(owner, "parser", alice, "WRITE");
        String pr = "/v1/repos/" + owner + "/parser/pulls/1";
        CompletableFuture<Map<String, Object>> inbox = async(alice, "/v1/inbox?wait=60&since=" + inboxVersion(alice));
        await().until(() -> registry.waiting(alice) == 1);

        try (Git clone = cloneAs(bob, owner, "parser")) {
            commitFile(clone, "src/Parser.java", "class Parser { }\n");
            assertThat(push(clone, bob, "HEAD:refs/heads/pr/fix-parser").getStatus())
                    .isEqualTo(RemoteRefUpdate.Status.OK);
        }
        CompletableFuture<Map<String, Object>> merged = async(bob, pr + "?waitFor=merged&timeout=60");
        await().until(() -> registry.waiting(bob) == 1);

        Map<String, Object> tasks = inbox.get(10, TimeUnit.SECONDS);
        assertThat(tasks).containsEntry("waitResult", "satisfied");
        Map<String, Object> task = ((List<Map<String, Object>>) tasks.get("items")).getFirst();
        assertThat(task).containsEntry("type", "review_requested").containsEntry("number", 1);
        reviewAsTheInboxSays(alice, task);

        assertThat(merged.get(10, TimeUnit.SECONDS)).containsEntry("state", "MERGED")
                .containsEntry("waitResult", "satisfied").containsEntry("mergedBy", "agenthub");
        assertThat((List<?>) call(HttpMethod.GET, "/v1/inbox", alice, null, Map.class).getBody().get("items"))
                .isEmpty();
    }

    @Test
    void theWholeMvp() throws Exception {
        String owner = unique("olga");
        String bob = unique("bob");
        String carol = unique("carol");
        String alice = unique("alice");
        createRepo(owner, "app");
        grant(owner, "app", bob, "PROPOSE");
        grant(owner, "app", carol, "PROPOSE");
        grant(owner, "app", alice, "WRITE");
        String api = "/v1/repos/" + owner + "/app";
        assertThat(call(HttpMethod.GET, api, owner, null, Map.class).getBody()).containsEntry("role", "ADMIN");

        try (Git clone = cloneAs(bob, owner, "app")) {
            commitFile(clone, "src/a.txt", "from git\n");
            assertThat(push(clone, bob, "HEAD:refs/heads/pr/feature-a").getStatus())
                    .isEqualTo(RemoteRefUpdate.Status.OK);
        }
        List<Map<String, Object>> history = (List<Map<String, Object>>) call(HttpMethod.GET,
                api + "/commits?ref=main&limit=1", carol, null, Map.class).getBody().get("items");
        String base = (String) history.getFirst().get("sha");
        ResponseEntity<Map> proposed = call(HttpMethod.POST, api + "/pulls", carol, Map.of("name", "feature-b",
                "baseSha", base, "title", "Add b", "files", List.of(Map.of("path", "src/b.txt",
                        "content", "without git\n"))), Map.class);
        assertThat(proposed.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        await().until(() -> ((List<?>) call(HttpMethod.GET, "/v1/inbox", alice, null, Map.class).getBody()
                .get("items")).size() == 2);
        assertThat(call(HttpMethod.GET, "/v1/me", alice, null, Map.class).getBody()).containsEntry("inboxCount", 2);
        for (Map<String, Object> task : (List<Map<String, Object>>) call(HttpMethod.GET, "/v1/inbox", alice, null,
                Map.class).getBody().get("items")) {
            reviewAsTheInboxSays(alice, task);
        }

        for (int number : List.of(1, 2)) {
            assertThat(async(bob, api + "/pulls/" + number + "?waitFor=merged&timeout=60").get(30, TimeUnit.SECONDS))
                    .containsEntry("state", "MERGED").containsEntry("autoMerged", true);
        }
        assertThat(call(HttpMethod.GET, api + "/contents/src/a.txt", bob, null, Map.class).getBody())
                .containsEntry("content", "from git\n");
        assertThat(call(HttpMethod.GET, api + "/contents/src/b.txt", bob, null, Map.class).getBody())
                .containsEntry("content", "without git\n");
        assertThat((List<Map<String, Object>>) call(HttpMethod.GET, api + "/commits?ref=main&limit=2", bob, null,
                Map.class).getBody().get("items")).allSatisfy(commit -> assertThat((String) commit.get("message"))
                        .contains("Reviewed-By: " + alice));
        assertThat(call(HttpMethod.GET, "/v1/me", alice, null, Map.class).getBody()).containsEntry("inboxCount", 0);
    }
}

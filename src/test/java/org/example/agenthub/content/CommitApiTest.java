package org.example.agenthub.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.example.agenthub.events.Event;
import org.example.agenthub.events.EventRepository;
import org.example.agenthub.git.GitIntegrationTest;
import org.example.agenthub.pr.PullRequestTasks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** §10.5 {@code POST /commits}: a WRITE member commits files without git, with the rules of a push. */
class CommitApiTest extends GitIntegrationTest {

    @Autowired
    EventRepository events;

    @Autowired
    PullRequestTasks tasks;

    String owner;
    String carol;
    String bob;
    String api;
    long repoId;

    @BeforeEach
    void repository() throws Exception {
        owner = unique("olga");
        carol = unique("carol");
        bob = unique("bob");
        createRepo(owner, "proj");
        grant(owner, "proj", carol, "WRITE");
        grant(owner, "proj", bob, "PROPOSE");
        api = "/v1/repos/" + owner + "/proj";
        repoId = repos.findByOwnerAndNameAndDeletedAtIsNull(owner, "proj").orElseThrow().getId();
        try (Git writer = cloneAs(carol, owner, "proj")) {
            push(writer, carol, "HEAD:refs/heads/dev");
        }
    }

    String tip(String branch) throws Exception {
        try (Repository server = serverRepo(owner, "proj")) {
            return server.resolve("refs/heads/" + branch).name();
        }
    }

    ResponseEntity<Map> commit(String agent, String branch, String parent, List<Map<String, Object>> files) {
        return call(HttpMethod.POST, api + "/commits", agent, Map.of("branch", branch, "parentSha", parent,
                "message", "edit via API", "files", files), Map.class);
    }

    void assertRejected(ResponseEntity<Map> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).containsEntry("code", code).containsKey("hint");
    }

    @Test
    void aWriterCommitsTextBinaryAndDeletions() throws Exception {
        String parent = tip("dev");

        ResponseEntity<Map> created = commit(carol, "dev", parent, List.of(
                Map.of("path", "docs/guide.md", "content", "# Guide\n"),
                Map.of("path", "img/dot.bin", "content", Base64.getEncoder().encodeToString(new byte[] {0, 7}),
                        "encoding", "base64"),
                Map.of("path", "README.md", "delete", true)));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String sha = (String) created.getBody().get("sha");
        assertThat(created.getBody()).containsEntry("branch", "dev").containsEntry("parentSha", parent);
        assertThat(tip("dev")).isEqualTo(sha);
        try (Repository server = serverRepo(owner, "proj")) {
            RevCommit commit = server.parseCommit(server.resolve(sha));
            assertThat(commit.getParent(0).name()).isEqualTo(parent);
            assertThat(commit.getAuthorIdent().getName()).isEqualTo(carol);
            assertThat(commit.getCommitterIdent().getName()).isEqualTo("agenthub");
            assertThat(commit.getFullMessage()).isEqualTo("edit via API");
        }
        Map<String, Object> guide = call(HttpMethod.GET, api + "/contents/docs/guide.md?ref=dev", carol, null,
                Map.class).getBody();
        assertThat(guide).containsEntry("content", "# Guide\n");
        assertThat(call(HttpMethod.GET, api + "/contents/README.md?ref=dev", carol, null, Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        await().until(() -> events.findByRepoIdOrderById(repoId).stream().map(Event::getType).toList(),
                types -> types.contains("push"));
    }

    @Test
    void aCommitMovesTheTargetOfOpenPullRequests() throws Exception {
        try (Git clone = cloneAs(bob, owner, "proj")) {
            commitFile(clone, "shared.txt", "bob\n");
            push(clone, bob, "HEAD:refs/heads/pr/fix", "target=dev");
        }
        await().until(() -> tasks.idle(repoId));

        commit(carol, "dev", tip("dev"), List.of(Map.of("path", "shared.txt", "content", "carol\n")));

        await().until(() -> "CONFLICT".equals(call(HttpMethod.GET, api + "/pulls/1", carol, null, Map.class)
                .getBody().get("mergeable")));
    }

    @Test
    void aStaleParentIsRejectedWithTheCurrentTip() throws Exception {
        String parent = tip("dev");
        commit(carol, "dev", parent, List.of(Map.of("path", "a.txt", "content", "a")));

        ResponseEntity<Map> stale = commit(carol, "dev", parent, List.of(Map.of("path", "b.txt", "content", "b")));

        assertRejected(stale, HttpStatus.CONFLICT, "stale_head");
        assertThat((String) stale.getBody().get("hint")).contains(tip("dev"));
    }

    @Test
    void protectedBranchesTakeChangesThroughPullRequests() throws Exception {
        ResponseEntity<Map> refused = commit(carol, "main", tip("main"), List.of(Map.of("path", "a", "content", "a")));

        assertRejected(refused, HttpStatus.FORBIDDEN, "protected_branch");
        assertThat((String) refused.getBody().get("hint")).contains("POST " + api + "/pulls");
    }

    @Test
    void proposersCannotCommitDirectly() throws Exception {
        ResponseEntity<Map> refused = commit(bob, "dev", tip("dev"), List.of(Map.of("path", "a", "content", "a")));

        assertRejected(refused, HttpStatus.FORBIDDEN, "insufficient_role");
        assertThat((String) refused.getBody().get("hint")).contains("/pulls");
    }

    @Test
    void unsafePathsAndUnknownBranchesAreRejected() throws Exception {
        String parent = tip("dev");
        for (String path : List.of("../x", ".git/config", "a//b", "/abs", "dir/", "README.md/inner")) {
            assertRejected(commit(carol, "dev", parent, List.of(Map.of("path", path, "content", "x"))),
                    HttpStatus.BAD_REQUEST, "validation_failed");
        }
        assertRejected(commit(carol, "dev", parent, List.of()), HttpStatus.BAD_REQUEST, "validation_failed");
        assertRejected(commit(carol, "nope", parent, List.of(Map.of("path", "a", "content", "a"))),
                HttpStatus.NOT_FOUND, "not_found");
        assertRejected(commit(carol, "pr/x", parent, List.of(Map.of("path", "a", "content", "a"))),
                HttpStatus.BAD_REQUEST, "reserved_ref");
    }

    @Test
    void contentThatIsAlreadyThereChangesNothing() throws Exception {
        String readme = (String) call(HttpMethod.GET, api + "/contents/README.md?ref=dev", carol, null, Map.class)
                .getBody().get("content");

        ResponseEntity<Map> same = commit(carol, "dev", tip("dev"), List.of(Map.of("path", "README.md",
                "content", readme)));

        assertRejected(same, HttpStatus.CONFLICT, "no_effective_changes");
    }
}

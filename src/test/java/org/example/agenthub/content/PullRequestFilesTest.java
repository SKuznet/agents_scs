package org.example.agenthub.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.example.agenthub.git.GitIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** §10.3 {@code POST /pulls}: a pull request from files, through the same {@code openOrUpdate} as a push. */
class PullRequestFilesTest extends GitIntegrationTest {

    String owner;
    String bob;
    String alice;
    String reader;
    String api;
    String base;

    @BeforeEach
    void repository() throws Exception {
        owner = unique("olga");
        bob = unique("bob");
        alice = unique("alice");
        reader = unique("rita");
        createRepo(owner, "proj");
        grant(owner, "proj", bob, "PROPOSE");
        grant(owner, "proj", alice, "WRITE");
        grant(owner, "proj", reader, "READ");
        api = "/v1/repos/" + owner + "/proj";
        try (Repository server = serverRepo(owner, "proj")) {
            base = server.resolve("refs/heads/main").name();
        }
    }

    ResponseEntity<Map> propose(String agent, Map<String, Object> overrides) {
        Map<String, Object> request = new HashMap<>(Map.of("name", "fix", "baseSha", base, "title", "Fix the parser",
                "description", "Why:\nthe parser\nbroke", "files", List.of(Map.of("path", "fix.txt",
                        "content", "fixed\n"))));
        request.putAll(overrides);
        return call(HttpMethod.POST, api + "/pulls", agent, request, Map.class);
    }

    void assertRejected(ResponseEntity<Map> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).containsEntry("code", code).containsKey("hint");
    }

    @Test
    void aProposerOpensAPullRequestWithoutGit() throws Exception {
        ResponseEntity<Map> created = propose(bob, Map.of("options", List.of("no-auto-merge", "reviewer=" + alice)));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> pull = (Map<String, Object>) created.getBody().get("pull");
        assertThat(pull).containsEntry("number", 1).containsEntry("author", bob).containsEntry("target", "main")
                .containsEntry("title", "Fix the parser").containsEntry("description", "Why:\nthe parser\nbroke")
                .containsEntry("currentRev", 1).containsEntry("autoMerge", false)
                .containsEntry("reviewers", List.of(alice));
        assertThat((List<String>) created.getBody().get("messages")).anySatisfy(line -> assertThat(line)
                .contains("Created PR #1 -> main (rev 1)"));
        try (Repository server = serverRepo(owner, "proj")) {
            RevCommit head = server.parseCommit(server.resolve((String) pull.get("headSha")));
            assertThat(head.getParent(0).name()).isEqualTo(base);
            assertThat(head.getAuthorIdent().getName()).isEqualTo(bob);
            assertThat(head.getCommitterIdent().getName()).isEqualTo("agenthub");
            assertThat(server.resolve("refs/pull/1/head").name()).isEqualTo(pull.get("headSha"));
        }
    }

    @Test
    void theSameFilesAgainAreUnchangedAndNewFilesAreANewRevision() {
        String head = (String) ((Map<String, Object>) propose(bob, Map.of()).getBody().get("pull")).get("headSha");

        ResponseEntity<Map> again = propose(bob, Map.of("title", "Fix the parser, really"));

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Map<String, Object>) again.getBody().get("pull")).containsEntry("headSha", head)
                .containsEntry("currentRev", 1).containsEntry("title", "Fix the parser, really");
        assertThat((List<String>) again.getBody().get("messages")).anySatisfy(line -> assertThat(line)
                .contains("PR #1 unchanged (applied: title, description)"));

        ResponseEntity<Map> updated = propose(bob, Map.of("baseSha", head,
                "files", List.of(Map.of("path", "test.txt", "content", "test\n"))));

        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Map<String, Object>) updated.getBody().get("pull")).containsEntry("currentRev", 2);
    }

    @Test
    void theChecksOfAPushApply() {
        String readme = (String) call(HttpMethod.GET, api + "/contents/README.md", bob, null, Map.class).getBody()
                .get("content");

        assertRejected(propose(bob, Map.of("files", List.of(Map.of("path", "README.md", "content", readme)))),
                HttpStatus.CONFLICT, "no_effective_changes");
        assertRejected(propose(bob, Map.of("target", "nope")), HttpStatus.BAD_REQUEST, "validation_failed");
        assertRejected(propose(bob, Map.of("baseSha", "1".repeat(40))), HttpStatus.BAD_REQUEST, "validation_failed");
        assertRejected(propose(bob, Map.of("name", "Bad Name")), HttpStatus.BAD_REQUEST, "invalid_ref_name");
        assertRejected(propose(bob, Map.of("options", List.of("title=x"))), HttpStatus.BAD_REQUEST,
                "validation_failed");
        assertRejected(propose(reader, Map.of()), HttpStatus.FORBIDDEN, "insufficient_role");
    }
}

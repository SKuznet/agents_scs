package org.example.agenthub.pr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.example.agenthub.git.GitIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Golden paths of §17 for phase 2a: PROPOSE author, WRITE reviewer, READ cannot review. */
class Phase2aScenariosTest extends GitIntegrationTest {

    String owner;
    String bob;
    String alice;
    String rita;
    String pulls;

    @BeforeEach
    void repository() {
        owner = unique("olga");
        bob = unique("bob");
        alice = unique("alice");
        rita = unique("rita");
        createRepo(owner, "parser");
        grant(owner, "parser", bob, "PROPOSE");
        grant(owner, "parser", alice, "WRITE");
        grant(owner, "parser", rita, "READ");
        pulls = "/v1/repos/" + owner + "/parser/pulls";
    }

    /** The manual review-and-merge path of phase 2a, so auto-merge (2b) is switched off for the PR. */
    PushResult pushPr(Git clone) throws Exception {
        return clone.push().setCredentialsProvider(credentials(bob))
                .setRefSpecs(new RefSpec("HEAD:refs/heads/pr/fix-parser")).setPushOptions(List.of("no-auto-merge"))
                .call().iterator().next();
    }

    @Test
    void proposerAndWriterTakeAChangeFromPushToMerge() throws Exception {
        try (Git clone = cloneAs(bob, owner, "parser")) {
            commitFile(clone, "src/Parser.java", "class Parser { }\n");
            assertThat(pushPr(clone).getMessages()).contains("Created PR #1 -> main (rev 1)");

            Map<String, Object> pr = call(HttpMethod.GET, pulls + "/1", alice, null, Map.class).getBody();
            assertThat(pr).containsEntry("state", "OPEN").containsEntry("approvalsRequired", 1);
            Map<String, Object> diff = call(HttpMethod.GET, pulls + "/1/diff", alice, null, Map.class).getBody();
            assertThat((List<?>) diff.get("files")).hasSize(1);
            assertThat(call(HttpMethod.POST, pulls + "/1/reviews", alice,
                    Map.of("rev", 1, "verdict", "REQUEST_CHANGES", "body", "handle empty input"), Map.class)
                    .getStatusCode()).isEqualTo(HttpStatus.CREATED);

            RevCommit fixed = commitFile(clone, "src/Parser.java", "class Parser { /* empty input */ }\n");
            assertThat(pushPr(clone).getMessages()).contains("Updated PR #1 -> main (rev 2)");

            pr = call(HttpMethod.GET, pulls + "/1", alice, null, Map.class).getBody();
            assertThat(pr).containsEntry("currentRev", 2).containsEntry("headSha", fixed.name())
                    .containsEntry("blockingReviews", List.of());
            assertThat(call(HttpMethod.POST, pulls + "/1/reviews", alice, Map.of("rev", 2, "verdict", "APPROVE"),
                    Map.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            ResponseEntity<Map> merged = call(HttpMethod.POST, pulls + "/1/merge", alice,
                    Map.of("expectedHeadSha", pr.get("headSha")), Map.class);

            assertThat(merged.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(merged.getBody()).containsEntry("state", "MERGED").containsEntry("mergedBy", alice);
            assertThat(call(HttpMethod.GET, pulls + "/1", bob, null, Map.class).getBody())
                    .containsEntry("state", "MERGED");
        }
        try (Repository server = serverRepo(owner, "parser")) {
            assertThat(server.resolve("refs/heads/main:src/Parser.java")).isNotNull();
        }
    }

    @Test
    void readerCannotReview() throws Exception {
        try (Git clone = cloneAs(bob, owner, "parser")) {
            commitFile(clone, "a.txt", "a");
            pushPr(clone);
        }

        ResponseEntity<Map> refused = call(HttpMethod.POST, pulls + "/1/reviews", rita,
                Map.of("rev", 1, "verdict", "COMMENT", "body", "looks fine"), Map.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody()).containsEntry("code", "insufficient_role");
        assertThat((String) refused.getBody().get("hint"))
                .contains("PUT /v1/repos/" + owner + "/parser/grants/" + rita);
    }
}

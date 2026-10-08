package org.example.agenthub.pr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.example.agenthub.git.GitIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/** Golden path "Auto-merge" of §17: PROPOSE pushes, WRITE approves, the PR merges itself. */
class Phase2bScenariosTest extends GitIntegrationTest {

    @Test
    void approvedProposalMergesItself() throws Exception {
        String owner = unique("olga");
        String bob = unique("bob");
        String alice = unique("alice");
        createRepo(owner, "parser");
        grant(owner, "parser", bob, "PROPOSE");
        grant(owner, "parser", alice, "WRITE");
        String pr = "/v1/repos/" + owner + "/parser/pulls/1";

        RevCommit head;
        try (Git clone = cloneAs(bob, owner, "parser")) {
            head = commitFile(clone, "src/Parser.java", "class Parser { }\n");
            PushResult result = clone.push().setCredentialsProvider(credentials(bob))
                    .setRefSpecs(new RefSpec("HEAD:refs/heads/pr/fix-parser")).call().iterator().next();
            assertThat(result.getMessages()).contains("Created PR #1 -> main (rev 1)")
                    .contains("Auto-merge: waiting for 1 approval from a WRITE member");
        }

        assertThat(call(HttpMethod.POST, pr + "/reviews", alice, Map.of("rev", 1, "verdict", "APPROVE"), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);

        await().until(() -> "MERGED".equals(call(HttpMethod.GET, pr, bob, null, Map.class).getBody().get("state")));
        Map<String, Object> merged = call(HttpMethod.GET, pr, bob, null, Map.class).getBody();
        assertThat(merged).containsEntry("mergedBy", "agenthub").containsEntry("autoMerged", true)
                .containsEntry("headSha", head.name());
        try (Repository server = serverRepo(owner, "parser")) {
            RevCommit squash = server.parseCommit(server.resolve("refs/heads/main"));
            assertThat(squash.name()).isEqualTo(merged.get("mergeCommitSha"));
            assertThat(squash.getAuthorIdent().getName()).isEqualTo(bob);
            assertThat(squash.getFullMessage()).contains("Reviewed-By: " + alice);
        }
    }
}

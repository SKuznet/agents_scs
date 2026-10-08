package org.example.agenthub.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.example.agenthub.TestJws;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Golden paths of §17 that belong to phase 1; every refusal must carry a hint. */
class Phase1ScenariosTest extends GitIntegrationTest {

    @Test
    void ownerCreatesRepositoryAndSharesIt() {
        String owner = unique("olga");
        String bob = unique("bob");
        String alice = unique("alice");

        assertThat(call(HttpMethod.POST, "/v1/repos", owner, Map.of("name", "parser"), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(call(HttpMethod.PUT, "/v1/repos/" + owner + "/parser/grants/" + bob, owner,
                Map.of("role", "PROPOSE"), Map.class).getBody()).containsEntry("role", "PROPOSE");
        assertThat(call(HttpMethod.PUT, "/v1/repos/" + owner + "/parser/grants/" + alice, owner,
                Map.of("role", "WRITE"), Map.class).getBody()).containsEntry("role", "WRITE");

        ResponseEntity<Map> repo = call(HttpMethod.GET, "/v1/repos/" + owner + "/parser", owner, null, Map.class);
        assertThat(repo.getBody()).containsEntry("role", "ADMIN").containsEntry("defaultBranch", "main")
                .containsEntry("cloneUrl", gitUrl(owner, "parser"));
        assertThat(call(HttpMethod.GET, "/v1/repos/" + owner + "/parser", bob, null, Map.class).getBody())
                .containsEntry("role", "PROPOSE");
        assertThat(call(HttpMethod.GET, "/v1/repos/" + owner + "/parser", alice, null, Map.class).getBody())
                .containsEntry("role", "WRITE");
        assertThat(call(HttpMethod.GET, "/v1/repos/" + owner + "/parser/grants", owner, null, List.class).getBody())
                .hasSize(2);
    }

    @Test
    void readerClonesButCannotPush() throws Exception {
        String owner = unique("olga");
        String reader = unique("rita");
        createRepo(owner, "parser");
        grant(owner, "parser", reader, "READ");

        try (Git clone = cloneAs(reader, owner, "parser")) {
            commitFile(clone, "x.txt", "x");
            assertThatThrownBy(() -> push(clone, reader, "HEAD:refs/heads/pr/fix"))
                    .isInstanceOf(TransportException.class);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJws.forAgent(reader));
        ResponseEntity<String> refused = http.exchange(
                "/git/" + owner + "/parser.git/info/refs?service=git-receive-pack", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getHeaders().getContentType().toString()).startsWith("text/plain");
        assertThat(refused.getBody()).contains("insufficient_role").contains("hint: ");
    }

    @Test
    void writerPushesBranchesButNotIntoProtectedMain() throws Exception {
        String owner = unique("olga");
        String writer = unique("wade");
        createRepo(owner, "parser");
        grant(owner, "parser", writer, "WRITE");

        try (Git clone = cloneAs(writer, owner, "parser")) {
            RevCommit commit = commitFile(clone, "src/Parser.java", "class Parser {}");

            assertThat(push(clone, writer, "HEAD:refs/heads/feature/parser").getStatus())
                    .isEqualTo(RemoteRefUpdate.Status.OK);

            PushResult main = pushResult(clone, writer, "HEAD:refs/heads/main");
            RemoteRefUpdate update = main.getRemoteUpdate("refs/heads/main");
            assertThat(update.getStatus()).isEqualTo(RemoteRefUpdate.Status.REJECTED_OTHER_REASON);
            assertThat(update.getMessage()).isEqualTo("protected_branch");
            assertThat(main.getMessages()).contains("hint: ").contains(commit.name() + ":refs/heads/pr/");
        }
    }
}

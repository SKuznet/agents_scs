package org.example.agenthub.pr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.JWTClaimsSet;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.example.agenthub.TestJws;
import org.example.agenthub.git.GitIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** §10.3 reads and the §10.4 diff format. */
class PullRequestReadApiTest extends GitIntegrationTest {

    String owner;
    String bob;
    String carol;
    String base;

    @BeforeEach
    void repository() {
        owner = unique("olga");
        bob = unique("bob");
        carol = unique("carol");
        createRepo(owner, "proj");
        grant(owner, "proj", bob, "PROPOSE");
        grant(owner, "proj", carol, "WRITE");
        base = "/v1/repos/" + owner + "/proj/pulls";
    }

    RemoteRefUpdate pushPr(Git git, String agent, String name, String... options) throws Exception {
        return push(git, agent, "HEAD:refs/heads/pr/" + name, options);
    }

    Map<String, Object> get(String path, String agent) {
        ResponseEntity<Map> response = call(HttpMethod.GET, path, agent, null, Map.class);
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    List<Map<String, Object>> list(String path, String agent) {
        ResponseEntity<List> response = call(HttpMethod.GET, path, agent, null, List.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    static List<Object> numbers(Map<String, Object> page) {
        return ((List<Map<String, Object>>) page.get("items")).stream().map(item -> item.get("number")).toList();
    }

    @Test
    void listFiltersByStateAuthorAndTarget() throws Exception {
        try (Git writer = cloneAs(carol, owner, "proj"); Git clone = cloneAs(bob, owner, "proj")) {
            push(writer, carol, "HEAD:refs/heads/dev");
            commitFile(clone, "a.txt", "a");
            pushPr(clone, bob, "one");
            commitFile(writer, "b.txt", "b");
            pushPr(writer, carol, "two", "target=dev");
        }

        assertThat(numbers(get(base, owner))).containsExactly(2, 1);
        assertThat(numbers(get(base + "?author=" + bob, owner))).containsExactly(1);
        assertThat(numbers(get(base + "?target=dev", owner))).containsExactly(2);
        assertThat(numbers(get(base + "?state=closed", owner))).isEmpty();
        Map<String, Object> page = get(base + "?limit=1", owner);
        assertThat(numbers(page)).containsExactly(2);
        assertThat(numbers(get(base + "?limit=1&cursor=" + page.get("nextCursor"), owner))).containsExactly(1);
        assertThat(((List<Map<String, Object>>) get(base, owner).get("items")).getFirst())
                .containsEntry("author", carol).containsEntry("target", "dev").containsEntry("state", "OPEN")
                .containsKeys("title", "headSha", "currentRev", "createdAt");
    }

    @Test
    void pullRequestStatusFollowsTheTarget() throws Exception {
        try (Git writer = cloneAs(carol, owner, "proj"); Git clone = cloneAs(bob, owner, "proj")) {
            push(writer, carol, "HEAD:refs/heads/dev");
            RevCommit head = commitFile(clone, "a.txt", "a");
            pushPr(clone, bob, "fix", "target=dev");

            Map<String, Object> pr = get(base + "/1", bob);
            assertThat(pr).containsEntry("number", 1).containsEntry("name", "fix").containsEntry("author", bob)
                    .containsEntry("target", "dev").containsEntry("state", "OPEN").containsEntry("draft", false)
                    .containsEntry("headSha", head.name()).containsEntry("currentRev", 1)
                    .containsEntry("behindBy", 0).containsEntry("mergeable", "CLEAN")
                    .containsEntry("conflictPaths", List.of()).containsEntry("approvals", List.of())
                    .containsEntry("approvalsRequired", 1).containsEntry("blockingReviews", List.of())
                    .containsEntry("reviewers", List.of()).containsEntry("autoMerge", null)
                    .containsEntry("mergeCommitSha", null).containsEntry("mergedBy", null)
                    .containsEntry("autoMerged", false).containsEntry("autoMergeBlockedReason", null);

            commitFile(writer, "dev1.txt", "1");
            commitFile(writer, "dev2.txt", "2");
            push(writer, carol, "HEAD:refs/heads/dev");

            assertThat(get(base + "/1", bob)).containsEntry("behindBy", 2).containsEntry("mergeable", "UNKNOWN");
        }
    }

    @Test
    void readersSeePullRequestsStrangersDoNot() throws Exception {
        String reader = unique("rita");
        grant(owner, "proj", reader, "READ");
        try (Git clone = cloneAs(bob, owner, "proj")) {
            commitFile(clone, "a.txt", "a");
            pushPr(clone, bob, "fix");
        }

        assertThat(get(base + "/1", reader)).containsEntry("number", 1);
        assertThat(call(HttpMethod.GET, base + "/1", unique("eve"), null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<Map> missing = call(HttpMethod.GET, base + "/42", reader, null, Map.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getBody()).containsEntry("code", "not_found").containsKey("hint");
    }

    @Test
    void revisionsRecordWhoPushedWithWhichClaims() throws Exception {
        String token = TestJws.sign(new JWTClaimsSet.Builder().subject(bob)
                .expirationTime(Date.from(Instant.now().plusSeconds(600)))
                .claim("model", "claude-opus-5-5").claim("session", "run-8f2a").build(), TestJws.SECRET,
                JWSAlgorithm.HS256);
        RevCommit first;
        RevCommit second;
        try (Git clone = cloneAs(bob, owner, "proj")) {
            first = commitFile(clone, "a.txt", "a");
            pushPr(clone, bob, "fix");
            second = commitFile(clone, "a.txt", "b");
            clone.push().setCredentialsProvider(new UsernamePasswordCredentialsProvider("x", token))
                    .setRefSpecs(new RefSpec("HEAD:refs/heads/pr/fix")).call();
        }

        List<Map<String, Object>> revisions = list(base + "/1/revisions", bob);

        assertThat(revisions).hasSize(2);
        assertThat(revisions.get(0)).containsEntry("rev", 1).containsEntry("headSha", first.name())
                .containsEntry("pushedBy", bob).containsEntry("model", null).containsKeys("baseSha", "createdAt");
        assertThat(revisions.get(1)).containsEntry("rev", 2).containsEntry("headSha", second.name())
                .containsEntry("model", "claude-opus-5-5").containsEntry("session", "run-8f2a");
    }

    @Test
    void commitsFilesAndDiffOfARevision() throws Exception {
        RevCommit first;
        RevCommit second;
        try (Git clone = cloneAs(bob, owner, "proj")) {
            first = commitFile(clone, "src/a.txt", "one\ntwo\nthree\n");
            pushPr(clone, bob, "fix");
            second = commitFile(clone, "src/a.txt", "one\n2\nthree\n");
            pushPr(clone, bob, "fix");
        }

        assertThat(list(base + "/1/commits?rev=1", bob)).extracting(c -> c.get("sha")).containsExactly(first.name());
        assertThat(list(base + "/1/commits", bob)).extracting(c -> c.get("sha"))
                .containsExactly(second.name(), first.name());
        List<Map<String, Object>> files = list(base + "/1/files", bob);
        assertThat(files).hasSize(1);
        assertThat(files.getFirst()).containsEntry("path", "src/a.txt").containsEntry("oldPath", null)
                .containsEntry("status", "ADDED").containsEntry("additions", 3).containsEntry("deletions", 0)
                .containsEntry("binary", false);

        Map<String, Object> diff = get(base + "/1/diff?rev=2&path=src/a.txt", bob);
        assertThat(diff).containsEntry("truncated", false);
        Map<String, Object> file = ((List<Map<String, Object>>) diff.get("files")).getFirst();
        Map<String, Object> hunk = ((List<Map<String, Object>>) file.get("hunks")).getFirst();
        assertThat(hunk).containsEntry("oldStart", 0).containsEntry("newStart", 1).containsEntry("newLines", 3);
        assertThat((List<Map<String, Object>>) hunk.get("lines"))
                .extracting(l -> l.get("t"), l -> l.get("o"), l -> l.get("n"), l -> l.get("s"))
                .containsExactly(tuple("+", null, 1, "one"), tuple("+", null, 2, "2"), tuple("+", null, 3, "three"));

        assertThat(get(base + "/1/diff?maxLines=1", bob)).containsEntry("truncated", true);
        assertThat(call(HttpMethod.GET, base + "/1/diff?rev=9", bob, null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}

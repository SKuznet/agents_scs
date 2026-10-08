package org.example.agenthub.pr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.example.agenthub.git.GitIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Requested reviewers (§7.3): push option {@code reviewer=a,b} and PATCH {@code reviewers}. */
class ReviewersTest extends GitIntegrationTest {

    String owner;
    String bob;
    String carol;
    String erin;
    String dave;
    String api;
    Git clone;

    @BeforeEach
    void repository() throws Exception {
        owner = unique("olga");
        bob = unique("bob");
        carol = unique("carol");
        erin = unique("erin");
        dave = unique("dave");
        createRepo(owner, "proj");
        grant(owner, "proj", bob, "PROPOSE");
        grant(owner, "proj", dave, "PROPOSE");
        grant(owner, "proj", carol, "WRITE");
        grant(owner, "proj", erin, "WRITE");
        api = "/v1/repos/" + owner + "/proj/pulls/1";
        clone = cloneAs(bob, owner, "proj");
        commitFile(clone, "a.txt", "a");
    }

    @AfterEach
    void close() {
        clone.close();
    }

    PushResult push(String... options) throws Exception {
        return clone.push().setCredentialsProvider(credentials(bob)).setRefSpecs(new RefSpec("HEAD:refs/heads/pr/fix"))
                .setPushOptions(List.of(options)).call().iterator().next();
    }

    Map<String, Object> pr() {
        return call(HttpMethod.GET, api, owner, null, Map.class).getBody();
    }

    @Test
    void reviewersAreRequestedByPush() throws Exception {
        PushResult result = push("reviewer=" + erin + "," + carol, "no-auto-merge");

        assertThat(result.getMessages()).doesNotContain("warning");
        assertThat(pr()).containsEntry("reviewers", List.of(carol, erin).stream().sorted().toList())
                .doesNotContainKey("warnings");
    }

    @Test
    void reviewersWithoutWriteAreAcceptedWithAWarning() throws Exception {
        String stranger = unique("sam");

        PushResult result = push("reviewer=" + dave + "," + stranger);

        assertThat(result.getMessages())
                .contains("[agenthub] warning: reviewer '" + dave + "' has no WRITE role in " + owner + "/proj")
                .contains("[agenthub] warning: reviewer '" + stranger + "' has no WRITE role");
        assertThat((List<String>) pr().get("warnings")).hasSize(2);
    }

    @Test
    void sameHeadWithNewReviewersIsUnchangedButApplied() throws Exception {
        push("reviewer=" + carol);

        PushResult again = push("reviewer=" + erin);

        assertThat(again.getMessages()).contains("PR #1 unchanged (applied: reviewer=" + erin + ")");
        assertThat(pr()).containsEntry("reviewers", List.of(erin));
    }

    @Test
    void invalidReviewersAreRefused() throws Exception {
        RemoteRefUpdate bad = push("reviewer=Not-A-Handle").getRemoteUpdate("refs/heads/pr/fix");
        RemoteRefUpdate self = push("reviewer=" + bob).getRemoteUpdate("refs/heads/pr/fix");

        assertThat(bad.getMessage()).isEqualTo("validation_failed");
        assertThat(self.getMessage()).isEqualTo("validation_failed");
    }

    @Test
    void patchReplacesTheReviewers() throws Exception {
        push("reviewer=" + carol);

        ResponseEntity<Map> replaced = call(HttpMethod.PATCH, api, bob, Map.of("reviewers", List.of(erin, dave)),
                Map.class);

        assertThat(replaced.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replaced.getBody()).containsEntry("reviewers", List.of(dave, erin).stream().sorted().toList());
        assertThat((List<String>) replaced.getBody().get("warnings")).singleElement().asString().contains(dave);

        assertThat(call(HttpMethod.PATCH, api, bob, Map.of("reviewers", List.of()), Map.class).getBody())
                .containsEntry("reviewers", List.of());
    }
}

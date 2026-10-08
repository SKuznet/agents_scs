package org.example.agenthub.pr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.example.agenthub.events.Event;
import org.example.agenthub.events.EventRepository;
import org.example.agenthub.git.GitIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** PATCH of §7.9 and §10.3 (phase 2a fields). */
class PullRequestPatchTest extends GitIntegrationTest {

    @Autowired
    EventRepository events;

    String owner;
    String bob;
    String carol;
    String path;
    RevCommit head;
    long repoId;

    @BeforeEach
    void pullRequest() throws Exception {
        owner = unique("olga");
        bob = unique("bob");
        carol = unique("carol");
        createRepo(owner, "proj");
        grant(owner, "proj", bob, "PROPOSE");
        grant(owner, "proj", carol, "WRITE");
        repoId = repos.findByOwnerAndNameAndDeletedAtIsNull(owner, "proj").orElseThrow().getId();
        try (Git clone = cloneAs(bob, owner, "proj")) {
            head = commitFile(clone, "a.txt", "a");
            push(clone, bob, "HEAD:refs/heads/pr/fix");
        }
        path = "/v1/repos/" + owner + "/proj/pulls/1";
    }

    ResponseEntity<Map> patch(String agent, Map<String, ?> body) {
        return call(HttpMethod.PATCH, path, agent, body, Map.class);
    }

    Map<String, Object> ok(ResponseEntity<Map> response) {
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    void refused(ResponseEntity<Map> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(status);
        assertThat(response.getBody()).containsEntry("code", code).containsKey("hint");
    }

    @Test
    void authorEditsTitleDescriptionAndDraft() {
        Map<String, Object> pr = ok(patch(bob, Map.of("title", "Better title", "description", "Line one\nLine two",
                "draft", true)));

        assertThat(pr).containsEntry("title", "Better title").containsEntry("description", "Line one\nLine two")
                .containsEntry("draft", true);
        assertThat(ok(patch(bob, Map.of("draft", false)))).containsEntry("draft", false);
    }

    @Test
    void authorClosesAndReopensWithRefsKept() throws Exception {
        assertThat(ok(patch(bob, Map.of("state", "closed")))).containsEntry("state", "CLOSED");
        assertThat(ok(patch(bob, Map.of("state", "CLOSED")))).containsEntry("state", "CLOSED");
        try (Repository server = serverRepo(owner, "proj")) {
            assertThat(server.exactRef("refs/pull/1/head").getObjectId()).isEqualTo(head.getId());
        }

        assertThat(ok(patch(bob, Map.of("state", "OPEN")))).containsEntry("state", "OPEN");
        await().until(() -> events.findByRepoIdOrderById(repoId).stream().map(Event::getType).toList(),
                types -> types.contains("pr.closed") && types.contains("pr.reopened"));
    }

    @Test
    void reopenIsRefusedWhileAnotherOpenPrUsesTheName() throws Exception {
        ok(patch(bob, Map.of("state", "CLOSED")));
        try (Git clone = cloneAs(bob, owner, "proj")) {
            commitFile(clone, "b.txt", "b");
            push(clone, bob, "HEAD:refs/heads/pr/fix");
        }

        refused(patch(bob, Map.of("state", "OPEN")), HttpStatus.CONFLICT, "already_exists");
    }

    @Test
    void writersManageOthersPullRequestsProposersDoNot() {
        String dave = unique("dave");
        String reader = unique("rita");
        grant(owner, "proj", dave, "PROPOSE");
        grant(owner, "proj", reader, "READ");

        refused(patch(dave, Map.of("state", "CLOSED")), HttpStatus.FORBIDDEN, "insufficient_role");
        refused(patch(reader, Map.of("title", "mine")), HttpStatus.FORBIDDEN, "insufficient_role");
        assertThat(ok(patch(carol, Map.of("state", "CLOSED")))).containsEntry("state", "CLOSED");
        assertThat(ok(patch(owner, Map.of("state", "OPEN")))).containsEntry("state", "OPEN");
    }

    @Test
    void invalidChangesAreRefused() {
        refused(patch(bob, Map.of("title", " ")), HttpStatus.BAD_REQUEST, "validation_failed");
        refused(patch(bob, Map.of("title", "x".repeat(1001))), HttpStatus.BAD_REQUEST, "validation_failed");
        refused(patch(bob, Map.of("state", "MERGED")), HttpStatus.BAD_REQUEST, "validation_failed");
        refused(patch(bob, Map.of("target", "nope")), HttpStatus.BAD_REQUEST, "validation_failed");
    }

    @Test
    void changingTargetMakesANewRevisionBasedOnTheNewTarget() throws Exception {
        try (Git writer = cloneAs(carol, owner, "proj"); Repository server = serverRepo(owner, "proj")) {
            commitFile(writer, "dev.txt", "dev");
            push(writer, carol, "HEAD:refs/heads/dev");

            Map<String, Object> pr = ok(patch(bob, Map.of("target", "dev")));

            assertThat(pr).containsEntry("target", "dev").containsEntry("currentRev", 2)
                    .containsEntry("headSha", head.name());
            assertThat(server.exactRef("refs/pull/1/rev/2").getObjectId()).isEqualTo(head.getId());
            Map<String, Object> rev2 = (Map<String, Object>) call(HttpMethod.GET, path + "/revisions", bob, null,
                    java.util.List.class).getBody().get(1);
            assertThat(rev2).containsEntry("baseSha", server.resolve("refs/heads/dev").name())
                    .containsEntry("pushedBy", bob);
        }
    }

    @Test
    void targetThatAlreadyContainsTheHeadIsRefused() throws Exception {
        try (Git writer = cloneAs(carol, owner, "proj")) {
            writer.fetch().setCredentialsProvider(credentials(carol)).setRefSpecs("refs/pull/1/head:refs/pr1").call();
            writer.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef("refs/pr1").call();
            push(writer, carol, "HEAD:refs/heads/dev");
        }

        refused(patch(bob, Map.of("target", "dev")), HttpStatus.CONFLICT, "no_new_commits");
    }
}

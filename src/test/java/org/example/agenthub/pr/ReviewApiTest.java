package org.example.agenthub.pr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.example.agenthub.events.Event;
import org.example.agenthub.events.EventRepository;
import org.example.agenthub.git.GitIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Reviews of §7.6: who may give which verdict, on which revision, and what counts. */
class ReviewApiTest extends GitIntegrationTest {

    @Autowired
    EventRepository events;

    String owner;
    String bob;
    String carol;
    String dave;
    String reader;
    String pr;
    Git bobsClone;

    @BeforeEach
    void pullRequest() throws Exception {
        owner = unique("olga");
        bob = unique("bob");
        carol = unique("carol");
        dave = unique("dave");
        reader = unique("rita");
        createRepo(owner, "proj");
        grant(owner, "proj", bob, "PROPOSE");
        grant(owner, "proj", dave, "PROPOSE");
        grant(owner, "proj", carol, "WRITE");
        grant(owner, "proj", reader, "READ");
        bobsClone = cloneAs(bob, owner, "proj");
        commitFile(bobsClone, "a.txt", "a");
        push(bobsClone, bob, "HEAD:refs/heads/pr/fix", "no-auto-merge");
        pr = "/v1/repos/" + owner + "/proj/pulls/1";
    }

    @AfterEach
    void closeClone() {
        bobsClone.close();
    }

    ResponseEntity<Map> review(String agent, Map<String, ?> body) {
        return call(HttpMethod.POST, pr + "/reviews", agent, body, Map.class);
    }

    void refused(ResponseEntity<Map> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(status);
        assertThat(response.getBody()).containsEntry("code", code).containsKey("hint");
    }

    Map<String, Object> status() {
        return call(HttpMethod.GET, pr, owner, null, Map.class).getBody();
    }

    @Test
    void writerApprovesAndTheApprovalCounts() {
        ResponseEntity<Map> approved = review(carol, Map.of("rev", 1, "verdict", "APPROVE", "body", "LGTM"));

        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(approved.getBody()).containsEntry("reviewer", carol).containsEntry("verdict", "APPROVE")
                .containsEntry("rev", 1).containsEntry("body", "LGTM");
        assertThat(status()).containsEntry("approvals", List.of(carol)).containsEntry("approvalsRequired", 1);
        await().until(() -> events.findAll().stream().map(Event::getType).toList(),
                types -> types.contains("pr.review"));
    }

    @Test
    void repeatedApprovalIsIdempotent() {
        Map<String, Object> first = review(carol, Map.of("rev", 1, "verdict", "APPROVE")).getBody();
        ResponseEntity<Map> again = review(carol, Map.of("rev", 1, "verdict", "APPROVE"));

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody()).containsEntry("id", first.get("id"));
        assertThat(call(HttpMethod.GET, pr + "/reviews", reader, null, List.class).getBody()).hasSize(1);
    }

    @Test
    void readerCannotReview() {
        refused(review(reader, Map.of("rev", 1, "verdict", "COMMENT", "body", "hi")), HttpStatus.FORBIDDEN,
                "insufficient_role");
    }

    @Test
    void proposerMayOnlyComment() {
        ResponseEntity<Map> comment = review(dave, Map.of("rev", 1, "verdict", "COMMENT", "body", "nit"));

        assertThat(comment.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        refused(review(dave, Map.of("rev", 1, "verdict", "APPROVE")), HttpStatus.FORBIDDEN, "insufficient_role");
        refused(review(dave, Map.of("rev", 1, "verdict", "REQUEST_CHANGES")), HttpStatus.FORBIDDEN,
                "insufficient_role");
    }

    @Test
    void authorsCannotApproveTheirOwnPullRequest() throws Exception {
        try (Git clone = cloneAs(carol, owner, "proj")) {
            commitFile(clone, "c.txt", "c");
            push(clone, carol, "HEAD:refs/heads/pr/mine", "no-auto-merge");
        }

        refused(call(HttpMethod.POST, "/v1/repos/" + owner + "/proj/pulls/2/reviews", carol,
                Map.of("rev", 1, "verdict", "APPROVE"), Map.class), HttpStatus.FORBIDDEN, "self_review");
        assertThat(review(bob, Map.of("rev", 1, "verdict", "COMMENT", "body", "note to self")).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void reviewMustTargetTheCurrentRevision() throws Exception {
        commitFile(bobsClone, "a.txt", "b");
        push(bobsClone, bob, "HEAD:refs/heads/pr/fix");

        ResponseEntity<Map> stale = review(carol, Map.of("rev", 1, "verdict", "APPROVE"));

        refused(stale, HttpStatus.CONFLICT, "stale_revision");
        assertThat((String) stale.getBody().get("hint")).contains("rev 2");
    }

    @Test
    void requestChangesBlocksUntilANewRevisionWhichAlsoNeedsNewApprovals() throws Exception {
        String erin = unique("erin");
        grant(owner, "proj", erin, "WRITE");
        review(carol, Map.of("rev", 1, "verdict", "APPROVE"));
        review(erin, Map.of("rev", 1, "verdict", "REQUEST_CHANGES", "body", "please add a test"));
        assertThat(status()).containsEntry("blockingReviews", List.of(erin)).containsEntry("approvals", List.of(carol));

        commitFile(bobsClone, "a.txt", "with test");
        push(bobsClone, bob, "HEAD:refs/heads/pr/fix");

        assertThat(status()).containsEntry("blockingReviews", List.of()).containsEntry("approvals", List.of())
                .containsEntry("currentRev", 2);
        assertThat(call(HttpMethod.GET, pr + "/reviews", reader, null, List.class).getBody()).hasSize(2);
    }

    @Test
    void closedPullRequestsAreNotReviewed() {
        call(HttpMethod.PATCH, pr, bob, Map.of("state", "CLOSED"), Map.class);

        refused(review(carol, Map.of("rev", 1, "verdict", "APPROVE")), HttpStatus.CONFLICT, "not_open");
    }

    @Test
    void malformedReviewsAreRefused() {
        refused(review(carol, Map.of("verdict", "APPROVE")), HttpStatus.BAD_REQUEST, "validation_failed");
        refused(review(carol, Map.of("rev", 1, "verdict", "LOVE_IT")), HttpStatus.BAD_REQUEST, "validation_failed");
        refused(review(carol, Map.of("rev", 1, "verdict", "COMMENT", "body", "x".repeat(64 * 1024 + 1))),
                HttpStatus.BAD_REQUEST, "validation_failed");
        refused(review(carol, Map.of("rev", 1, "verdict", "COMMENT", "comments",
                List.of(Map.of("path", "a.txt", "line", 7, "body", "here")))), HttpStatus.UNPROCESSABLE_ENTITY,
                "line_out_of_range");
    }
}

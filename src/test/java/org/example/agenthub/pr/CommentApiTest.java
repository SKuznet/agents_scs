package org.example.agenthub.pr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.HashMap;
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

/** Line comments of §7.6: anchored to lines of the file in a revision, threaded through parentId. */
class CommentApiTest extends GitIntegrationTest {

    static final String TEN = "1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n";

    @Autowired
    EventRepository events;

    String owner;
    String bob;
    String carol;
    String dave;
    String reader;
    String api;
    long repoId;
    Git bobs;

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
        repoId = repos.findByOwnerAndNameAndDeletedAtIsNull(owner, "proj").orElseThrow().getId();
        try (Git writer = cloneAs(carol, owner, "proj")) {
            commitFile(writer, "ten.txt", TEN);
            push(writer, carol, "HEAD:refs/heads/dev");
        }
        bobs = cloneAs(bob, owner, "proj");
        bobs.checkout().setName("dev").setCreateBranch(true).setStartPoint("origin/dev").call();
        commitFile(bobs, "ten.txt", TEN.replace("5\n", "five\n"));
        commitFile(bobs, "new.txt", "a\nb\nc\n");
        push(bobs, bob, "HEAD:refs/heads/pr/fix", "target=dev");
        api = "/v1/repos/" + owner + "/proj/pulls/1";
    }

    @AfterEach
    void close() {
        bobs.close();
    }

    static Map<String, Object> comment(int rev, String path, Integer line, String side, String body) {
        Map<String, Object> request = new HashMap<>();
        request.put("rev", rev);
        request.put("path", path);
        request.put("line", line);
        request.put("side", side);
        request.put("body", body);
        return request;
    }

    ResponseEntity<Map> post(String agent, Map<String, Object> request) {
        return call(HttpMethod.POST, api + "/comments", agent, request, Map.class);
    }

    List<Map<String, Object>> threads(String query) {
        return call(HttpMethod.GET, api + "/comments" + query, reader, null, List.class).getBody();
    }

    static void refused(ResponseEntity<Map> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(status);
        assertThat(response.getBody()).containsEntry("code", code).containsKey("hint");
    }

    @Test
    void lineCommentOnTheNewSide() {
        ResponseEntity<Map> created = post(dave, comment(1, "ten.txt", 5, "NEW", "why spell it out?"));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).containsEntry("author", dave).containsEntry("rev", 1)
                .containsEntry("path", "ten.txt").containsEntry("line", 5).containsEntry("side", "NEW")
                .containsEntry("body", "why spell it out?").containsEntry("parentId", null);
        await().until(() -> events.findByRepoIdOrderById(repoId).stream().map(Event::getType).toList(),
                types -> types.contains("pr.comment"));
    }

    @Test
    void oldSideLinesAreNumberedInTheFileAtTheMergeBase() {
        assertThat(post(carol, comment(1, "ten.txt", 10, "OLD", "last line")).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> outside = post(carol, comment(1, "ten.txt", 11, "OLD", "beyond"));
        refused(outside, HttpStatus.UNPROCESSABLE_ENTITY, "line_out_of_range");
    }

    @Test
    void lineOutsideTheFileNamesTheNearestHunk() {
        ResponseEntity<Map> outside = post(carol, comment(1, "ten.txt", 42, "NEW", "?"));

        refused(outside, HttpStatus.UNPROCESSABLE_ENTITY, "line_out_of_range");
        assertThat((Map<String, Object>) outside.getBody().get("nearestHunk"))
                .containsEntry("newStart", 2).containsEntry("newLines", 7);
        assertThat((String) outside.getBody().get("hint")).contains("/diff?rev=1&path=ten.txt");
    }

    @Test
    void anchorsMustPointIntoTheRevision() {
        refused(post(carol, comment(1, "missing.txt", 1, "NEW", "?")), HttpStatus.BAD_REQUEST, "validation_failed");
        refused(post(carol, comment(1, null, 3, "NEW", "?")), HttpStatus.BAD_REQUEST, "validation_failed");
        refused(post(carol, comment(9, "ten.txt", 1, "NEW", "?")), HttpStatus.NOT_FOUND, "not_found");
        refused(post(carol, comment(1, "ten.txt", 1, "SIDEWAYS", "?")), HttpStatus.BAD_REQUEST,
                "validation_failed");
        refused(post(carol, comment(1, null, null, null, " ")), HttpStatus.BAD_REQUEST, "validation_failed");
        refused(post(carol, comment(1, null, null, null, "x".repeat(64 * 1024 + 1))), HttpStatus.BAD_REQUEST,
                "validation_failed");
    }

    @Test
    void fileAndGeneralCommentsNeedNoLine() {
        assertThat(post(carol, comment(1, "new.txt", null, null, "nice file")).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(post(carol, comment(1, null, null, null, "overall fine")).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void repliesFormThreads() {
        Number root = (Number) post(carol, comment(1, "ten.txt", 5, "NEW", "why?")).getBody().get("id");
        Map<String, Object> reply = comment(1, null, null, null, "it reads better");
        reply.put("parentId", root);
        assertThat(post(bob, reply).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> answer = comment(1, null, null, null, "ok");
        answer.put("parentId", root);
        post(carol, answer);

        List<Map<String, Object>> threads = threads("");

        assertThat(threads).hasSize(1);
        assertThat(threads.getFirst()).containsEntry("body", "why?");
        assertThat((List<Map<String, Object>>) threads.getFirst().get("replies")).extracting(r -> r.get("body"))
                .containsExactly("it reads better", "ok");

        Map<String, Object> orphan = comment(1, null, null, null, "lost");
        orphan.put("parentId", 999999);
        refused(post(bob, orphan), HttpStatus.BAD_REQUEST, "validation_failed");
    }

    @Test
    void readersReadButDoNotComment() {
        refused(post(reader, comment(1, null, null, null, "hi")), HttpStatus.FORBIDDEN, "insufficient_role");
        assertThat(call(HttpMethod.POST, api + "/comments", unique("eve"), comment(1, null, null, null, "hi"),
                Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void reviewsCarryLineComments() {
        Map<String, Object> request = new HashMap<>(Map.of("rev", 1, "verdict", "COMMENT", "body", "see inline"));
        request.put("comments", List.of(Map.of("path", "new.txt", "line", 2, "body", "b?")));

        assertThat(call(HttpMethod.POST, api + "/reviews", dave, request, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(threads("?rev=1")).extracting(t -> t.get("body")).containsExactly("b?");

        request.put("comments", List.of(Map.of("path", "new.txt", "line", 9, "body", "nowhere")));
        refused(call(HttpMethod.POST, api + "/reviews", dave, request, Map.class), HttpStatus.UNPROCESSABLE_ENTITY,
                "line_out_of_range");
        assertThat(call(HttpMethod.GET, api + "/reviews", reader, null, List.class).getBody()).hasSize(1);
    }

    @Test
    void threadsCanBeFilteredByRevision() throws Exception {
        post(carol, comment(1, "ten.txt", 1, "NEW", "on rev 1"));
        commitFile(bobs, "new.txt", "a\nb\nc\nd\n");
        push(bobs, bob, "HEAD:refs/heads/pr/fix");
        post(carol, comment(2, "new.txt", 4, "NEW", "on rev 2"));

        assertThat(threads("?rev=1")).extracting(t -> t.get("body")).containsExactly("on rev 1");
        assertThat(threads("?rev=2")).extracting(t -> t.get("body")).containsExactly("on rev 2");
        assertThat(threads("")).hasSize(2);
    }
}

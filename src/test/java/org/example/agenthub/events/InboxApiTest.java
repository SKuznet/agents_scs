package org.example.agenthub.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.example.agenthub.git.GitIntegrationTest;
import org.example.agenthub.pr.PullRequestTasks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

/** §9.4: the inbox is computed from state; every task carries the action that resolves it. */
class InboxApiTest extends GitIntegrationTest {

    @Autowired
    PullRequestTasks tasks;

    String owner;
    String bob;
    String alice;
    String carol;
    String reader;
    String repo;
    String api;
    long repoId;

    @BeforeEach
    void repository() throws Exception {
        owner = unique("olga");
        bob = unique("bob");
        alice = unique("alice");
        carol = unique("carol");
        reader = unique("rita");
        createRepo(owner, "proj");
        grant(owner, "proj", bob, "PROPOSE");
        grant(owner, "proj", alice, "WRITE");
        grant(owner, "proj", carol, "WRITE");
        grant(owner, "proj", reader, "READ");
        repo = owner + "/proj";
        api = "/v1/repos/" + repo + "/pulls/1";
        repoId = repos.findByOwnerAndNameAndDeletedAtIsNull(owner, "proj").orElseThrow().getId();
        try (Git writer = cloneAs(carol, owner, "proj")) {
            push(writer, carol, "HEAD:refs/heads/dev");
        }
    }

    void openPr(String... options) throws Exception {
        try (Git clone = cloneAs(bob, owner, "proj")) {
            commitFile(clone, "shared.txt", "bob's version\n");
            push(clone, bob, "HEAD:refs/heads/pr/fix", options);
        }
        await().until(() -> tasks.idle(repoId));
    }

    Map<String, Object> inbox(String agent) {
        return call(HttpMethod.GET, "/v1/inbox", agent, null, Map.class).getBody();
    }

    List<Map<String, Object>> items(String agent) {
        return (List<Map<String, Object>>) inbox(agent).get("items");
    }

    List<Object> types(String agent) {
        return items(agent).stream().map(item -> item.get("type")).toList();
    }

    void review(String agent, String verdict) {
        call(HttpMethod.POST, api + "/reviews", agent, Map.of("rev", 1, "verdict", verdict, "body", "x"), Map.class);
        await().until(() -> tasks.idle(repoId));
    }

    @Test
    void writersAreAskedToReviewWithReadyToUseActions() throws Exception {
        openPr("no-auto-merge");

        Map<String, Object> item = items(alice).getFirst();

        assertThat(item).containsEntry("type", "review_requested").containsEntry("repo", repo)
                .containsEntry("number", 1).containsEntry("rev", 1).containsKey("headSha");
        assertThat((Map<String, Object>) item.get("title")).containsEntry("author", bob)
                .containsEntry("authorRole", "PROPOSE").containsKey("text");
        List<Map<String, Object>> actions = (List<Map<String, Object>>) item.get("actions");
        assertThat(actions.get(0)).containsEntry("method", "GET").containsEntry("path", api + "/review-context");
        assertThat(actions.get(1)).containsEntry("method", "POST").containsEntry("path", api + "/reviews");
        assertThat((Map<String, Object>) actions.get(1).get("body")).containsEntry("rev", 1);
        assertThat(types(owner)).containsExactly("review_requested");
        assertThat(types(carol)).containsExactly("review_requested");
        assertThat(types(bob)).isEmpty();
        assertThat(types(reader)).isEmpty();
        assertThat(call(HttpMethod.GET, "/v1/me", alice, null, Map.class).getBody()).containsEntry("inboxCount", 1);
    }

    @Test
    void reviewTaskEndsWhenEnoughApprovalsAreIn() throws Exception {
        openPr("no-auto-merge");

        review(alice, "APPROVE");

        assertThat(types(alice)).isEmpty();
        assertThat(types(carol)).isEmpty();
    }

    @Test
    void draftsAreNotUpForReviewAndRequestedReviewersNarrowTheAudience() throws Exception {
        openPr("draft");
        assertThat(types(alice)).isEmpty();

        call(HttpMethod.PATCH, api, bob, Map.of("draft", false, "reviewers", List.of(carol)), Map.class);

        assertThat(types(carol)).containsExactly("review_requested");
        assertThat(types(alice)).isEmpty();
    }

    @Test
    void requestedChangesComeBackToTheAuthorWithTheGitCommand() throws Exception {
        openPr("no-auto-merge");

        review(carol, "REQUEST_CHANGES");

        Map<String, Object> item = items(bob).getFirst();
        assertThat(item).containsEntry("type", "changes_requested").containsEntry("number", 1);
        assertThat((List<Map<String, Object>>) item.get("actions")).anySatisfy(action -> assertThat(action)
                .containsEntry("command", "git push origin HEAD:refs/heads/pr/fix"));
        assertThat(types(carol)).isEmpty();
    }

    @Test
    void conflictsAndBlockedAutoMergesAreTheAuthorsTasks() throws Exception {
        Map<String, Object> protection = new HashMap<>(Map.of("requirePr", false, "allowForcePush", false,
                "allowDelete", true, "requiredApprovals", 1, "autoMerge", true));
        call(HttpMethod.PUT, "/v1/repos/" + repo + "/protection?branch=dev", owner, protection, Map.class);
        openPr("target=dev");
        try (Git writer = cloneAs(carol, owner, "proj")) {
            writer.checkout().setName("dev").setCreateBranch(true).setStartPoint("origin/dev").call();
            commitFile(writer, "shared.txt", "carol's version\n");
            push(writer, carol, "HEAD:refs/heads/dev");
        }
        await().until(() -> types(bob).contains("conflict"));
        Map<String, Object> conflict = items(bob).getFirst();
        assertThat(conflict).containsEntry("conflictPaths", List.of("shared.txt"));
        assertThat((String) conflict.get("reason")).doesNotContain("shared.txt");
        assertThat((List<Map<String, Object>>) conflict.get("actions")).anySatisfy(action -> assertThat(
                (String) action.get("command")).contains("git rebase origin/dev"));

        review(alice, "APPROVE");

        await().until(() -> types(bob).contains("auto_merge_blocked"));
        assertThat(items(bob).stream().filter(i -> i.get("type").equals("auto_merge_blocked")).findFirst()
                .orElseThrow()).containsKey("reason");
    }

    @Test
    void inboxVersionChangesExactlyWhenTheContentChanges() throws Exception {
        String empty = (String) inbox(alice).get("inboxVersion");
        assertThat(inbox(alice).get("inboxVersion")).isEqualTo(empty);

        openPr("no-auto-merge");
        String one = (String) inbox(alice).get("inboxVersion");
        assertThat(one).isNotEqualTo(empty);
        assertThat(inbox(alice).get("inboxVersion")).isEqualTo(one);

        review(carol, "APPROVE");
        assertThat(inbox(alice).get("inboxVersion")).isEqualTo(empty);
    }
}

package org.example.agenthub.pr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.example.agenthub.events.Event;
import org.example.agenthub.events.EventRepository;
import org.example.agenthub.git.GitIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

/** §7.8: the server merges approved, mergeable PRs itself, never past invariant D8. */
class AutoMergeTest extends GitIntegrationTest {

    @Autowired
    PullRequestTasks tasks;

    @Autowired
    EventRepository events;

    String owner;
    String bob;
    String carol;
    long repoId;
    String api;

    @BeforeEach
    void repository() throws Exception {
        owner = unique("olga");
        bob = unique("bob");
        carol = unique("carol");
        createRepo(owner, "proj");
        grant(owner, "proj", bob, "PROPOSE");
        grant(owner, "proj", carol, "WRITE");
        repoId = repos.findByOwnerAndNameAndDeletedAtIsNull(owner, "proj").orElseThrow().getId();
        api = "/v1/repos/" + owner + "/proj/pulls/";
        try (Git writer = cloneAs(carol, owner, "proj")) {
            push(writer, carol, "HEAD:refs/heads/dev");
        }
    }

    PushResult pushPr(String agent, String name, String file, String... options) throws Exception {
        try (Git clone = cloneAs(agent, owner, "proj")) {
            commitFile(clone, file, "content of " + file + "\n");
            return clone.push().setCredentialsProvider(credentials(agent))
                    .setRefSpecs(new RefSpec("HEAD:refs/heads/pr/" + name))
                    .setPushOptions(options.length == 0 ? null : List.of(options)).call().iterator().next();
        }
    }

    Map<String, Object> pr(int number) {
        return call(HttpMethod.GET, api + number, owner, null, Map.class).getBody();
    }

    void approve(int number) {
        call(HttpMethod.POST, api + number + "/reviews", carol,
                Map.of("rev", pr(number).get("currentRev"), "verdict", "APPROVE"), Map.class);
    }

    void settle() {
        await().until(() -> tasks.idle(repoId));
    }

    void awaitMerged(int number) {
        await().until(() -> "MERGED".equals(pr(number).get("state")));
    }

    @Test
    void approvedPullRequestIntoTheDefaultBranchMergesItself() throws Exception {
        PushResult created = pushPr(bob, "fix", "fix.txt");
        assertThat(created.getMessages()).contains("  Auto-merge: waiting for 1 approval from a WRITE member");
        settle();
        assertThat(pr(1)).containsEntry("state", "OPEN");

        approve(1);

        awaitMerged(1);
        assertThat(pr(1)).containsEntry("mergedBy", "agenthub").containsEntry("autoMerged", true);
        try (Repository server = serverRepo(owner, "proj")) {
            RevCommit merge = server.parseCommit(server.resolve("refs/heads/main"));
            assertThat(merge.getCommitterIdent().getName()).isEqualTo("agenthub");
            assertThat(merge.getFullMessage()).contains("Reviewed-By: " + carol);
        }
    }

    @Test
    void autoMergeNeverSkipsTheApprovalOfAWriterD8() throws Exception {
        pushPr(bob, "fix", "fix.txt", "target=dev", "auto-merge");
        settle();
        assertThat(pr(1)).containsEntry("state", "OPEN").containsEntry("mergeable", "CLEAN");

        approve(1);

        awaitMerged(1);
    }

    @Test
    void writerWithNothingToWaitForIsMergedRightAway() throws Exception {
        PushResult created = pushPr(carol, "quick", "quick.txt", "target=dev", "auto-merge");

        assertThat(created.getMessages()).contains("Auto-merge:");
        awaitMerged(1);
        assertThat(pr(1)).containsEntry("mergedBy", "agenthub");
    }

    @Test
    void optingOutAndBackIn() throws Exception {
        PushResult created = pushPr(bob, "manual", "m.txt", "no-auto-merge");
        assertThat(created.getMessages()).contains("  Merge:   needs 1 approval from a WRITE member");
        approve(1);
        settle();
        assertThat(pr(1)).containsEntry("state", "OPEN").containsEntry("autoMerge", false);

        call(HttpMethod.PATCH, api + "1", bob, Map.of("autoMerge", true), Map.class);

        awaitMerged(1);
    }

    @Test
    void draftWaitsUntilReady() throws Exception {
        pushPr(bob, "draft", "d.txt", "draft");
        approve(1);
        settle();
        assertThat(pr(1)).containsEntry("state", "OPEN");

        call(HttpMethod.PATCH, api + "1", bob, Map.of("draft", false), Map.class);

        awaitMerged(1);
    }

    @Test
    void conflictBlocksTheAutoMergeWithAReason() throws Exception {
        Map<String, Object> protection = new HashMap<>(Map.of("requirePr", false, "allowForcePush", false,
                "allowDelete", true, "requiredApprovals", 1, "autoMerge", true));
        call(HttpMethod.PUT, "/v1/repos/" + owner + "/proj/protection?branch=dev", owner, protection, Map.class);
        pushPr(bob, "fix", "shared.txt", "target=dev");
        try (Git writer = cloneAs(carol, owner, "proj")) {
            writer.checkout().setName("dev").setCreateBranch(true).setStartPoint("origin/dev").call();
            commitFile(writer, "shared.txt", "carol's version\n");
            push(writer, carol, "HEAD:refs/heads/dev");
        }
        await().until(() -> "CONFLICT".equals(pr(1).get("mergeable")));

        approve(1);

        await().until(() -> pr(1).get("autoMergeBlockedReason") != null);
        assertThat((String) pr(1).get("autoMergeBlockedReason")).contains("shared.txt");
        assertThat(pr(1)).containsEntry("state", "OPEN");
        await().until(() -> events.findByRepoIdOrderById(repoId).stream().map(Event::getType).toList(),
                types -> types.contains("pr.auto_merge_blocked"));
    }

    @Test
    void autoMergeUsesTheStrategyOfTheBranchProtection() throws Exception {
        Map<String, Object> protection = new HashMap<>(Map.of("requirePr", true, "allowForcePush", false,
                "allowDelete", false, "requiredApprovals", 1, "autoMerge", true, "autoMergeStrategy", "MERGE"));
        call(HttpMethod.PUT, "/v1/repos/" + owner + "/proj/protection?branch=main", owner, protection, Map.class);
        pushPr(bob, "fix", "fix.txt");

        approve(1);

        awaitMerged(1);
        try (Repository server = serverRepo(owner, "proj")) {
            assertThat(server.parseCommit(server.resolve("refs/heads/main")).getParentCount()).isEqualTo(2);
        }
    }

    @Test
    void autoMergeOfChangesThatAlreadyLandedIsBlocked() throws Exception {
        pushPr(bob, "dup", "dup.txt", "target=dev", "auto-merge");
        try (Git writer = cloneAs(carol, owner, "proj")) {
            writer.checkout().setName("dev").setCreateBranch(true).setStartPoint("origin/dev").call();
            commitFile(writer, "dup.txt", "content of dup.txt\n");
            writer.commit().setAmend(true).setMessage("the same change, landed separately").setSign(false).call();
            push(writer, carol, "HEAD:refs/heads/dev");
        }
        settle();

        approve(1);

        await().until(() -> pr(1).get("autoMergeBlockedReason") != null);
        assertThat((String) pr(1).get("autoMergeBlockedReason")).contains("changes nothing");
        assertThat(pr(1)).containsEntry("state", "OPEN");
    }

    @Test
    void soleWriterIsToldHowToProceed() throws Exception {
        String solo = unique("solo");
        createRepo(solo, "alone");
        try (Git clone = cloneAs(solo, solo, "alone")) {
            commitFile(clone, "a.txt", "a");
            PushResult result = clone.push().setCredentialsProvider(credentials(solo))
                    .setRefSpecs(new RefSpec("HEAD:refs/heads/pr/fix")).call().iterator().next();

            assertThat(result.getMessages()).contains("you are the only WRITE member")
                    .contains("\"bypassProtection\":true").contains("/protection?branch=main");
        }
    }
}

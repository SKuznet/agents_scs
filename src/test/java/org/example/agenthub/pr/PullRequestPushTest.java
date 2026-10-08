package org.example.agenthub.pr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.example.agenthub.events.Event;
import org.example.agenthub.events.EventRepository;
import org.example.agenthub.git.GitIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** §7.2: a push to {@code refs/heads/pr/<name>} opens, updates or leaves unchanged the pusher's PR. */
class PullRequestPushTest extends GitIntegrationTest {

    @Autowired
    PullRequestRepository pulls;

    @Autowired
    PrRevisionRepository revisions;

    @Autowired
    EventRepository events;

    String owner;
    String bob;
    String carol;
    long repoId;

    @BeforeEach
    void repository() {
        owner = unique("olga");
        bob = unique("bob");
        carol = unique("carol");
        createRepo(owner, "proj");
        grant(owner, "proj", bob, "PROPOSE");
        grant(owner, "proj", carol, "WRITE");
        repoId = repos.findByOwnerAndNameAndDeletedAtIsNull(owner, "proj").orElseThrow().getId();
    }

    PushResult pushPr(Git git, String agent, String name, String... options) throws Exception {
        return git.push().setCredentialsProvider(credentials(agent))
                .setRefSpecs(new RefSpec("HEAD:refs/heads/pr/" + name))
                .setPushOptions(options.length == 0 ? null : List.of(options))
                .call().iterator().next();
    }

    static RemoteRefUpdate update(PushResult result, String name) {
        return result.getRemoteUpdate("refs/heads/pr/" + name);
    }

    static void assertAccepted(PushResult result, String name) {
        assertThat(update(result, name).getStatus()).as(result.getMessages()).isEqualTo(RemoteRefUpdate.Status.OK);
    }

    static void assertRejected(PushResult result, String name, String code) {
        RemoteRefUpdate update = update(result, name);
        assertThat(update.getStatus()).as(result.getMessages())
                .isEqualTo(RemoteRefUpdate.Status.REJECTED_OTHER_REASON);
        assertThat(update.getMessage()).isEqualTo(code);
    }

    PullRequest pr(int number) {
        return pulls.findByRepoIdAndNumber(repoId, number).orElseThrow();
    }

    List<String> eventTypes() {
        return events.findByRepoIdOrderById(repoId).stream().map(Event::getType).toList();
    }

    @Test
    void pushToPrBranchOpensAPullRequest() throws Exception {
        try (Git clone = cloneAs(bob, owner, "proj"); Repository server = serverRepo(owner, "proj")) {
            String mainTip = server.resolve("refs/heads/main").name();
            RevCommit commit = commitFile(clone, "src/Parser.java", "class Parser {}");
            commit = clone.commit().setAmend(true).setMessage("Fix parser\n\nHandles empty input.\n")
                    .setAuthor("bob", "bob@example.org").setCommitter("bob", "bob@example.org").setSign(false).call();

            PushResult result = pushPr(clone, bob, "fix-parser");

            assertAccepted(result, "fix-parser");
            assertThat(result.getMessages())
                    .contains("[agenthub] Created PR #1 -> main (rev 1)")
                    .contains("  Status:  GET /v1/repos/" + owner + "/proj/pulls/1")
                    .contains("  Auto-merge: waiting for 1 approval from a WRITE member")
                    .contains("  Update:  commit and run the same push again (no --force needed)")
                    .matches("\\A[\\x20-\\x7e\\n]*\\z");
            assertThat(server.exactRef("refs/pull/1/head").getObjectId().name()).isEqualTo(commit.name());
            assertThat(server.exactRef("refs/pull/1/rev/1").getObjectId().name()).isEqualTo(commit.name());
            assertThat(server.exactRef("refs/heads/pr/fix-parser")).isNull();

            PullRequest pr = pr(1);
            assertThat(pr.getTitle()).isEqualTo("Fix parser");
            assertThat(pr.getDescription()).isEqualTo("Handles empty input.");
            assertThat(pr.getAuthor()).isEqualTo(bob);
            assertThat(pr.getTargetBranch()).isEqualTo("main");
            assertThat(pr.getState()).isEqualTo(PrState.OPEN);
            assertThat(pr.getCurrentRev()).isEqualTo(1);
            assertThat(pr.getMergeable()).isEqualTo(Mergeable.CLEAN);
            PrRevision rev = revisions.findByPrIdAndRev(pr.getId(), 1).orElseThrow();
            assertThat(rev.getBaseSha()).isEqualTo(mainTip);
            assertThat(rev.getPushedBy()).isEqualTo(bob);
        }
        await().until(this::eventTypes, types -> types.contains("pr.opened"));
    }

    @Test
    void samePushAgainIsUnchangedButAppliesOptions() throws Exception {
        try (Git clone = cloneAs(bob, owner, "proj")) {
            commitFile(clone, "a.txt", "a");
            assertAccepted(pushPr(clone, bob, "fix"), "fix");

            PushResult again = pushPr(clone, bob, "fix");
            PushResult draft = pushPr(clone, bob, "fix", "draft");

            assertAccepted(again, "fix");
            assertThat(again.getMessages()).contains("[agenthub] PR #1 unchanged");
            assertAccepted(draft, "fix");
            assertThat(draft.getMessages()).contains("[agenthub] PR #1 unchanged (applied: draft)");
            assertThat(pr(1).isDraft()).isTrue();
            assertThat(pr(1).getCurrentRev()).isEqualTo(1);
        }
    }

    @Test
    void newCommitMakesANewRevision() throws Exception {
        try (Git clone = cloneAs(bob, owner, "proj"); Repository server = serverRepo(owner, "proj")) {
            RevCommit first = commitFile(clone, "a.txt", "a");
            pushPr(clone, bob, "fix");
            RevCommit second = commitFile(clone, "a.txt", "b");

            PushResult result = pushPr(clone, bob, "fix");

            assertAccepted(result, "fix");
            assertThat(result.getMessages()).contains("[agenthub] Updated PR #1 -> main (rev 2)");
            assertThat(server.exactRef("refs/pull/1/rev/1").getObjectId()).isEqualTo(first.getId());
            assertThat(server.exactRef("refs/pull/1/rev/2").getObjectId()).isEqualTo(second.getId());
            assertThat(server.exactRef("refs/pull/1/head").getObjectId()).isEqualTo(second.getId());
            assertThat(pr(1).getCurrentRev()).isEqualTo(2);
            assertThat(pr(1).getHeadSha()).isEqualTo(second.name());
        }
        await().until(this::eventTypes, types -> types.contains("pr.updated"));
    }

    @Test
    void optionsSetTitleDescriptionTargetAndDraft() throws Exception {
        try (Git writer = cloneAs(carol, owner, "proj"); Git clone = cloneAs(bob, owner, "proj")) {
            push(writer, carol, "HEAD:refs/heads/dev");
            commitFile(clone, "a.txt", "a");

            PushResult result = pushPr(clone, bob, "fix", "title=Better parser", "description=One line", "target=dev",
                    "draft");

            assertAccepted(result, "fix");
            assertThat(result.getMessages()).contains("Created PR #1 -> dev (rev 1)").contains("Draft:");
            PullRequest pr = pr(1);
            assertThat(pr.getTitle()).isEqualTo("Better parser");
            assertThat(pr.getDescription()).isEqualTo("One line");
            assertThat(pr.getTargetBranch()).isEqualTo("dev");
            assertThat(pr.isDraft()).isTrue();
        }
    }

    @Test
    void changingTargetOfAnOpenPrIsANewRevision() throws Exception {
        try (Git writer = cloneAs(carol, owner, "proj"); Git clone = cloneAs(bob, owner, "proj")) {
            push(writer, carol, "HEAD:refs/heads/dev");
            commitFile(clone, "a.txt", "a");
            pushPr(clone, bob, "fix");

            PushResult result = pushPr(clone, bob, "fix", "target=dev");

            assertThat(result.getMessages()).contains("Updated PR #1 -> dev (rev 2)");
            assertThat(pr(1).getTargetBranch()).isEqualTo("dev");
        }
    }

    @Test
    void targetMustExist() throws Exception {
        try (Git clone = cloneAs(bob, owner, "proj")) {
            commitFile(clone, "a.txt", "a");

            PushResult result = pushPr(clone, bob, "fix", "target=nope");

            assertRejected(result, "fix", "validation_failed");
            assertThat(result.getMessages()).contains("/branches");
        }
    }

    @Test
    void unrelatedHistoryIsRefused() throws Exception {
        File dir = Files.createTempDirectory(workspace, "orphan").toFile();
        try (Git orphan = Git.init().setDirectory(dir).setInitialBranch("main").call()) {
            commitFile(orphan, "x.txt", "x");

            PushResult result = orphan.push().setRemote(gitUrl(owner, "proj")).setCredentialsProvider(credentials(bob))
                    .setRefSpecs(new RefSpec("HEAD:refs/heads/pr/orphan")).call().iterator().next();

            assertRejected(result, "orphan", "unrelated_history");
            assertThat(result.getMessages()).contains("git fetch origin && git rebase origin/main");
        }
    }

    @Test
    void commitAlreadyInTargetHasNoNewCommits() throws Exception {
        try (Git clone = cloneAs(bob, owner, "proj")) {
            PushResult result = pushPr(clone, bob, "nothing");

            assertRejected(result, "nothing", "no_new_commits");
            assertThat(result.getMessages()).contains("hint: ");
        }
    }

    @Test
    void changesAlreadyInTargetHaveNoEffect() throws Exception {
        try (Git writer = cloneAs(carol, owner, "proj"); Git clone = cloneAs(bob, owner, "proj")) {
            commitFile(writer, "same.txt", "same");
            push(writer, carol, "HEAD:refs/heads/dev");
            commitFile(clone, "same.txt", "same");
            clone.commit().setAmend(true).setMessage("the same change, made independently").setSign(false).call();

            PushResult result = pushPr(clone, bob, "dup", "target=dev");

            assertRejected(result, "dup", "no_effective_changes");
            assertThat(result.getMessages()).contains("git fetch origin && git switch -C dup origin/dev");
        }
    }

    @Test
    void unknownOptionsAreRefused() throws Exception {
        try (Git clone = cloneAs(bob, owner, "proj")) {
            commitFile(clone, "a.txt", "a");

            PushResult colour = pushPr(clone, bob, "fix", "colour=red");

            assertRejected(colour, "fix", "unknown_push_option");
            assertThat(colour.getMessages()).contains("reviewer=").contains("auto-merge");
            assertThat(pulls.findOpen(repoId, bob, "fix")).isEmpty();
        }
    }

    @Test
    void eachAuthorHasOwnPullRequestForTheSameName() throws Exception {
        try (Git bobs = cloneAs(bob, owner, "proj"); Git carols = cloneAs(carol, owner, "proj")) {
            commitFile(bobs, "b.txt", "b");
            commitFile(carols, "c.txt", "c");

            assertThat(pushPr(bobs, bob, "fix").getMessages()).contains("Created PR #1");
            assertThat(pushPr(carols, carol, "fix").getMessages()).contains("Created PR #2")
                    .contains("Auto-merge: waiting for 1 approval");
        }
    }

    @Test
    void conflictIsReportedButThePullRequestIsOpened() throws Exception {
        try (Git writer = cloneAs(carol, owner, "proj"); Git clone = cloneAs(bob, owner, "proj")) {
            commitFile(writer, "README.md", "dev version\n");
            push(writer, carol, "HEAD:refs/heads/dev");
            commitFile(clone, "README.md", "bob version\n");

            PushResult result = pushPr(clone, bob, "readme", "target=dev");

            assertAccepted(result, "readme");
            assertThat(result.getMessages()).contains("Conflict: README.md")
                    .contains("git fetch origin && git rebase origin/dev");
            assertThat(pr(1).getMergeable()).isEqualTo(Mergeable.CONFLICT);
            assertThat(pr(1).getConflictPaths()).containsExactly("README.md");
        }
    }
}

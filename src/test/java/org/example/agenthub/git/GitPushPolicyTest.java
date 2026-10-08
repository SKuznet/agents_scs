package org.example.agenthub.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.errors.TooLargePackException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

/** §6.6 end to end: JGit client over HTTP, statuses and {@code remote:} messages as an agent sees them. */
class GitPushPolicyTest extends GitIntegrationTest {

    @Autowired
    TransactionTemplate tx;

    String alice;
    String writer;

    void repoWithWriter(Map<String, ?> request) {
        alice = unique("alice");
        writer = unique("carol");
        createRepo(alice, request);
        grant(alice, (String) request.get("name"), writer, "WRITE");
    }

    static void assertRejected(RemoteRefUpdate update, String code) {
        assertThat(update.getStatus()).isEqualTo(RemoteRefUpdate.Status.REJECTED_OTHER_REASON);
        assertThat(update.getMessage()).isEqualTo(code);
    }

    @Test
    void pushToProtectedDefaultBranchIsRejectedWithPullRequestHint() throws Exception {
        repoWithWriter(Map.of("name", "proj"));
        try (Git clone = cloneAs(writer, alice, "proj")) {
            RevCommit commit = commitFile(clone, "a.txt", "a");

            PushResult result = pushResult(clone, writer, "HEAD:refs/heads/main");

            assertRejected(result.getRemoteUpdate("refs/heads/main"), "protected_branch");
            assertThat(result.getMessages()).contains("protected_branch")
                    .contains("git push origin " + commit.name() + ":refs/heads/pr/" + writer + "-");
        }
    }

    @Test
    void writerUsesUnprotectedBranchesFreely() throws Exception {
        repoWithWriter(Map.of("name", "proj"));
        try (Git clone = cloneAs(writer, alice, "proj")) {
            RevCommit first = commitFile(clone, "a.txt", "a");
            assertThat(push(clone, writer, "HEAD:refs/heads/topic").getStatus()).isEqualTo(RemoteRefUpdate.Status.OK);

            clone.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef("HEAD~1").call();
            commitFile(clone, "b.txt", "b");
            assertThat(push(clone, writer, "+HEAD:refs/heads/topic").getStatus())
                    .isEqualTo(RemoteRefUpdate.Status.OK);
            assertThat(push(clone, writer, "HEAD:refs/tags/v1.0").getStatus()).isEqualTo(RemoteRefUpdate.Status.OK);
            assertThat(push(clone, writer, ":refs/heads/topic").getStatus()).isEqualTo(RemoteRefUpdate.Status.OK);

            try (Repository server = serverRepo(alice, "proj")) {
                assertThat(server.exactRef("refs/heads/topic")).isNull();
                assertThat(server.exactRef("refs/tags/v1.0")).isNotNull();
                assertThat(server.resolve("refs/heads/main")).isNotEqualTo(first.getId());
            }
        }
    }

    @Test
    void proposerIsPointedAtPullRequests() throws Exception {
        String owner = unique("alice");
        String bob = unique("bob");
        createRepo(owner, "proj");
        grant(owner, "proj", bob, "PROPOSE");
        try (Git clone = cloneAs(bob, owner, "proj")) {
            RevCommit commit = commitFile(clone, "a.txt", "a");

            PushResult branch = pushResult(clone, bob, "HEAD:refs/heads/fix-parser");

            assertRejected(branch.getRemoteUpdate("refs/heads/fix-parser"), "insufficient_role");
            String hint = "git push origin " + commit.name() + ":refs/heads/pr/fix-parser";
            assertThat(branch.getMessages()).contains(hint);

            PushResult followed = pushResult(clone, bob, commit.name() + ":refs/heads/pr/fix-parser");
            assertThat(followed.getRemoteUpdate("refs/heads/pr/fix-parser").getStatus())
                    .isEqualTo(RemoteRefUpdate.Status.OK);
            assertThat(followed.getMessages()).contains("Created PR #1 -> main (rev 1)");
        }
    }

    @Test
    void reservedNamespacesAndBadNamesAreRefused() throws Exception {
        repoWithWriter(Map.of("name", "proj"));
        try (Git clone = cloneAs(writer, alice, "proj")) {
            commitFile(clone, "a.txt", "a");

            PushResult result = pushResult(clone, writer, "HEAD:refs/for/main", "HEAD:refs/pull/1/head",
                    "HEAD:refs/meta/config", "HEAD:refs/heads/Feature", "HEAD:refs/heads/main/x");

            assertRejected(result.getRemoteUpdate("refs/for/main"), "reserved_ref");
            assertRejected(result.getRemoteUpdate("refs/pull/1/head"), "reserved_ref");
            assertRejected(result.getRemoteUpdate("refs/meta/config"), "reserved_ref");
            assertRejected(result.getRemoteUpdate("refs/heads/Feature"), "invalid_ref_name");
            assertRejected(result.getRemoteUpdate("refs/heads/main/x"), "invalid_ref_name");
            assertThat(result.getMessages()).contains(":refs/heads/pr/").contains("conflicts with existing");
        }
    }

    @Test
    void overQuotaRepositoryAcceptsOnlyDeletesAndTinyPacks() throws Exception {
        repoWithWriter(Map.of("name", "full"));
        var created = repos.findByOwnerAndNameAndDeletedAtIsNull(alice, "full").orElseThrow();
        long id = created.getId();
        try (Git clone = cloneAs(writer, alice, "full")) {
            commitFile(clone, "a.txt", "a");
            assertThat(push(clone, writer, "HEAD:refs/heads/old").getStatus()).isEqualTo(RemoteRefUpdate.Status.OK);
            await().until(() -> repos.findById(id).orElseThrow().getSizeBytes() > created.getSizeBytes());
            tx.executeWithoutResult(s -> repos.updateSize(id, DataSize.ofGigabytes(2).toBytes()));

            PushResult refused = pushResult(clone, writer, "HEAD:refs/heads/again");
            assertRejected(refused.getRemoteUpdate("refs/heads/again"), "quota_exceeded");
            assertThat(refused.getMessages()).contains("git push origin :refs/heads/<branch>");

            commitFile(clone, "big.txt", IntStream.range(0, 200).mapToObj(i -> UUID.randomUUID().toString())
                    .collect(Collectors.joining()));
            assertThatThrownBy(() -> push(clone, writer, "HEAD:refs/heads/big"))
                    .hasRootCauseInstanceOf(TooLargePackException.class);

            assertThat(push(clone, writer, ":refs/heads/old").getStatus()).isEqualTo(RemoteRefUpdate.Status.OK);
        }
    }

    @Test
    void firstPushIntoEmptyRepositoryCreatesTheDefaultBranch() throws Exception {
        repoWithWriter(Map.of("name", "blank", "init", false));
        try (Git clone = cloneAs(writer, alice, "blank")) {
            commitFile(clone, "a.txt", "a");
            assertThat(push(clone, writer, "HEAD:refs/heads/main").getStatus()).isEqualTo(RemoteRefUpdate.Status.OK);

            commitFile(clone, "b.txt", "b");
            assertRejected(push(clone, writer, "HEAD:refs/heads/main"), "protected_branch");
        }
    }
}

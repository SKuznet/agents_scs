package org.example.agenthub.pr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.example.agenthub.git.GitIntegrationTest;
import org.example.agenthub.repo.RepoStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** §7.9 reconciliation and §13 restoration of {@code refs/pull/*} from the database. */
class ReconcilerTest extends GitIntegrationTest {

    @Autowired
    Reconciler reconciler;

    @Autowired
    PullRequestRepository pulls;

    @Autowired
    RepoStorage storage;

    @Autowired
    TransactionTemplate tx;

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

    RevCommit openPr(String name, String... options) throws Exception {
        try (Git clone = cloneAs(bob, owner, "proj")) {
            RevCommit head = commitFile(clone, name + ".txt", name);
            push(clone, bob, "HEAD:refs/heads/pr/" + name, options);
            return head;
        }
    }

    PullRequest pr(int number) {
        return pulls.findByRepoIdAndNumber(repoId, number).orElseThrow();
    }

    void intent(int number, ObjectId commit) {
        tx.executeWithoutResult(s -> pulls.findByRepoIdAndNumber(repoId, number).orElseThrow()
                .startMerge(commit.name(), carol));
    }

    void move(String ref, ObjectId to) throws Exception {
        try (Repository db = storage.open(repoId)) {
            RefUpdate update = db.updateRef(ref);
            update.setNewObjectId(to);
            update.setForceUpdate(true);
            assertThat(update.update()).isIn(RefUpdate.Result.FAST_FORWARD, RefUpdate.Result.FORCED,
                    RefUpdate.Result.NEW);
        }
    }

    @Test
    void intentWhoseCommitReachedTheTargetCompletesTheMerge() throws Exception {
        RevCommit head = openPr("fix");
        intent(1, head);
        move("refs/heads/main", head);

        reconciler.reconcileAll();

        PullRequest pr = pr(1);
        assertThat(pr.getState()).isEqualTo(PrState.MERGED);
        assertThat(pr.getMergeCommitSha()).isEqualTo(head.name());
        assertThat(pr.getMergedBy()).isEqualTo(carol);
        assertThat(pr.getMergingCommitSha()).isNull();
    }

    @Test
    void intentWhoseCommitNeverLandedIsDropped() throws Exception {
        RevCommit head = openPr("fix");
        intent(1, head);

        reconciler.reconcileAll();

        PullRequest pr = pr(1);
        assertThat(pr.getState()).isEqualTo(PrState.OPEN);
        assertThat(pr.getMergingCommitSha()).isNull();
        assertThat(pr.getMergedBy()).isNull();
    }

    @Test
    void pushingTheHeadIntoTheTargetByHandMarksThePullRequestMerged() throws Exception {
        try (Git writer = cloneAs(carol, owner, "proj")) {
            push(writer, carol, "HEAD:refs/heads/dev");
            openPr("fix", "target=dev");
            writer.fetch().setCredentialsProvider(credentials(carol)).setRefSpecs("refs/pull/1/head:refs/pr1").call();
            writer.reset().setMode(ResetCommand.ResetType.HARD).setRef("refs/pr1").call();
            RevCommit merge = commitFile(writer, "after.txt", "after");
            push(writer, carol, "HEAD:refs/heads/dev");

            await().until(() -> pr(1).getState() == PrState.MERGED);
            assertThat(pr(1).getMergedBy()).isEqualTo(carol);
            assertThat(pr(1).getMergeCommitSha()).isEqualTo(merge.name());
        }
    }

    @Test
    void pullRefsArePutBackFromTheDatabase() throws Exception {
        RevCommit first = openPr("fix");
        RevCommit main;
        try (Repository db = storage.open(repoId)) {
            main = db.parseCommit(db.resolve("refs/heads/main"));
            RefUpdate drop = db.updateRef("refs/pull/1/head");
            drop.setForceUpdate(true);
            drop.delete();
        }
        move("refs/pull/1/rev/1", main);
        move("refs/pull/1/rev/7", main);
        move("refs/pull/9/head", main);

        reconciler.reconcileAll();

        try (Repository db = storage.open(repoId)) {
            assertThat(db.exactRef("refs/pull/1/head").getObjectId()).isEqualTo(first.getId());
            assertThat(db.exactRef("refs/pull/1/rev/1").getObjectId()).isEqualTo(first.getId());
            assertThat(db.exactRef("refs/pull/1/rev/7")).isNull();
            assertThat(db.exactRef("refs/pull/9/head")).isNull();
        }
    }
}

package org.example.agenthub.pr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.example.agenthub.git.GitIntegrationTest;
import org.example.agenthub.repo.RepoStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Races of §18 phase 2b: merge vs close, merge vs push to the target, auto-merge vs a new revision. */
class RaceTest extends GitIntegrationTest {

    @Autowired
    MergeService merges;

    @Autowired
    PullRequestTasks tasks;

    @Autowired
    PullRequestRepository pulls;

    @Autowired
    RepoStorage storage;

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

    @AfterEach
    void removeSeam() {
        merges.beforeTargetUpdate = (db, branch) -> { };
        tasks.resume(repoId);
    }

    RevCommit openPr(String name, String... options) throws Exception {
        try (Git clone = cloneAs(bob, owner, "proj")) {
            RevCommit head = commitFile(clone, name + ".txt", name);
            push(clone, bob, "HEAD:refs/heads/pr/" + name, options);
            return head;
        }
    }

    void approve(int number, int rev) {
        assertThat(call(HttpMethod.POST, api + number + "/reviews", carol, Map.of("rev", rev, "verdict", "APPROVE"),
                Map.class).getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.OK);
    }

    String tip(String branch) throws Exception {
        try (Repository db = storage.open(repoId)) {
            return db.resolve("refs/heads/" + branch).name();
        }
    }

    /** What a concurrent push does: a new commit on top of the branch. */
    static void advance(Repository db, String branch) throws Exception {
        ObjectId tip = db.resolve("refs/heads/" + branch);
        RevCommit parent = db.parseCommit(tip);
        try (ObjectInserter inserter = db.newObjectInserter()) {
            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(parent.getTree());
            commit.setParentId(tip);
            PersonIdent someone = new PersonIdent("someone", "someone@example.org");
            commit.setAuthor(someone);
            commit.setCommitter(someone);
            commit.setMessage("concurrent push\n");
            ObjectId id = inserter.insert(commit);
            inserter.flush();
            RefUpdate update = db.updateRef("refs/heads/" + branch);
            update.setExpectedOldObjectId(tip);
            update.setNewObjectId(id);
            assertThat(update.update()).isEqualTo(RefUpdate.Result.FAST_FORWARD);
        }
    }

    @Test
    void mergeAndCloseNeverBothWin() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int number = 1; number <= 3; number++) {
                RevCommit head = openPr("race" + number, "no-auto-merge");
                approve(number, 1);
                String before = tip("main");
                int n = number;
                CountDownLatch start = new CountDownLatch(1);
                Callable<ResponseEntity<Map>> merge = () -> {
                    start.await();
                    return call(HttpMethod.POST, api + n + "/merge", carol, Map.of("expectedHeadSha", head.name()),
                            Map.class);
                };
                Callable<ResponseEntity<Map>> close = () -> {
                    start.await();
                    return call(HttpMethod.PATCH, api + n, bob, Map.of("state", "CLOSED"), Map.class);
                };
                Future<ResponseEntity<Map>> merged = pool.submit(merge);
                Future<ResponseEntity<Map>> closed = pool.submit(close);
                start.countDown();

                boolean mergeWon = merged.get().getStatusCode() == HttpStatus.OK;
                boolean closeWon = closed.get().getStatusCode() == HttpStatus.OK;
                PullRequest pr = pulls.findByRepoIdAndNumber(repoId, number).orElseThrow();
                assertThat(mergeWon ^ closeWon).as("exactly one wins").isTrue();
                if (mergeWon) {
                    assertThat(pr.getState()).isEqualTo(PrState.MERGED);
                    assertThat(closed.get().getBody()).containsEntry("code", "not_open");
                    assertThat(tip("main")).isEqualTo(pr.getMergeCommitSha());
                } else {
                    assertThat(pr.getState()).isEqualTo(PrState.CLOSED);
                    assertThat(merged.get().getBody()).containsEntry("code", "not_open");
                    assertThat(tip("main")).isEqualTo(before);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void targetMovedBetweenCheckAndRefUpdateIsRetriedOnTheNewTip() throws Exception {
        RevCommit head = openPr("fix", "target=dev");
        approve(1, 1);
        AtomicInteger moves = new AtomicInteger();
        merges.beforeTargetUpdate = (db, branch) -> {
            if (moves.getAndIncrement() == 0) {
                advance(db, branch);
            }
        };

        ResponseEntity<Map> response = call(HttpMethod.POST, api + "1/merge", carol,
                Map.of("expectedHeadSha", head.name()), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(moves.get()).isEqualTo(2);
        try (Repository db = storage.open(repoId)) {
            RevCommit merged = db.parseCommit(db.resolve("refs/heads/dev"));
            assertThat(db.parseCommit(merged.getParent(0)).getShortMessage()).isEqualTo("concurrent push");
            assertThat(db.resolve("refs/heads/dev:fix.txt")).isNotNull();
        }
    }

    @Test
    void targetThatKeepsMovingGivesUpCleanly() throws Exception {
        RevCommit head = openPr("fix", "target=dev");
        approve(1, 1);
        merges.beforeTargetUpdate = RaceTest::advance;

        ResponseEntity<Map> response = call(HttpMethod.POST, api + "1/merge", carol,
                Map.of("expectedHeadSha", head.name()), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("code", "concurrent_update").containsKey("hint");
        PullRequest pr = pulls.findByRepoIdAndNumber(repoId, 1).orElseThrow();
        assertThat(pr.getState()).isEqualTo(PrState.OPEN);
        assertThat(pr.getMergingCommitSha()).isNull();
    }

    @Test
    void autoMergeNeverMergesAnOutdatedHead() throws Exception {
        try (Git clone = cloneAs(bob, owner, "proj")) {
            commitFile(clone, "fix.txt", "first try");
            push(clone, bob, "HEAD:refs/heads/pr/fix");
            await().until(() -> tasks.idle(repoId));

            tasks.pause(repoId);
            approve(1, 1);
            commitFile(clone, "fix.txt", "second try");
            push(clone, bob, "HEAD:refs/heads/pr/fix");
            tasks.resume(repoId);
            await().until(() -> tasks.idle(repoId));

            PullRequest pr = pulls.findByRepoIdAndNumber(repoId, 1).orElseThrow();
            assertThat(pr.getState()).isEqualTo(PrState.OPEN);
            assertThat(pr.getCurrentRev()).isEqualTo(2);

            approve(1, 2);
            await().until(() -> pulls.findByRepoIdAndNumber(repoId, 1).orElseThrow().getState() == PrState.MERGED);
            try (Repository db = storage.open(repoId)) {
                assertThat(new String(db.open(db.resolve("refs/heads/main:fix.txt")).getBytes()))
                        .isEqualTo("second try");
            }
        }
    }
}

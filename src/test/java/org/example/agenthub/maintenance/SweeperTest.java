package org.example.agenthub.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.example.agenthub.git.GitIntegrationTest;
import org.example.agenthub.pr.Mergeable;
import org.example.agenthub.pr.PrReview;
import org.example.agenthub.pr.PrReviewRepository;
import org.example.agenthub.pr.PrState;
import org.example.agenthub.pr.PullRequest;
import org.example.agenthub.pr.PullRequestRepository;
import org.example.agenthub.pr.PullRequestTasks;
import org.example.agenthub.pr.Verdict;
import org.example.agenthub.repo.RepoStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.transaction.support.TransactionTemplate;

/** §13: the Sweeper picks up what events missed (a full queue, a restart) and finishes failed deletions. */
class SweeperTest extends GitIntegrationTest {

    @Autowired
    Sweeper sweeper;

    @Autowired
    PullRequestTasks tasks;

    @Autowired
    PullRequestRepository pulls;

    @Autowired
    PrReviewRepository reviews;

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

    PullRequest openPr() throws Exception {
        try (Git clone = cloneAs(bob, owner, "proj")) {
            commitFile(clone, "a.txt", "a");
            push(clone, bob, "HEAD:refs/heads/pr/fix");
        }
        await().until(() -> tasks.idle(repoId));
        return pulls.findByRepoIdAndNumber(repoId, 1).orElseThrow();
    }

    @Test
    void unknownMergeabilityIsComputed() throws Exception {
        PullRequest pr = openPr();
        tx.executeWithoutResult(s -> pulls.recordMergeability(pr.getId(), pr.getHeadSha(), Mergeable.UNKNOWN, null,
                null, null));

        sweeper.sweep();

        await().until(() -> pulls.findById(pr.getId()).orElseThrow().getMergeable() == Mergeable.CLEAN);
    }

    @Test
    void approvedPullRequestWhoseTriggerWasLostIsAutoMerged() throws Exception {
        PullRequest pr = openPr();
        reviews.save(new PrReview(pr.getId(), 1, carol, Verdict.APPROVE, null, Instant.now()));

        sweeper.sweep();

        await().until(() -> pulls.findById(pr.getId()).orElseThrow().getState() == PrState.MERGED);
    }

    @Test
    void directoryLeftBehindByAFailedDeletionIsRemoved() throws Exception {
        call(HttpMethod.DELETE, "/v1/repos/" + owner + "/proj", owner, null, Map.class);
        Path dir = storage.dirOf(repoId);
        Files.createDirectories(dir.resolve("objects/pack"));
        Files.writeString(dir.resolve("objects/pack/left-over.pack"), "x");

        sweeper.sweep();

        assertThat(dir).doesNotExist();
    }
}

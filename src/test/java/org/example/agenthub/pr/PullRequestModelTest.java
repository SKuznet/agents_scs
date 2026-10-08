package org.example.agenthub.pr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.example.agenthub.IntegrationTest;
import org.example.agenthub.repo.RepoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/** §7.1 and §11: one open PR per (repo, author, name), numbers unique per repository, optimistic locking. */
class PullRequestModelTest extends IntegrationTest {

    static final String SHA = "0123456789012345678901234567890123456789";

    @Autowired
    PullRequestRepository pulls;

    @Autowired
    RepoRepository repos;

    long repoId;

    @BeforeEach
    void repository() {
        String alice = unique("alice");
        createRepo(alice, "proj");
        repoId = repos.findByOwnerAndNameAndDeletedAtIsNull(alice, "proj").orElseThrow().getId();
    }

    PullRequest pr(int number, String author, String name) {
        return PullRequest.open(repoId, number, name, author, "main", "title", null, false, SHA, Instant.now());
    }

    @Test
    void onlyOneOpenPullRequestPerAuthorAndName() {
        pulls.saveAndFlush(pr(1, "bob", "fix"));

        assertThatThrownBy(() -> pulls.saveAndFlush(pr(2, "bob", "fix")))
                .isInstanceOf(DataIntegrityViolationException.class);
        pulls.saveAndFlush(pr(3, "carol", "fix"));
    }

    @Test
    void closedPullRequestsReleaseTheName() {
        PullRequest first = pulls.saveAndFlush(pr(1, "bob", "fix"));
        first.close(Instant.now());
        pulls.saveAndFlush(first);

        pulls.saveAndFlush(pr(2, "bob", "fix"));

        assertThat(pulls.findOpen(repoId, "bob", "fix")).hasValueSatisfying(p -> assertThat(p.getNumber())
                .isEqualTo(2));
    }

    @Test
    void numbersAreUniquePerRepository() {
        pulls.saveAndFlush(pr(1, "bob", "a"));

        assertThatThrownBy(() -> pulls.saveAndFlush(pr(1, "carol", "b")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void concurrentChangesAreDetected() {
        PullRequest saved = pulls.saveAndFlush(pr(1, "bob", "a"));
        PullRequest first = pulls.findById(saved.getId()).orElseThrow();
        PullRequest second = pulls.findById(saved.getId()).orElseThrow();

        first.setDraft(true);
        pulls.saveAndFlush(first);
        second.setDraft(false);

        assertThatThrownBy(() -> pulls.saveAndFlush(second))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
}

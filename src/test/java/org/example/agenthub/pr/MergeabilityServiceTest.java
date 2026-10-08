package org.example.agenthub.pr;

import static org.assertj.core.api.Assertions.assertThat;

import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.example.agenthub.config.JGitEnvironment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MergeabilityServiceTest {

    InMemoryRepository db;
    TestRepository<InMemoryRepository> repo;
    RevCommit base;
    final MergeabilityService mergeability = new MergeabilityService();

    @BeforeAll
    static void isolateJGit() {
        JGitEnvironment.install();
    }

    @BeforeEach
    void setUp() throws Exception {
        db = new InMemoryRepository(new DfsRepositoryDescription("merge"));
        repo = new TestRepository<>(db);
        base = repo.commit().add("a.txt", "1\n2\n3\n").add("b.txt", "b\n").create();
    }

    @Test
    void divergedButCompatibleChangesMergeCleanlyWithoutStoringObjects() throws Exception {
        RevCommit tip = repo.commit().parent(base).add("tip.txt", "tip\n").create();
        RevCommit head = repo.commit().parent(base).add("head.txt", "head\n").create();
        int packsBefore = db.getObjectDatabase().getPacks().length;

        MergeabilityService.Result result = mergeability.check(db, tip, head);

        assertThat(result.status()).isEqualTo(Mergeable.CLEAN);
        assertThat(result.conflictPaths()).isEmpty();
        assertThat(db.getObjectDatabase().getPacks()).hasSize(packsBefore);
        assertThat(db.getObjectDatabase().has(result.tree())).isFalse();
    }

    @Test
    void conflictingChangesReportThePaths() throws Exception {
        RevCommit tip = repo.commit().parent(base).add("a.txt", "1\ntwo\n3\n").create();
        RevCommit head = repo.commit().parent(base).add("a.txt", "1\nzwei\n3\n").add("c.txt", "c\n").create();

        MergeabilityService.Result result = mergeability.check(db, tip, head);

        assertThat(result.status()).isEqualTo(Mergeable.CONFLICT);
        assertThat(result.conflictPaths()).containsExactly("a.txt");
    }

    @Test
    void unrelatedHistoriesCannotBeMerged() throws Exception {
        RevCommit stranger = repo.commit().add("a.txt", "other\n").create();

        MergeabilityService.Result result = mergeability.check(db, base, stranger);

        assertThat(result.status()).isEqualTo(Mergeable.ERROR);
        assertThat(result.error()).contains("no merge base");
    }

    @Test
    void mergeWithARealInserterYieldsTheMergedTree() throws Exception {
        RevCommit tip = repo.commit().parent(base).add("tip.txt", "tip\n").create();
        RevCommit head = repo.commit().parent(base).add("head.txt", "head\n").create();

        try (var inserter = db.newObjectInserter()) {
            MergeabilityService.Result result = MergeabilityService.merge(db, inserter, tip, head);
            inserter.flush();

            assertThat(result.status()).isEqualTo(Mergeable.CLEAN);
            try (TreeWalk walk = new TreeWalk(db)) {
                walk.addTree(result.tree());
                StringBuilder names = new StringBuilder();
                while (walk.next()) {
                    names.append(walk.getPathString()).append(' ');
                }
                assertThat(names.toString().trim()).isEqualTo("a.txt b.txt head.txt tip.txt");
            }
        }
    }
}

package org.example.agenthub.pr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.example.agenthub.config.JGitEnvironment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DiffServiceTest {

    static final String TEN = "1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n";
    static final String MOVED = "moved content\nline two\nline three\nline four\n";

    InMemoryRepository db;
    TestRepository<InMemoryRepository> repo;
    RevCommit base;
    RevCommit head;
    final DiffService diffs = new DiffService();

    @BeforeAll
    static void isolateJGit() {
        JGitEnvironment.install();
    }

    @BeforeEach
    void setUp() throws Exception {
        db = new InMemoryRepository(new DfsRepositoryDescription("diff"));
        repo = new TestRepository<>(db);
        base = repo.commit()
                .add("a.txt", TEN)
                .add("old.txt", MOVED)
                .add("gone.txt", "bye\n")
                .add("bin.dat", repo.blob(new byte[] {0, 1, 2, 0}))
                .create();
        head = repo.commit().parent(base)
                .add("a.txt", TEN.replace("5\n", "five\n"))
                .rm("old.txt").add("moved.txt", MOVED)
                .rm("gone.txt")
                .add("new.txt", "hello\nworld\n")
                .add("bin.dat", repo.blob(new byte[] {0, 9, 9, 0}))
                .create();
    }

    @Test
    void filesListStatusesRenamesAndCounts() throws Exception {
        List<DiffService.FileChange> files = diffs.files(db, base, head);

        assertThat(files).containsExactlyInAnyOrder(
                new DiffService.FileChange("a.txt", null, "MODIFIED", 1, 1, false),
                new DiffService.FileChange("bin.dat", null, "MODIFIED", 0, 0, true),
                new DiffService.FileChange("gone.txt", null, "DELETED", 0, 1, false),
                new DiffService.FileChange("moved.txt", "old.txt", "RENAMED", 0, 0, false),
                new DiffService.FileChange("new.txt", null, "ADDED", 2, 0, false));
    }

    @Test
    void hunksCarryLineNumbersOfBothSides() throws Exception {
        DiffService.Diff diff = diffs.diff(db, base, head, "a.txt", 1000);

        assertThat(diff.truncated()).isFalse();
        DiffService.FileDiff file = diff.files().getFirst();
        assertThat(file.hunks()).hasSize(1);
        DiffService.Hunk hunk = file.hunks().getFirst();
        assertThat(hunk).extracting(DiffService.Hunk::oldStart, DiffService.Hunk::oldLines,
                DiffService.Hunk::newStart, DiffService.Hunk::newLines).containsExactly(2, 7, 2, 7);
        assertThat(hunk.lines()).containsExactly(
                new DiffService.Line(" ", 2, 2, "2"),
                new DiffService.Line(" ", 3, 3, "3"),
                new DiffService.Line(" ", 4, 4, "4"),
                new DiffService.Line("-", 5, null, "5"),
                new DiffService.Line("+", null, 5, "five"),
                new DiffService.Line(" ", 6, 6, "6"),
                new DiffService.Line(" ", 7, 7, "7"),
                new DiffService.Line(" ", 8, 8, "8"));
    }

    @Test
    void addedFileStartsAtLineOne() throws Exception {
        DiffService.Hunk hunk = diffs.diff(db, base, head, "new.txt", 1000).files().getFirst().hunks().getFirst();

        assertThat(hunk).extracting(DiffService.Hunk::oldStart, DiffService.Hunk::oldLines,
                DiffService.Hunk::newStart, DiffService.Hunk::newLines).containsExactly(0, 0, 1, 2);
        assertThat(hunk.lines()).containsExactly(
                new DiffService.Line("+", null, 1, "hello"),
                new DiffService.Line("+", null, 2, "world"));
    }

    @Test
    void pathFilterMatchesOldNameOfRenames() throws Exception {
        assertThat(diffs.diff(db, base, head, "old.txt", 1000).files()).extracting(DiffService.FileDiff::path)
                .containsExactly("moved.txt");
    }

    @Test
    void binaryFilesHaveNoHunks() throws Exception {
        DiffService.FileDiff bin = diffs.diff(db, base, head, "bin.dat", 1000).files().getFirst();

        assertThat(bin.binary()).isTrue();
        assertThat(bin.hunks()).isEmpty();
    }

    @Test
    void maxLinesTruncatesButKeepsEveryFileEntry() throws Exception {
        DiffService.Diff diff = diffs.diff(db, base, head, null, 3);

        assertThat(diff.truncated()).isTrue();
        assertThat(diff.files()).hasSize(5);
        int lines = diff.files().stream().flatMap(f -> f.hunks().stream()).mapToInt(h -> h.lines().size()).sum();
        assertThat(lines).isEqualTo(3);
    }

    @Test
    void diffStartsAtMergeBaseSoTargetProgressIsNotShownReversed() throws Exception {
        RevCommit targetMoved = repo.commit().parent(base).add("target-only.txt", "x\n").create();

        List<DiffService.FileChange> files = diffs.files(db, targetMoved, head);

        assertThat(files).extracting(DiffService.FileChange::path).doesNotContain("target-only.txt");
    }

    @Test
    void commitsAreThoseAfterTheBase() throws Exception {
        RevCommit second = repo.commit().parent(head).add("x.txt", "x\n").message("second").create();

        assertThat(diffs.commits(db, base, second, 100)).extracting(DiffService.CommitView::sha)
                .containsExactly(second.name(), head.name());
    }

    @Test
    void behindByCountsTargetCommitsMissingFromHead() throws Exception {
        RevCommit t1 = repo.commit().parent(base).add("t1", "1").create();
        RevCommit t2 = repo.commit().parent(t1).add("t2", "2").create();

        assertThat(diffs.behindBy(db, t2, head)).isEqualTo(2);
        assertThat(diffs.behindBy(db, base, head)).isZero();
    }
}

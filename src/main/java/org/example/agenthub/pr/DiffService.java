package org.example.agenthub.pr;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.springframework.stereotype.Component;

/**
 * Files, diff (§10.4) and commits of a PR revision (§7.5): everything starts at merge-base(base, head), so
 * progress of the target since the PR was based is never shown reversed.
 */
@Component
public class DiffService {

    static final int CONTEXT = 3;

    public record FileChange(String path, String oldPath, String status, int additions, int deletions,
            boolean binary) {
    }

    public record Line(String t, Integer o, Integer n, String s) {
    }

    public record Hunk(int oldStart, int oldLines, int newStart, int newLines, List<Line> lines) {
    }

    public record FileDiff(String path, String oldPath, String status, int additions, int deletions, boolean binary,
            List<Hunk> hunks) {
    }

    public record Diff(List<FileDiff> files, boolean truncated) {
    }

    public record CommitView(String sha, List<String> parents, String author, String authorEmail,
            Instant authoredAt, String title, String message) {

        public static CommitView of(RevCommit commit) {
            return new CommitView(commit.name(), Arrays.stream(commit.getParents()).map(RevCommit::name).toList(),
                    commit.getAuthorIdent().getName(), commit.getAuthorIdent().getEmailAddress(),
                    commit.getAuthorIdent().getWhenAsInstant(), commit.getShortMessage(), commit.getFullMessage());
        }
    }

    private record Change(DiffEntry entry, EditList edits, RawText oldText, RawText newText, boolean binary) {

        String path() {
            return entry.getChangeType() == DiffEntry.ChangeType.DELETE ? entry.getOldPath() : entry.getNewPath();
        }

        String oldPath() {
            return switch (entry.getChangeType()) {
                case RENAME, COPY -> entry.getOldPath();
                default -> null;
            };
        }

        String status() {
            return switch (entry.getChangeType()) {
                case ADD -> "ADDED";
                case DELETE -> "DELETED";
                case MODIFY -> "MODIFIED";
                case RENAME -> "RENAMED";
                case COPY -> "COPIED";
            };
        }

        int additions() {
            return edits.stream().mapToInt(Edit::getLengthB).sum();
        }

        int deletions() {
            return edits.stream().mapToInt(Edit::getLengthA).sum();
        }
    }

    public List<FileChange> files(Repository db, AnyObjectId base, AnyObjectId head) throws IOException {
        return changes(db, base, head).stream()
                .map(c -> new FileChange(c.path(), c.oldPath(), c.status(), c.additions(), c.deletions(), c.binary()))
                .toList();
    }

    public Diff diff(Repository db, AnyObjectId base, AnyObjectId head, String path, int maxLines) throws IOException {
        List<FileDiff> files = new ArrayList<>();
        int budget = maxLines;
        boolean truncated = false;
        for (Change change : changes(db, base, head)) {
            if (path != null && !path.equals(change.path()) && !path.equals(change.oldPath())) {
                continue;
            }
            List<Hunk> hunks = new ArrayList<>();
            for (List<Edit> group : change.binary() ? List.<List<Edit>>of() : groups(change.edits())) {
                if (budget == 0) {
                    truncated = true;
                    break;
                }
                Hunk hunk = hunk(change, group, budget);
                hunks.add(hunk);
                budget -= hunk.lines().size();
                truncated |= hunk.lines().size() < fullLength(change, group);
            }
            files.add(new FileDiff(change.path(), change.oldPath(), change.status(), change.additions(),
                    change.deletions(), change.binary(), hunks));
        }
        return new Diff(files, truncated);
    }

    public List<CommitView> commits(Repository db, AnyObjectId base, AnyObjectId head, int limit) throws IOException {
        try (RevWalk walk = new RevWalk(db)) {
            walk.sort(RevSort.TOPO);
            walk.sort(RevSort.COMMIT_TIME_DESC, true);
            walk.markStart(walk.parseCommit(head));
            walk.markUninteresting(walk.parseCommit(base));
            List<CommitView> commits = new ArrayList<>();
            for (RevCommit commit : walk) {
                if (commits.size() == limit) {
                    break;
                }
                commits.add(CommitView.of(commit));
            }
            return commits;
        }
    }

    /** Commits of the target tip that the head does not contain. */
    public int behindBy(Repository db, AnyObjectId tip, AnyObjectId head) throws IOException {
        try (RevWalk walk = new RevWalk(db)) {
            walk.markStart(walk.parseCommit(tip));
            walk.markUninteresting(walk.parseCommit(head));
            int count = 0;
            while (walk.next() != null) {
                count++;
            }
            return count;
        }
    }

    private List<Change> changes(Repository db, AnyObjectId base, AnyObjectId head) throws IOException {
        ObjectId from = base == null ? null : MergeabilityService.mergeBase(db, base, head);
        try (RevWalk walk = new RevWalk(db);
                DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            formatter.setRepository(db);
            formatter.setDetectRenames(true);
            List<DiffEntry> entries = formatter.scan(from == null ? null : walk.parseCommit(from).getTree(),
                    walk.parseCommit(head).getTree());
            List<Change> changes = new ArrayList<>();
            for (DiffEntry entry : entries) {
                FileHeader header = formatter.toFileHeader(entry);
                boolean binary = header.getPatchType() != FileHeader.PatchType.UNIFIED;
                changes.add(binary
                        ? new Change(entry, new EditList(), RawText.EMPTY_TEXT, RawText.EMPTY_TEXT, true)
                        : new Change(entry, header.toEditList(), text(db, entry.getOldId().toObjectId()),
                                text(db, entry.getNewId().toObjectId()), false));
            }
            changes.sort(Comparator.comparing(Change::path));
            return changes;
        }
    }

    private static RawText text(Repository db, ObjectId blob) throws IOException {
        if (blob == null || ObjectId.zeroId().equals(blob)) {
            return RawText.EMPTY_TEXT;
        }
        return new RawText(db.open(blob, Constants.OBJ_BLOB).getCachedBytes(Integer.MAX_VALUE));
    }

    /** Edits closer than two contexts apart share a hunk, as in {@code git diff}. */
    private static List<List<Edit>> groups(EditList edits) {
        List<List<Edit>> groups = new ArrayList<>();
        for (Edit edit : edits) {
            List<Edit> current = groups.isEmpty() ? null : groups.getLast();
            if (current == null || edit.getBeginA() - current.getLast().getEndA() > 2 * CONTEXT) {
                current = new ArrayList<>();
                groups.add(current);
            }
            current.add(edit);
        }
        return groups;
    }

    private static int[] bounds(Change change, List<Edit> group) {
        Edit first = group.getFirst();
        Edit last = group.getLast();
        return new int[] {
            Math.max(0, first.getBeginA() - CONTEXT), Math.min(change.oldText().size(), last.getEndA() + CONTEXT),
            Math.max(0, first.getBeginB() - CONTEXT), Math.min(change.newText().size(), last.getEndB() + CONTEXT)};
    }

    private static int fullLength(Change change, List<Edit> group) {
        int[] b = bounds(change, group);
        int inserted = group.stream().mapToInt(Edit::getLengthB).sum();
        return (b[1] - b[0]) + inserted;
    }

    private static Hunk hunk(Change change, List<Edit> group, int budget) {
        int[] b = bounds(change, group);
        RawText oldText = change.oldText();
        RawText newText = change.newText();
        List<Line> lines = new ArrayList<>();
        int a = b[0];
        int n = b[2];
        for (Edit edit : group) {
            for (; a < edit.getBeginA() && lines.size() < budget; a++, n++) {
                lines.add(new Line(" ", a + 1, n + 1, oldText.getString(a)));
            }
            for (int i = edit.getBeginA(); i < edit.getEndA() && lines.size() < budget; i++) {
                lines.add(new Line("-", i + 1, null, oldText.getString(i)));
            }
            for (int i = edit.getBeginB(); i < edit.getEndB() && lines.size() < budget; i++) {
                lines.add(new Line("+", null, i + 1, newText.getString(i)));
            }
            a = edit.getEndA();
            n = edit.getEndB();
        }
        for (; a < b[1] && lines.size() < budget; a++, n++) {
            lines.add(new Line(" ", a + 1, n + 1, oldText.getString(a)));
        }
        int oldLines = b[1] - b[0];
        int newLines = b[3] - b[2];
        return new Hunk(oldLines == 0 ? b[0] : b[0] + 1, oldLines, newLines == 0 ? b[2] : b[2] + 1, newLines, lines);
    }
}

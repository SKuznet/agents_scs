package org.example.agenthub.content;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.example.agenthub.access.RepoAccessService;
import org.example.agenthub.access.Role;
import org.example.agenthub.pr.DiffService;
import org.example.agenthub.pr.MergeabilityService;
import org.example.agenthub.repo.Repo;
import org.example.agenthub.repo.RepoStorage;
import org.example.agenthub.web.ErrorCode;
import org.example.agenthub.web.HintedException;
import org.example.agenthub.web.Paged;
import org.springframework.stereotype.Service;

/** Read side of the content API (§10.5): files, folders, history and comparisons, all for READ members. */
@Service
public class ContentService {

    /** Larger files are not inlined into JSON; {@code raw=true} returns them. */
    static final int MAX_INLINE = 1024 * 1024;
    static final int DEFAULT_DIFF_LINES = 2000;
    static final int MAX_DIFF_LINES = 20000;
    static final int MAX_COMMITS = 250;
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");

    private final RepoAccessService access;
    private final RepoStorage storage;
    private final DiffService diffs;

    public ContentService(RepoAccessService access, RepoStorage storage, DiffService diffs) {
        this.access = access;
        this.storage = storage;
        this.diffs = diffs;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Content(String type, String path, Long size, String sha, String encoding, String content,
            Boolean truncated, List<Entry> entries) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entry(String name, String path, String type, Long size, String sha) {
    }

    public record CommitDetails(DiffService.CommitView commit, List<DiffService.FileChange> files,
            DiffService.Diff diff) {
    }

    public record Comparison(String baseSha, String headSha, String mergeBaseSha, int aheadBy, int behindBy,
            List<DiffService.CommitView> commits, List<DiffService.FileChange> files, DiffService.Diff diff) {
    }

    public Content contents(String agent, String owner, String name, String path, String ref) {
        Repo repo = access.require(agent, owner, name, Role.READ).repo();
        return read(repo, db -> {
            try (RevWalk walk = new RevWalk(db)) {
                RevCommit commit = walk.parseCommit(resolve(db, repo, ref));
                return path.isEmpty() ? folder(db, "", commit.getTree()) : entry(db, repo, path, ref, commit);
            }
        });
    }

    public byte[] raw(String agent, String owner, String name, String path, String ref) {
        Repo repo = access.require(agent, owner, name, Role.READ).repo();
        return read(repo, db -> {
            RevTree tree;
            try (RevWalk walk = new RevWalk(db)) {
                tree = walk.parseCommit(resolve(db, repo, ref)).getTree();
            }
            try (TreeWalk file = TreeWalk.forPath(db, path, tree)) {
                if (file == null || file.getFileMode(0).getObjectType() != Constants.OBJ_BLOB) {
                    throw new HintedException(ErrorCode.VALIDATION_FAILED, "'" + path + "' is not a file",
                            "list the folder without raw=true: GET " + contents(repo, path, ref));
                }
                try (InputStream in = db.open(file.getObjectId(0), Constants.OBJ_BLOB).openStream()) {
                    return in.readAllBytes();
                }
            }
        });
    }

    public Paged<DiffService.CommitView> commits(String agent, String owner, String name, String ref, String path,
            String cursor, Integer limit) {
        Repo repo = access.require(agent, owner, name, Role.READ).repo();
        int size = Paged.limit(limit);
        int offset = parseCursor(cursor);
        return read(repo, db -> {
            try (RevWalk walk = new RevWalk(db)) {
                walk.sort(RevSort.TOPO);
                walk.sort(RevSort.COMMIT_TIME_DESC, true);
                walk.markStart(walk.parseCommit(resolve(db, repo, ref)));
                if (path != null && !path.isEmpty()) {
                    walk.setTreeFilter(AndTreeFilter.create(PathFilter.create(path), TreeFilter.ANY_DIFF));
                }
                List<Numbered> rows = new ArrayList<>();
                int index = 0;
                for (RevCommit commit : walk) {
                    if (index++ < offset) {
                        continue;
                    }
                    rows.add(new Numbered(index, DiffService.CommitView.of(commit)));
                    if (rows.size() > size) {
                        break;
                    }
                }
                return Paged.of(rows, size, row -> String.valueOf(row.next()), Numbered::commit);
            }
        });
    }

    public CommitDetails commit(String agent, String owner, String name, String sha, Integer maxLines) {
        Repo repo = access.require(agent, owner, name, Role.READ).repo();
        return read(repo, db -> {
            try (RevWalk walk = new RevWalk(db)) {
                RevCommit commit = walk.parseCommit(objectId(repo, sha));
                RevCommit parent = commit.getParentCount() == 0 ? null : commit.getParent(0);
                return new CommitDetails(DiffService.CommitView.of(commit), diffs.files(db, parent, commit),
                        diffs.diff(db, parent, commit, null, lines(maxLines)));
            }
        });
    }

    public Comparison compare(String agent, String owner, String name, String base, String head, Integer maxLines) {
        Repo repo = access.require(agent, owner, name, Role.READ).repo();
        return read(repo, db -> {
            ObjectId baseId = resolve(db, repo, base);
            ObjectId headId = resolve(db, repo, head);
            ObjectId mergeBase = MergeabilityService.mergeBase(db, baseId, headId);
            if (mergeBase == null) {
                throw new HintedException(ErrorCode.UNRELATED_HISTORY, "'" + base + "' and '" + head
                        + "' share no history", "compare refs of the same history; see their commits with"
                        + " GET /v1/repos/" + repo.getFullName() + "/commits?ref=<ref>");
            }
            return new Comparison(baseId.name(), headId.name(), mergeBase.name(), diffs.behindBy(db, headId, baseId),
                    diffs.behindBy(db, baseId, headId), diffs.commits(db, baseId, headId, MAX_COMMITS),
                    diffs.files(db, baseId, headId), diffs.diff(db, baseId, headId, null, lines(maxLines)));
        });
    }

    private record Numbered(int next, DiffService.CommitView commit) {
    }

    private Content entry(Repository db, Repo repo, String path, String ref, RevCommit commit) throws IOException {
        try (TreeWalk walk = TreeWalk.forPath(db, path, commit.getTree())) {
            if (walk == null) {
                throw new HintedException(ErrorCode.NOT_FOUND, "'" + path + "' not found at " + commit.name(),
                        "list the folder: GET " + contents(repo, parentOf(path), ref));
            }
            FileMode mode = walk.getFileMode(0);
            ObjectId id = walk.getObjectId(0);
            if (mode == FileMode.TREE) {
                return folder(db, path, id);
            }
            if (mode == FileMode.GITLINK) {
                return new Content("submodule", path, null, id.name(), null, null, null, null);
            }
            return file(db, path, mode == FileMode.SYMLINK ? "symlink" : "file", id);
        }
    }

    private static Content file(Repository db, String path, String type, ObjectId id) throws IOException {
        ObjectLoader blob = db.open(id, Constants.OBJ_BLOB);
        long size = blob.getSize();
        if (size > MAX_INLINE) {
            return new Content(type, path, size, id.name(), null, null, true, null);
        }
        byte[] bytes = blob.getCachedBytes(MAX_INLINE);
        String text = RawText.isBinary(bytes) ? null : utf8(bytes);
        return text != null
                ? new Content(type, path, size, id.name(), "utf-8", text, false, null)
                : new Content(type, path, size, id.name(), "base64", Base64.getEncoder().encodeToString(bytes), false,
                        null);
    }

    private static Content folder(Repository db, String path, ObjectId tree) throws IOException {
        List<Entry> entries = new ArrayList<>();
        try (TreeWalk walk = new TreeWalk(db)) {
            walk.addTree(tree);
            while (walk.next()) {
                FileMode mode = walk.getFileMode(0);
                String child = path.isEmpty() ? walk.getNameString() : path + "/" + walk.getNameString();
                String type = mode == FileMode.TREE ? "dir" : mode == FileMode.GITLINK ? "submodule"
                        : mode == FileMode.SYMLINK ? "symlink" : "file";
                Long size = type.equals("file")
                        ? walk.getObjectReader().getObjectSize(walk.getObjectId(0), Constants.OBJ_BLOB) : null;
                entries.add(new Entry(walk.getNameString(), child, type, size, walk.getObjectId(0).name()));
            }
        }
        return new Content("dir", path, null, tree.name(), null, null, null, entries);
    }

    private static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /** A branch, a tag, a full ref such as {@code refs/pull/7/head}, or a commit sha; default: the default branch. */
    static ObjectId resolve(Repository db, Repo repo, String ref) throws IOException {
        String wanted = ref == null || ref.isEmpty() ? repo.getDefaultBranch() : ref;
        for (String candidate : List.of(Constants.R_HEADS + wanted, Constants.R_TAGS + wanted, wanted)) {
            if (candidate.startsWith(Constants.R_REFS) && Repository.isValidRefName(candidate)) {
                Ref found = db.exactRef(candidate);
                if (found != null) {
                    return found.getObjectId();
                }
            }
        }
        if (SHA.matcher(wanted).matches() && db.getObjectDatabase().has(ObjectId.fromString(wanted))) {
            return ObjectId.fromString(wanted);
        }
        throw new HintedException(ErrorCode.NOT_FOUND, "ref '" + wanted + "' not found in " + repo.getFullName(),
                "use a branch (GET /v1/repos/" + repo.getFullName() + "/branches), a tag or a commit sha");
    }

    private static ObjectId objectId(Repo repo, String sha) {
        if (!SHA.matcher(sha).matches()) {
            throw new HintedException(ErrorCode.VALIDATION_FAILED, "'" + sha + "' is not a full commit sha",
                    "pass the 40-character sha from GET /v1/repos/" + repo.getFullName() + "/commits");
        }
        return ObjectId.fromString(sha);
    }

    private static int lines(Integer maxLines) {
        return maxLines == null ? DEFAULT_DIFF_LINES : Math.max(1, Math.min(MAX_DIFF_LINES, maxLines));
    }

    private static String contents(Repo repo, String path, String ref) {
        return "/v1/repos/" + repo.getFullName() + "/contents" + (path.isEmpty() ? "" : "/" + path)
                + (ref == null ? "" : "?ref=" + ref);
    }

    private static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static int parseCursor(String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(cursor));
        } catch (NumberFormatException e) {
            throw new HintedException(ErrorCode.VALIDATION_FAILED, "invalid cursor",
                    "pass 'nextCursor' from the previous page unchanged");
        }
    }

    private interface GitRead<T> {
        T apply(Repository db) throws IOException;
    }

    private <T> T read(Repo repo, GitRead<T> read) {
        try (Repository db = storage.open(repo.getId())) {
            return read.apply(db);
        } catch (MissingObjectException | IncorrectObjectTypeException e) {
            throw new HintedException(ErrorCode.NOT_FOUND, "no such commit in " + repo.getFullName(),
                    "list commits: GET /v1/repos/" + repo.getFullName() + "/commits");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

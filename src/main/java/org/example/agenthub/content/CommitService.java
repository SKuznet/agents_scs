package org.example.agenthub.content;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectChecker;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.example.agenthub.access.RepoAccess;
import org.example.agenthub.access.RepoAccessService;
import org.example.agenthub.access.Role;
import org.example.agenthub.config.AgentHubProperties;
import org.example.agenthub.git.PostReceiveHandler;
import org.example.agenthub.git.RepoLocks;
import org.example.agenthub.pr.Provenance;
import org.example.agenthub.pr.PullRequest;
import org.example.agenthub.pr.PullRequestRepository;
import org.example.agenthub.pr.PullRequestService;
import org.example.agenthub.pr.PullRequestViews;
import org.example.agenthub.pr.PushOptions;
import org.example.agenthub.repo.BranchProtectionRepository;
import org.example.agenthub.repo.BranchProtectionService;
import org.example.agenthub.repo.Identities;
import org.example.agenthub.repo.NamePolicy;
import org.example.agenthub.repo.Repo;
import org.example.agenthub.repo.RepoStorage;
import org.example.agenthub.web.ErrorCode;
import org.example.agenthub.web.HintedException;
import org.springframework.stereotype.Service;

/**
 * Commits the server writes from files (§10.5 {@code POST /commits}, §10.3 {@code POST /pulls}): the agent is the
 * author, {@code agenthub} the committer, and the same rules apply as to a push.
 */
@Service
public class CommitService {

    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");

    private final RepoAccessService access;
    private final RepoStorage storage;
    private final RepoLocks locks;
    private final BranchProtectionRepository protections;
    private final Identities identities;
    private final PostReceiveHandler postReceive;
    private final PullRequestService pullRequests;
    private final PullRequestRepository pulls;
    private final PullRequestViews views;
    private final AgentHubProperties.Limits limits;

    public CommitService(RepoAccessService access, RepoStorage storage, RepoLocks locks,
            BranchProtectionRepository protections, Identities identities, PostReceiveHandler postReceive,
            PullRequestService pullRequests, PullRequestRepository pulls, PullRequestViews views,
            AgentHubProperties properties) {
        this.access = access;
        this.storage = storage;
        this.locks = locks;
        this.protections = protections;
        this.identities = identities;
        this.postReceive = postReceive;
        this.pullRequests = pullRequests;
        this.pulls = pulls;
        this.views = views;
        this.limits = properties.limits();
    }

    /** {@code content} is text unless {@code encoding} is {@code base64}; {@code delete: true} removes the path. */
    public record FileChange(String path, String content, String encoding, Boolean delete) {

        boolean deletes() {
            return Boolean.TRUE.equals(delete);
        }
    }

    public record CommitRequest(String branch, String parentSha, String message, List<FileChange> files) {
    }

    public record Committed(String branch, String sha, String parentSha) {
    }

    public record ProposeRequest(String name, String target, String baseSha, String title, String description,
            List<FileChange> files, List<String> options) {
    }

    public record Proposed(PullRequestViews.Details pull, List<String> messages, @JsonIgnore boolean created) {
    }

    /** §10.5: a WRITE member commits onto an unprotected branch whose tip is still {@code parentSha}. */
    public Committed commit(Provenance by, String owner, String name, CommitRequest request) {
        RepoAccess granted = access.require(by.agent(), owner, name, Role.READ);
        Repo repo = granted.repo();
        String proposals = "/v1/repos/" + repo.getFullName() + "/pulls";
        if (!granted.role().atLeast(Role.WRITE)) {
            throw new HintedException(ErrorCode.INSUFFICIENT_ROLE, "committing needs WRITE; you have "
                    + granted.role(), "propose the change as a pull request: POST " + proposals
                    + " {\"name\": ..., \"baseSha\": ..., \"files\": [...]}");
        }
        String branch = request.branch();
        if (branch == null || request.message() == null || request.message().isBlank()) {
            throw new HintedException(ErrorCode.VALIDATION_FAILED, "'branch', 'parentSha', 'message' and 'files' are"
                    + " required", "send {\"branch\": ..., \"parentSha\": <tip>, \"message\": ..., \"files\": [...]}");
        }
        if (branch.equals("pr") || branch.startsWith("pr/")) {
            throw new HintedException(ErrorCode.RESERVED_REF, "pull request branches are virtual",
                    "open or update a pull request: POST " + proposals);
        }
        String problem = NamePolicy.refNameProblem(branch);
        if (problem != null) {
            throw new HintedException(ErrorCode.INVALID_REF_NAME, "invalid branch name '" + branch + "': " + problem,
                    "commit to an existing branch");
        }
        String parentSha = requireSha("parentSha", request.parentSha(), repo);
        checkFiles(request.files());
        checkQuota(repo);
        if (BranchProtectionService.effective(protections, repo.getId(), branch).isRequirePr()) {
            throw new HintedException(ErrorCode.PROTECTED_BRANCH, "direct commits to '" + branch + "' are not"
                    + " allowed; changes land through pull requests", "propose the change: POST " + proposals
                    + " {\"name\": ..., \"target\": \"" + branch + "\", \"baseSha\": \"" + parentSha
                    + "\", \"files\": [...]}");
        }
        ObjectId parent = ObjectId.fromString(parentSha);
        ObjectId created = locks.withLock(repo.getId(), () -> write(repo, db -> {
            Ref tip = db.exactRef(Constants.R_HEADS + branch);
            if (tip == null) {
                throw new HintedException(ErrorCode.NOT_FOUND, "branch '" + branch + "' not found in "
                        + repo.getFullName(), "commit to an existing branch (GET /v1/repos/" + repo.getFullName()
                        + "/branches); new branches are created with git push");
            }
            if (!tip.getObjectId().equals(parent)) {
                throw stale(repo, branch, parentSha, tip.getObjectId());
            }
            try (RevWalk walk = new RevWalk(db); ObjectInserter inserter = db.newObjectInserter()) {
                RevCommit parentCommit = walk.parseCommit(parent);
                ObjectId tree = tree(db, inserter, parentCommit, request.files());
                if (tree.equals(parentCommit.getTree())) {
                    throw new HintedException(ErrorCode.NO_EFFECTIVE_CHANGES, "'" + branch + "' already has these"
                            + " files", "nothing to commit; compare with GET /v1/repos/" + repo.getFullName()
                            + "/contents/<path>?ref=" + branch);
                }
                ObjectId commit = insertCommit(inserter, tree, parent, by.agent(), request.message());
                RefUpdate update = db.updateRef(Constants.R_HEADS + branch);
                update.setExpectedOldObjectId(parent);
                update.setNewObjectId(commit);
                update.setRefLogIdent(identities.agent(by.agent()));
                update.setRefLogMessage("commit via API", false);
                RefUpdate.Result result = update.update(walk);
                if (result != RefUpdate.Result.FAST_FORWARD) {
                    Ref now = db.exactRef(Constants.R_HEADS + branch);
                    throw stale(repo, branch, parentSha, now == null ? ObjectId.zeroId() : now.getObjectId());
                }
                return commit;
            }
        }));
        postReceive.updated(repo.getId(), by, List.of(new ReceiveCommand(parent, created,
                Constants.R_HEADS + branch, ReceiveCommand.Type.UPDATE)));
        return new Committed(branch, created.name(), parentSha);
    }

    /**
     * §10.3: a pull request from files, committed onto {@code baseSha} and handed to the same {@code openOrUpdate}
     * as a push. The same files on the same base again reuse the current head, so a retry is "unchanged".
     */
    public Proposed propose(Provenance by, String owner, String name, ProposeRequest request) {
        RepoAccess granted = access.require(by.agent(), owner, name, Role.READ);
        Repo repo = granted.repo();
        if (!granted.role().atLeast(Role.PROPOSE)) {
            throw RepoAccessService.insufficientRole(repo, by.agent(), granted.role(), Role.PROPOSE);
        }
        String pullName = request.name();
        if (pullName == null) {
            throw new HintedException(ErrorCode.VALIDATION_FAILED, "'name', 'baseSha' and 'files' are required",
                    "send {\"name\": \"fix-parser\", \"baseSha\": <tip of the target>, \"files\": [...]}");
        }
        String problem = NamePolicy.refNameProblem(pullName);
        if (problem != null) {
            throw new HintedException(ErrorCode.INVALID_REF_NAME, "invalid pull request name: " + problem,
                    "pick a name like fix-parser");
        }
        PushOptions options = PushOptions.forRequest(request.options(), request.title(), request.description(),
                request.target(), limits.maxPushOptions(), limits.maxPushOptionSize().toBytes());
        String baseSha = requireSha("baseSha", request.baseSha(), repo);
        checkFiles(request.files());
        checkQuota(repo);
        PullRequestService.Outcome outcome = locks.withLock(repo.getId(), () -> write(repo, db -> {
            ObjectId head;
            try (RevWalk walk = new RevWalk(db); ObjectInserter inserter = db.newObjectInserter()) {
                RevCommit base = parseBase(walk, repo, baseSha);
                ObjectId tree = tree(db, inserter, base, request.files());
                if (tree.equals(base.getTree())) {
                    throw new HintedException(ErrorCode.NO_EFFECTIVE_CHANGES, "the files are already like this in "
                            + baseSha, "change at least one file");
                }
                head = reusableHead(db, walk, repo, by.agent(), pullName, base, tree);
                if (head == null) {
                    String message = request.title() != null ? request.title() : pullName;
                    head = insertCommit(inserter, tree, base, by.agent(), request.description() == null ? message
                            : message + "\n\n" + request.description());
                }
            }
            return pullRequests.openOrUpdate(db, repo.getId(), granted.role(), by, pullName, head, options);
        }));
        PullRequest pr = pulls.findById(outcome.pr().getId()).orElseThrow();
        return new Proposed(views.details(repo, pr), outcome.lines(), outcome.created());
    }

    private ObjectId reusableHead(Repository db, RevWalk walk, Repo repo, String agent, String name, RevCommit base,
            ObjectId tree) throws IOException {
        PullRequest open = pulls.findOpen(repo.getId(), agent, name).orElse(null);
        if (open == null) {
            return null;
        }
        RevCommit head = walk.parseCommit(ObjectId.fromString(open.getHeadSha()));
        boolean same = head.getParentCount() == 1 && head.getParent(0).equals(base) && head.getTree().equals(tree);
        return same ? head : null;
    }

    private static RevCommit parseBase(RevWalk walk, Repo repo, String sha) throws IOException {
        try {
            return walk.parseCommit(ObjectId.fromString(sha));
        } catch (MissingObjectException | IncorrectObjectTypeException e) {
            throw new HintedException(ErrorCode.VALIDATION_FAILED, "baseSha " + sha + " is not a commit of "
                    + repo.getFullName(), "use the tip of the target: GET /v1/repos/" + repo.getFullName()
                    + "/branches");
        }
    }

    /** The tree of {@code parent} with the changes applied; a path may not turn a file into a folder or back. */
    private ObjectId tree(Repository db, ObjectInserter inserter, RevCommit parent, List<FileChange> files)
            throws IOException {
        DirCache index = DirCache.newInCore();
        DirCacheBuilder builder = index.builder();
        try (ObjectReader reader = db.newObjectReader()) {
            builder.addTree(new byte[0], DirCacheEntry.STAGE_0, reader, parent.getTree());
        }
        builder.finish();
        Set<String> paths = new HashSet<>();
        for (int i = 0; i < index.getEntryCount(); i++) {
            paths.add(index.getEntry(i).getPathString());
        }
        DirCacheEditor editor = index.editor();
        for (FileChange file : files) {
            String path = file.path();
            if (file.deletes()) {
                if (!paths.remove(path)) {
                    throw invalid("'" + path + "' does not exist in " + parent.name(), "delete only existing files");
                }
                editor.add(new DirCacheEditor.DeletePath(path));
                continue;
            }
            checkFolders(paths, path);
            paths.add(path);
            byte[] bytes = bytesOf(file);
            ObjectId blob = inserter.insert(Constants.OBJ_BLOB, bytes);
            editor.add(new DirCacheEditor.PathEdit(path) {
                @Override
                public void apply(DirCacheEntry entry) {
                    entry.setFileMode(entry.getFileMode() == FileMode.EXECUTABLE_FILE ? FileMode.EXECUTABLE_FILE
                            : FileMode.REGULAR_FILE);
                    entry.setObjectId(blob);
                    entry.setLength(bytes.length);
                }
            });
        }
        editor.finish();
        return index.writeTree(inserter);
    }

    private static void checkFolders(Set<String> paths, String path) {
        for (int slash = path.indexOf('/'); slash > 0; slash = path.indexOf('/', slash + 1)) {
            String folder = path.substring(0, slash);
            if (paths.contains(folder)) {
                throw invalid("'" + folder + "' is a file, so '" + path + "' cannot be created",
                        "delete '" + folder + "' in the same request first");
            }
        }
        String prefix = path + "/";
        if (paths.stream().anyMatch(existing -> existing.startsWith(prefix))) {
            throw invalid("'" + path + "' is a folder", "write files inside it, e.g. '" + prefix + "...'");
        }
    }

    private ObjectId insertCommit(ObjectInserter inserter, ObjectId tree, ObjectId parent, String agent,
            String message) throws IOException {
        CommitBuilder commit = new CommitBuilder();
        commit.setTreeId(tree);
        commit.setParentId(parent);
        commit.setAuthor(identities.agent(agent));
        commit.setCommitter(identities.server());
        commit.setMessage(message);
        ObjectId id = inserter.insert(commit);
        inserter.flush();
        return id;
    }

    private static void checkFiles(List<FileChange> files) {
        if (files == null || files.isEmpty()) {
            throw invalid("'files' must list at least one change",
                    "send \"files\": [{\"path\": \"src/a.txt\", \"content\": \"...\"}, {\"path\": \"old.txt\","
                            + " \"delete\": true}]");
        }
        Set<String> seen = new HashSet<>();
        for (FileChange file : files) {
            String path = file.path();
            String problem = pathProblem(path);
            if (problem != null) {
                throw invalid("invalid path '" + path + "': " + problem,
                        "use a relative path with '/' separators, like src/main/App.java");
            }
            if (!seen.add(path)) {
                throw invalid("'" + path + "' is listed twice", "list every path once");
            }
            if (file.deletes() == (file.content() != null)) {
                throw invalid("'" + path + "' needs either 'content' or \"delete\": true", "send one of them");
            }
            if (file.encoding() != null && !file.encoding().equals("utf-8") && !file.encoding().equals("base64")) {
                throw invalid("unknown encoding '" + file.encoding() + "'", "use \"utf-8\" (default) or \"base64\"");
            }
        }
    }

    private static String pathProblem(String path) {
        if (path == null || path.isEmpty()) {
            return "the path is empty";
        }
        if (path.startsWith("/") || path.endsWith("/") || path.contains("\\")) {
            return "no leading or trailing '/', and '/' instead of '\\'";
        }
        try {
            new ObjectChecker().setSafeForWindows(true).setSafeForMacOS(true).checkPath(path);
            return null;
        } catch (CorruptObjectException e) {
            return e.getMessage();
        }
    }

    private static byte[] bytesOf(FileChange file) {
        if (!"base64".equals(file.encoding())) {
            return file.content().getBytes(StandardCharsets.UTF_8);
        }
        try {
            return Base64.getDecoder().decode(file.content());
        } catch (IllegalArgumentException e) {
            throw invalid("content of '" + file.path() + "' is not valid base64", "encode the bytes with base64");
        }
    }

    private void checkQuota(Repo repo) {
        if (repo.getSizeBytes() >= limits.maxRepoSize().toBytes()) {
            throw new HintedException(ErrorCode.QUOTA_EXCEEDED, repo.getFullName() + " is over its size quota; only"
                    + " deletions are accepted", "delete branches or tags you no longer need:"
                    + " git push origin :refs/heads/<branch>");
        }
    }

    private static String requireSha(String field, String value, Repo repo) {
        if (value == null || !SHA.matcher(value).matches()) {
            throw invalid("'" + field + "' must be a full 40-character commit sha",
                    "take it from GET /v1/repos/" + repo.getFullName() + "/branches");
        }
        return value;
    }

    private static HintedException stale(Repo repo, String branch, String expected, ObjectId current) {
        return new HintedException(ErrorCode.STALE_HEAD, "'" + branch + "' is at " + current.name() + ", not at "
                + expected, "re-read the files (GET /v1/repos/" + repo.getFullName() + "/contents/<path>?ref="
                + branch + ") and commit again with \"parentSha\": \"" + current.name() + "\"");
    }

    private static HintedException invalid(String detail, String hint) {
        return new HintedException(ErrorCode.VALIDATION_FAILED, detail, hint);
    }

    private interface GitWrite<T> {
        T apply(Repository db) throws IOException;
    }

    private <T> T write(Repo repo, GitWrite<T> write) {
        try (Repository db = storage.open(repo.getId())) {
            return write.apply(db);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

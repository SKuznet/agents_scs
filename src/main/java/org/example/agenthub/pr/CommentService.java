package org.example.agenthub.pr;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.example.agenthub.access.RepoAccess;
import org.example.agenthub.access.RepoAccessService;
import org.example.agenthub.access.Role;
import org.example.agenthub.config.AgentHubProperties;
import org.example.agenthub.events.DomainEvent;
import org.example.agenthub.repo.Repo;
import org.example.agenthub.repo.RepoStorage;
import org.example.agenthub.web.ErrorCode;
import org.example.agenthub.web.HintedException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Comments of §7.6: {@code line} is a line number in the file of the revision, on the NEW side (the head) or the
 * OLD side (the file at the merge-base); replies refer to their parent and are shown in the thread of its root.
 */
@Service
public class CommentService {

    private final RepoAccessService access;
    private final PullRequestRepository pulls;
    private final PrRevisionRepository revisions;
    private final PrCommentRepository comments;
    private final RepoStorage storage;
    private final DiffService diffs;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final long maxBody;

    public CommentService(RepoAccessService access, PullRequestRepository pulls, PrRevisionRepository revisions,
            PrCommentRepository comments, RepoStorage storage, DiffService diffs, TransactionTemplate tx,
            ApplicationEventPublisher events, Clock clock, AgentHubProperties properties) {
        this.access = access;
        this.pulls = pulls;
        this.revisions = revisions;
        this.comments = comments;
        this.storage = storage;
        this.diffs = diffs;
        this.tx = tx;
        this.events = events;
        this.clock = clock;
        this.maxBody = properties.limits().maxCommentSize().toBytes();
    }

    public record CommentRequest(Integer rev, String path, Integer line, String side, String body, Long parentId) {
    }

    /** A line comment inside a review; its revision is the review's. */
    public record InlineComment(String path, Integer line, String side, String body) {
    }

    public record CommentView(long id, int rev, String author, String path, Integer line, Side side, String body,
            Long parentId, Instant createdAt, List<CommentView> replies) {

        static CommentView of(PrComment c, List<CommentView> replies) {
            return new CommentView(c.getId(), c.getRev(), c.getAuthor(), c.getPath(), c.getLine(), c.getSide(),
                    c.getBody(), c.getParentId(), c.getCreatedAt(), replies);
        }
    }

    record Anchor(String path, Integer line, Side side) {
    }

    public CommentView comment(String agent, String owner, String name, int number, CommentRequest request) {
        RepoAccess granted = access.require(agent, owner, name, Role.READ);
        Repo repo = granted.repo();
        if (!granted.role().atLeast(Role.PROPOSE)) {
            throw RepoAccessService.insufficientRole(repo, agent, granted.role(), Role.PROPOSE);
        }
        if (request.rev() == null) {
            throw new HintedException(ErrorCode.VALIDATION_FAILED, "'rev' is required",
                    "send the revision you comment on, e.g. the currentRev of the PR");
        }
        checkBody(request.body());
        PullRequest pr = pulls.require(repo, number);
        PrRevision revision = revision(repo, pr, request.rev());
        if (request.parentId() != null && comments.findById(request.parentId())
                .filter(parent -> parent.getPrId().equals(pr.getId())).isEmpty()) {
            throw new HintedException(ErrorCode.VALIDATION_FAILED, "comment " + request.parentId()
                    + " is not a comment of PR #" + number, "reply with the id of a comment from GET "
                    + PullRequestService.api(repo, pr) + "/comments");
        }
        Anchor anchor = anchor(repo, pr, revision, request.path(), request.line(), request.side());
        PrComment saved = tx.execute(status -> save(repo, pr, revision.getRev(), agent, anchor, request.body(),
                request.parentId()));
        return CommentView.of(saved, List.of());
    }

    public List<CommentView> threads(String agent, String owner, String name, int number, Integer rev) {
        Repo repo = access.require(agent, owner, name, Role.READ).repo();
        PullRequest pr = pulls.require(repo, number);
        List<PrComment> all = comments.findByPrIdOrderById(pr.getId());
        Map<Long, PrComment> byId = new HashMap<>();
        all.forEach(c -> byId.put(c.getId(), c));
        Map<Long, List<CommentView>> replies = new LinkedHashMap<>();
        List<PrComment> roots = new ArrayList<>();
        for (PrComment c : all) {
            if (c.getParentId() == null) {
                roots.add(c);
                replies.put(c.getId(), new ArrayList<>());
            }
        }
        for (PrComment c : all) {
            if (c.getParentId() != null) {
                PrComment root = c;
                while (root.getParentId() != null && byId.containsKey(root.getParentId())) {
                    root = byId.get(root.getParentId());
                }
                replies.computeIfAbsent(root.getId(), id -> new ArrayList<>()).add(CommentView.of(c, List.of()));
            }
        }
        return roots.stream()
                .filter(root -> rev == null || root.getRev() == rev)
                .sorted(Comparator.comparing(PrComment::getId))
                .map(root -> CommentView.of(root, replies.get(root.getId())))
                .toList();
    }

    /** Validates and stores the line comments of a review, in the caller's transaction. */
    void saveInline(Repo repo, PullRequest pr, int rev, String author, List<InlineComment> inline) {
        PrRevision revision = revision(repo, pr, rev);
        List<Anchor> anchors = new ArrayList<>();
        for (InlineComment c : inline) {
            checkBody(c.body());
            if (c.path() == null) {
                throw new HintedException(ErrorCode.VALIDATION_FAILED, "comments of a review need 'path'",
                        "put general remarks into the review body");
            }
            anchors.add(anchor(repo, pr, revision, c.path(), c.line(), c.side()));
        }
        for (int i = 0; i < inline.size(); i++) {
            save(repo, pr, rev, author, anchors.get(i), inline.get(i).body(), null);
        }
    }

    private PrComment save(Repo repo, PullRequest pr, int rev, String author, Anchor anchor, String body,
            Long parentId) {
        PrComment saved = comments.save(new PrComment(pr.getId(), rev, author, anchor.path(), anchor.line(),
                anchor.side(), body, parentId, clock.instant()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("number", pr.getNumber());
        payload.put("rev", rev);
        payload.put("commentId", saved.getId());
        payload.put("path", anchor.path());
        payload.put("line", anchor.line());
        events.publishEvent(new DomainEvent("pr.comment", repo.getId(), pr.getId(), author, payload));
        return saved;
    }

    private Anchor anchor(Repo repo, PullRequest pr, PrRevision revision, String path, Integer line,
            String sideName) {
        Side side = parseSide(sideName);
        if (line != null && path == null) {
            throw new HintedException(ErrorCode.VALIDATION_FAILED, "'line' needs 'path'",
                    "send the file path together with the line");
        }
        if (path == null) {
            return new Anchor(null, null, null);
        }
        String api = PullRequestService.api(repo, pr);
        int rev = revision.getRev();
        try (Repository db = storage.open(repo.getId()); RevWalk walk = new RevWalk(db)) {
            ObjectId head = ObjectId.fromString(revision.getHeadSha());
            ObjectId commit = side == Side.OLD
                    ? MergeabilityService.mergeBase(db, ObjectId.fromString(revision.getBaseSha()), head)
                    : head;
            TreeWalk file = commit == null ? null : TreeWalk.forPath(db, path, walk.parseCommit(commit).getTree());
            if (file == null) {
                throw new HintedException(ErrorCode.VALIDATION_FAILED, "'" + path + "' does not exist on the "
                        + side + " side of rev " + rev, "list the files: GET " + api + "/files?rev=" + rev);
            }
            if (line == null) {
                return new Anchor(path, null, null);
            }
            byte[] content = db.open(file.getObjectId(0), Constants.OBJ_BLOB).getCachedBytes(Integer.MAX_VALUE);
            if (RawText.isBinary(content)) {
                throw new HintedException(ErrorCode.VALIDATION_FAILED, "'" + path + "' is a binary file",
                        "comment on the file without 'line'");
            }
            int lines = new RawText(content).size();
            if (line < 1 || line > lines) {
                throw new HintedException(ErrorCode.LINE_OUT_OF_RANGE, "line " + line + " is outside " + path
                        + " (" + lines + " lines on the " + side + " side of rev " + rev + ")",
                        "use the line numbers o (OLD) or n (NEW) from GET " + api + "/diff?rev=" + rev + "&path="
                                + path)
                        .with("nearestHunk", nearestHunk(db, revision, head, path, line, side));
            }
            return new Anchor(path, line, side);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Map<String, Integer> nearestHunk(Repository db, PrRevision revision, ObjectId head, String path,
            int line, Side side) throws IOException {
        DiffService.Hunk nearest = null;
        int best = Integer.MAX_VALUE;
        for (DiffService.FileDiff file : diffs.diff(db, ObjectId.fromString(revision.getBaseSha()), head, path,
                PullRequestViews.MAX_DIFF_LINES).files()) {
            for (DiffService.Hunk hunk : file.hunks()) {
                int start = side == Side.OLD ? hunk.oldStart() : hunk.newStart();
                int count = side == Side.OLD ? hunk.oldLines() : hunk.newLines();
                int distance = line < start ? start - line : Math.max(0, line - (start + count - 1));
                if (distance < best) {
                    best = distance;
                    nearest = hunk;
                }
            }
        }
        if (nearest == null) {
            return null;
        }
        return Map.of("oldStart", nearest.oldStart(), "oldLines", nearest.oldLines(), "newStart",
                nearest.newStart(), "newLines", nearest.newLines());
    }

    private PrRevision revision(Repo repo, PullRequest pr, int rev) {
        return revisions.findByPrIdAndRev(pr.getId(), rev).orElseThrow(() -> new HintedException(
                ErrorCode.NOT_FOUND, "PR #" + pr.getNumber() + " has no revision " + rev,
                "list revisions: GET " + PullRequestService.api(repo, pr) + "/revisions"));
    }

    private void checkBody(String body) {
        if (body == null || body.isBlank()) {
            throw new HintedException(ErrorCode.VALIDATION_FAILED, "a comment needs a non-blank 'body'",
                    "write the remark into 'body'");
        }
        if (body.getBytes(StandardCharsets.UTF_8).length > maxBody) {
            throw new HintedException(ErrorCode.VALIDATION_FAILED, "comment body is longer than " + maxBody
                    + " bytes", "shorten the comment");
        }
    }

    private static Side parseSide(String side) {
        if (side == null) {
            return Side.NEW;
        }
        try {
            return Side.valueOf(side.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new HintedException(ErrorCode.VALIDATION_FAILED, "side must be NEW or OLD, not '" + side + "'",
                    "NEW: the file in the revision head; OLD: the file at the merge-base");
        }
    }
}

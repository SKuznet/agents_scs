package org.example.agenthub.pr;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.example.agenthub.access.RepoAccessService;
import org.example.agenthub.access.Role;
import org.example.agenthub.repo.Repo;
import org.example.agenthub.repo.RepoStorage;
import org.springframework.stereotype.Service;

/**
 * {@code GET /pulls/N/review-context} (§9.6, §9.8): status first, then the change and the discussion of the current
 * revision. Agent texts come only as separate fields next to their author and role; {@code AGENTS.md} is read from
 * the tip of the target, never from the PR.
 */
@Service
public class ReviewContexts {

    static final String AGENTS_MD = "AGENTS.md";
    static final int MAX_COMMITS = 50;
    static final int MAX_AGENTS_MD = 64 * 1024;

    private final RepoAccessService access;
    private final PullRequestRepository pulls;
    private final PrRevisionRepository revisions;
    private final PrReviewRepository reviews;
    private final PullRequestViews views;
    private final CommentService comments;
    private final DiffService diffs;
    private final RepoStorage storage;

    public ReviewContexts(RepoAccessService access, PullRequestRepository pulls, PrRevisionRepository revisions,
            PrReviewRepository reviews, PullRequestViews views, CommentService comments, DiffService diffs,
            RepoStorage storage) {
        this.access = access;
        this.pulls = pulls;
        this.revisions = revisions;
        this.reviews = reviews;
        this.views = views;
        this.comments = comments;
        this.diffs = diffs;
        this.storage = storage;
    }

    /** Server-computed status of the PR, without any agent text. */
    public record Status(int number, String name, String author, Role authorRole, String target, PrState state,
            boolean draft, Boolean autoMerge, int currentRev, String headSha, String baseSha, Integer behindBy,
            Mergeable mergeable, List<String> conflictPaths, List<String> approvals, int approvalsRequired,
            List<String> blockingReviews, List<String> reviewers, String autoMergeBlockedReason) {
    }

    public record Commit(String sha, List<String> parents, AgentText message) {
    }

    public record Review(long id, String author, Role authorRole, Verdict verdict, String body, Instant createdAt) {
    }

    public record Comment(long id, String author, Role authorRole, String path, Integer line, Side side, String body,
            Long parentId, Instant createdAt, List<Comment> replies) {
    }

    public record AgentsMd(String ref, String sha, String content, boolean truncated) {
    }

    public record ReviewContext(Status pr, AgentText title, AgentText description, List<Commit> commits,
            List<DiffService.FileChange> files, DiffService.Diff diff, List<Review> reviews, List<Comment> comments,
            AgentsMd agentsMd, boolean agentsMdChanged, boolean truncated,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, String> more) {
    }

    public ReviewContext of(String agent, String owner, String name, int number, Integer maxLines) {
        Repo repo = access.require(agent, owner, name, Role.READ).repo();
        PullRequest pr = pulls.require(repo, number);
        int rev = pr.getCurrentRev();
        PrRevision revision = revisions.findByPrIdAndRev(pr.getId(), rev).orElseThrow();
        Map<String, Role> roles = new HashMap<>();
        Function<String, Role> roleOf = agentName -> roles.computeIfAbsent(agentName,
                key -> access.roleOf(key, repo).orElse(null));
        Role authorRole = roleOf.apply(pr.getAuthor());
        PullRequestViews.Details details = views.details(repo, pr);
        Status status = new Status(pr.getNumber(), pr.getName(), pr.getAuthor(), authorRole, pr.getTargetBranch(),
                pr.getState(), pr.isDraft(), pr.getAutoMerge(), rev, revision.getHeadSha(), revision.getBaseSha(),
                details.behindBy(), details.mergeable(), details.conflictPaths(), details.approvals(),
                details.approvalsRequired(), details.blockingReviews(), details.reviewers(),
                pr.getAutoMergeBlockedReason());
        int lines = maxLines == null ? PullRequestViews.DEFAULT_DIFF_LINES
                : Math.max(1, Math.min(PullRequestViews.MAX_DIFF_LINES, maxLines));
        Change change = change(repo, revision, pr.getTargetBranch(), lines);
        String api = PullRequestService.api(repo, pr);
        Map<String, String> more = new LinkedHashMap<>();
        if (change.diff().truncated()) {
            more.put("diff", "GET " + api + "/diff?rev=" + rev + "&maxLines=" + PullRequestViews.MAX_DIFF_LINES);
        }
        List<DiffService.CommitView> commitViews = change.commits();
        if (commitViews.size() > MAX_COMMITS) {
            commitViews = commitViews.subList(0, MAX_COMMITS);
            more.put("commits", "GET " + api + "/commits?rev=" + rev);
        }
        if (change.agentsMd() != null && change.agentsMd().truncated()) {
            more.put("agentsMd", "GET /v1/repos/" + repo.getFullName() + "/contents/" + AGENTS_MD + "?ref="
                    + change.agentsMd().sha() + "&raw=true");
        }
        List<Commit> commits = commitViews.stream().map(c -> new Commit(c.sha(), c.parents(),
                new AgentText(c.message(), pr.getAuthor(), authorRole))).toList();
        List<Review> currentReviews = reviews.findByPrIdAndRevOrderById(pr.getId(), rev).stream()
                .map(r -> new Review(r.getId(), r.getReviewer(), roleOf.apply(r.getReviewer()), r.getVerdict(),
                        r.getBody(), r.getCreatedAt()))
                .toList();
        List<Comment> threads = comments.threads(agent, owner, name, number, rev).stream()
                .map(c -> comment(c, roleOf)).toList();
        boolean agentsMdChanged = change.files().stream()
                .anyMatch(f -> AGENTS_MD.equals(f.path()) || AGENTS_MD.equals(f.oldPath()));
        AgentText description = pr.getDescription() == null ? null
                : new AgentText(pr.getDescription(), pr.getAuthor(), authorRole);
        return new ReviewContext(status, new AgentText(pr.getTitle(), pr.getAuthor(), authorRole), description,
                commits, change.files(), change.diff(), currentReviews, threads, change.agentsMd(), agentsMdChanged,
                !more.isEmpty(), more);
    }

    private static Comment comment(CommentService.CommentView c, Function<String, Role> roleOf) {
        return new Comment(c.id(), c.author(), roleOf.apply(c.author()), c.path(), c.line(), c.side(), c.body(),
                c.parentId(), c.createdAt(), c.replies().stream().map(reply -> comment(reply, roleOf)).toList());
    }

    private record Change(List<DiffService.CommitView> commits, List<DiffService.FileChange> files,
            DiffService.Diff diff, AgentsMd agentsMd) {
    }

    private Change change(Repo repo, PrRevision revision, String target, int lines) {
        ObjectId base = ObjectId.fromString(revision.getBaseSha());
        ObjectId head = ObjectId.fromString(revision.getHeadSha());
        try (Repository db = storage.open(repo.getId())) {
            return new Change(diffs.commits(db, base, head, MAX_COMMITS + 1), diffs.files(db, base, head),
                    diffs.diff(db, base, head, null, lines), agentsMd(db, target));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static AgentsMd agentsMd(Repository db, String target) throws IOException {
        Ref tip = db.exactRef(Constants.R_HEADS + target);
        if (tip == null) {
            return null;
        }
        try (RevWalk walk = new RevWalk(db);
                TreeWalk file = TreeWalk.forPath(db, AGENTS_MD, walk.parseCommit(tip.getObjectId()).getTree())) {
            if (file == null || file.getFileMode(0).getObjectType() != Constants.OBJ_BLOB) {
                return null;
            }
            ObjectLoader blob = db.open(file.getObjectId(0), Constants.OBJ_BLOB);
            byte[] bytes;
            try (var in = blob.openStream()) {
                bytes = in.readNBytes(MAX_AGENTS_MD);
            }
            boolean truncated = blob.getSize() > MAX_AGENTS_MD;
            return new AgentsMd(target, tip.getObjectId().name(), new String(bytes, StandardCharsets.UTF_8),
                    truncated);
        }
    }
}

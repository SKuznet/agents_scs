package org.example.agenthub.pr;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.example.agenthub.auth.AgentPrincipal;
import org.example.agenthub.web.Paged;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

@RestController
@RequestMapping("/v1/repos/{owner}/{name}/pulls")
class PullRequestController {

    private final PullRequestViews views;
    private final PullRequestService pullRequests;
    private final ReviewService reviews;
    private final MergeService merges;
    private final CommentService comments;
    private final ReviewContexts contexts;

    PullRequestController(PullRequestViews views, PullRequestService pullRequests, ReviewService reviews,
            MergeService merges, CommentService comments, ReviewContexts contexts) {
        this.views = views;
        this.pullRequests = pullRequests;
        this.reviews = reviews;
        this.merges = merges;
        this.comments = comments;
        this.contexts = contexts;
    }

    static Provenance provenance(AgentPrincipal me, HttpServletRequest request) {
        return Provenance.of(me, request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT));
    }

    @GetMapping
    Paged<PullRequestViews.Summary> list(AgentPrincipal me, @PathVariable String owner, @PathVariable String name,
            @RequestParam(required = false) String state, @RequestParam(required = false) String author,
            @RequestParam(required = false) String target, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return views.list(me.handle(), owner, name, state, author, target, cursor, limit);
    }

    @GetMapping("/{number}")
    PullRequestViews.Details get(AgentPrincipal me, @PathVariable String owner, @PathVariable String name,
            @PathVariable int number) {
        return views.get(me.handle(), owner, name, number);
    }

    @GetMapping(value = "/{number}", params = "waitFor")
    DeferredResult<PullRequestViews.Details> await(AgentPrincipal me, @PathVariable String owner,
            @PathVariable String name, @PathVariable int number, @RequestParam String waitFor,
            @RequestParam(defaultValue = "60") int timeout) {
        return views.await(me.handle(), owner, name, number, waitFor, timeout);
    }

    @GetMapping("/{number}/review-context")
    ReviewContexts.ReviewContext reviewContext(AgentPrincipal me, @PathVariable String owner,
            @PathVariable String name, @PathVariable int number, @RequestParam(required = false) Integer maxLines) {
        return contexts.of(me.handle(), owner, name, number, maxLines);
    }

    @PatchMapping("/{number}")
    PullRequestViews.Details patch(AgentPrincipal me, HttpServletRequest request, @PathVariable String owner,
            @PathVariable String name, @PathVariable int number, @RequestBody PullRequestService.Patch patch) {
        PullRequest pr = pullRequests.patch(provenance(me, request), owner, name, number, patch);
        return views.details(pr.getRepoId(), pr);
    }

    @PostMapping("/{number}/merge")
    PullRequestViews.Details merge(AgentPrincipal me, @PathVariable String owner, @PathVariable String name,
            @PathVariable int number, @RequestBody MergeService.MergeRequest request) {
        PullRequest pr = merges.merge(me.handle(), owner, name, number, request);
        return views.details(pr.getRepoId(), pr);
    }

    @PostMapping("/{number}/reviews")
    ResponseEntity<ReviewService.ReviewView> review(AgentPrincipal me, @PathVariable String owner,
            @PathVariable String name, @PathVariable int number, @RequestBody ReviewService.ReviewRequest request) {
        ReviewService.Recorded recorded = reviews.review(me.handle(), owner, name, number, request);
        return ResponseEntity.status(recorded.created() ? HttpStatus.CREATED : HttpStatus.OK).body(recorded.review());
    }

    @GetMapping("/{number}/reviews")
    List<ReviewService.ReviewView> reviews(AgentPrincipal me, @PathVariable String owner, @PathVariable String name,
            @PathVariable int number) {
        return reviews.list(me.handle(), owner, name, number);
    }

    @PostMapping("/{number}/comments")
    ResponseEntity<CommentService.CommentView> comment(AgentPrincipal me, @PathVariable String owner,
            @PathVariable String name, @PathVariable int number,
            @RequestBody CommentService.CommentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(comments.comment(me.handle(), owner, name, number, request));
    }

    @GetMapping("/{number}/comments")
    List<CommentService.CommentView> comments(AgentPrincipal me, @PathVariable String owner,
            @PathVariable String name, @PathVariable int number, @RequestParam(required = false) Integer rev) {
        return comments.threads(me.handle(), owner, name, number, rev);
    }

    @GetMapping("/{number}/revisions")
    List<PullRequestViews.RevisionView> revisions(AgentPrincipal me, @PathVariable String owner,
            @PathVariable String name, @PathVariable int number) {
        return views.revisions(me.handle(), owner, name, number);
    }

    @GetMapping("/{number}/commits")
    List<DiffService.CommitView> commits(AgentPrincipal me, @PathVariable String owner, @PathVariable String name,
            @PathVariable int number, @RequestParam(required = false) Integer rev) {
        return views.commits(me.handle(), owner, name, number, rev);
    }

    @GetMapping("/{number}/files")
    List<DiffService.FileChange> files(AgentPrincipal me, @PathVariable String owner, @PathVariable String name,
            @PathVariable int number, @RequestParam(required = false) Integer rev) {
        return views.files(me.handle(), owner, name, number, rev);
    }

    @GetMapping("/{number}/diff")
    DiffService.Diff diff(AgentPrincipal me, @PathVariable String owner, @PathVariable String name,
            @PathVariable int number, @RequestParam(required = false) Integer rev,
            @RequestParam(required = false) String path, @RequestParam(required = false) Integer maxLines) {
        return views.diff(me.handle(), owner, name, number, rev, path, maxLines);
    }
}

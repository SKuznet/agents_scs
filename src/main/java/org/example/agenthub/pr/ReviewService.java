package org.example.agenthub.pr;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.example.agenthub.access.RepoAccess;
import org.example.agenthub.access.RepoAccessService;
import org.example.agenthub.access.Role;
import org.example.agenthub.config.AgentHubProperties;
import org.example.agenthub.events.DomainEvent;
import org.example.agenthub.git.RepoLocks;
import org.example.agenthub.repo.BranchProtection;
import org.example.agenthub.repo.BranchProtectionRepository;
import org.example.agenthub.repo.Repo;
import org.example.agenthub.repo.RepoGrantRepository;
import org.example.agenthub.web.ErrorCode;
import org.example.agenthub.web.HintedException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

/** Reviews of §7.6 and the approval state they produce under invariant D8 (§7.7). */
@Service
public class ReviewService {

    private final PrReviewRepository reviews;
    private final RepoGrantRepository grants;
    private final PullRequestRepository pulls;
    private final BranchProtectionRepository protections;
    private final RepoAccessService access;
    private final RepoLocks locks;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final CommentService comments;
    private final Clock clock;
    private final DataSize maxBody;

    public ReviewService(PrReviewRepository reviews, RepoGrantRepository grants, PullRequestRepository pulls,
            BranchProtectionRepository protections, RepoAccessService access, RepoLocks locks,
            TransactionTemplate tx, ApplicationEventPublisher events, CommentService comments, Clock clock,
            AgentHubProperties properties) {
        this.reviews = reviews;
        this.grants = grants;
        this.pulls = pulls;
        this.protections = protections;
        this.access = access;
        this.locks = locks;
        this.tx = tx;
        this.events = events;
        this.comments = comments;
        this.clock = clock;
        this.maxBody = properties.limits().maxCommentSize();
    }

    public record ReviewRequest(Integer rev, Verdict verdict, String body,
            List<CommentService.InlineComment> comments) {
    }

    public record ReviewView(long id, int rev, String reviewer, Verdict verdict, String body, Instant createdAt) {
        static ReviewView of(PrReview review) {
            return new ReviewView(review.getId(), review.getRev(), review.getReviewer(), review.getVerdict(),
                    review.getBody(), review.getCreatedAt());
        }
    }

    public record Recorded(ReviewView review, boolean created) {
    }

    /** §7.6: COMMENT needs PROPOSE; APPROVE and REQUEST_CHANGES need WRITE and someone else's PR. */
    public Recorded review(String agent, String owner, String name, int number, ReviewRequest request) {
        RepoAccess granted = access.require(agent, owner, name, Role.READ);
        Repo repo = granted.repo();
        if (!granted.role().atLeast(Role.PROPOSE)) {
            throw RepoAccessService.insufficientRole(repo, agent, granted.role(), Role.PROPOSE);
        }
        validate(request);
        Verdict verdict = request.verdict();
        if (verdict != Verdict.COMMENT && !granted.role().atLeast(Role.WRITE)) {
            throw new HintedException(ErrorCode.INSUFFICIENT_ROLE,
                    verdict + " needs WRITE; you have " + granted.role(),
                    "leave a COMMENT review, or ask the owner '" + repo.getOwner() + "' for WRITE");
        }
        return locks.withLock(repo.getId(), () -> tx.execute(status -> {
            PullRequest pr = pulls.require(repo, number);
            String api = PullRequestService.api(repo, pr);
            PullRequestService.requireOpen(repo, pr);
            if (verdict != Verdict.COMMENT && pr.getAuthor().equals(agent)) {
                throw new HintedException(ErrorCode.SELF_REVIEW, "authors cannot approve or block their own pull"
                        + " request", "ask another WRITE member of " + repo.getFullName() + " to review it");
            }
            if (request.rev() != pr.getCurrentRev()) {
                throw new HintedException(ErrorCode.STALE_REVISION, "the review is for rev " + request.rev()
                        + " but PR #" + number + " is at rev " + pr.getCurrentRev(),
                        "GET " + api + " and review rev " + pr.getCurrentRev());
            }
            if (verdict == Verdict.APPROVE) {
                var existing = latestVerdict(pr, agent);
                if (existing != null && existing.getVerdict() == Verdict.APPROVE) {
                    return new Recorded(ReviewView.of(existing), false);
                }
            }
            List<CommentService.InlineComment> inline = request.comments() == null ? List.of() : request.comments();
            comments.saveInline(repo, pr, pr.getCurrentRev(), agent, inline);
            PrReview review = reviews.save(new PrReview(pr.getId(), pr.getCurrentRev(), agent, verdict,
                    request.body(), clock.instant()));
            events.publishEvent(new DomainEvent("pr.review", repo.getId(), pr.getId(), agent, Map.of("number",
                    number, "rev", pr.getCurrentRev(), "verdict", verdict, "comments", inline.size())));
            events.publishEvent(new PullRequestChanged(repo.getId(), pr.getId()));
            return new Recorded(ReviewView.of(review), true);
        }));
    }

    public List<ReviewView> list(String agent, String owner, String name, int number) {
        Repo repo = access.require(agent, owner, name, Role.READ).repo();
        return reviews.findByPrIdOrderById(pulls.require(repo, number).getId()).stream().map(ReviewView::of)
                .toList();
    }

    /** Whether {@code reviewer} has an APPROVE or REQUEST_CHANGES on the current revision. */
    public boolean decided(PullRequest pr, String reviewer) {
        return latestVerdict(pr, reviewer) != null;
    }

    private PrReview latestVerdict(PullRequest pr, String reviewer) {
        PrReview latest = null;
        for (PrReview review : reviews.findByPrIdAndRevOrderById(pr.getId(), pr.getCurrentRev())) {
            if (review.getReviewer().equals(reviewer) && review.getVerdict() != Verdict.COMMENT) {
                latest = review;
            }
        }
        return latest;
    }

    private void validate(ReviewRequest request) {
        if (request.rev() == null || request.verdict() == null) {
            throw new HintedException(ErrorCode.VALIDATION_FAILED, "'rev' and 'verdict' are required",
                    "send {\"rev\": <current rev>, \"verdict\": \"APPROVE|REQUEST_CHANGES|COMMENT\","
                            + " \"body\": \"...\"}");
        }
        long limit = maxBody.toBytes();
        if (request.body() != null && request.body().getBytes(StandardCharsets.UTF_8).length > limit) {
            throw new HintedException(ErrorCode.VALIDATION_FAILED, "review body is longer than " + limit + " bytes",
                    "shorten the review");
        }
    }

    /** Approvals of the current revision, counted with everyone's role as of now. */
    public ApprovalRules.Status status(Repo repo, PullRequest pr) {
        List<ApprovalRules.Vote> votes = reviews.findByPrIdAndRevOrderById(pr.getId(), pr.getCurrentRev()).stream()
                .map(PrReview::vote)
                .toList();
        Predicate<String> canWrite = agent -> canWrite(repo, agent);
        return ApprovalRules.evaluate(pr.getAuthor(), canWrite.test(pr.getAuthor()),
                requiredApprovals(repo.getId(), pr.getTargetBranch()), votes, canWrite);
    }

    /** Agents other than {@code author} who hold WRITE now and so could approve (§7.8). */
    public List<String> otherWriters(Repo repo, String author) {
        List<String> writers = new java.util.ArrayList<>();
        if (!repo.getOwner().equals(author)) {
            writers.add(repo.getOwner());
        }
        grants.findByRepoIdOrderByAgent(repo.getId()).stream()
                .filter(grant -> grant.getRole().atLeast(Role.WRITE) && !grant.getAgent().equals(author))
                .forEach(grant -> writers.add(grant.getAgent()));
        return writers;
    }

    public boolean canWrite(Repo repo, String agent) {
        return access.roleOf(agent, repo).map(role -> role.atLeast(Role.WRITE)).orElse(false);
    }

    public int requiredApprovals(long repoId, String branch) {
        return protections.findById(new BranchProtection.Key(repoId, branch))
                .map(BranchProtection::getRequiredApprovals).orElse(0);
    }
}

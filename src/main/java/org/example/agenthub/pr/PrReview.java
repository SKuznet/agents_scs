package org.example.agenthub.pr;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "pr_review")
public class PrReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pr_id", nullable = false)
    private Long prId;

    /** Only for the foreign key of §11; never navigated. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pr_id", insertable = false, updatable = false)
    private PullRequest pullRequest;

    @Column(nullable = false)
    private int rev;

    @Column(nullable = false, length = 39)
    private String reviewer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Verdict verdict;

    @Lob
    private String body;

    @Column(nullable = false)
    private Instant createdAt;

    protected PrReview() {
    }

    public PrReview(long prId, int rev, String reviewer, Verdict verdict, String body, Instant createdAt) {
        this.prId = prId;
        this.rev = rev;
        this.reviewer = reviewer;
        this.verdict = verdict;
        this.body = body;
        this.createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
    }

    public Long getId() {
        return id;
    }

    public Long getPrId() {
        return prId;
    }

    public int getRev() {
        return rev;
    }

    public String getReviewer() {
        return reviewer;
    }

    public Verdict getVerdict() {
        return verdict;
    }

    public String getBody() {
        return body;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    ApprovalRules.Vote vote() {
        return new ApprovalRules.Vote(reviewer, verdict);
    }
}

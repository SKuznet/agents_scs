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

/** A comment on a PR revision, optionally on a file and a line of it (§7.6, §11). */
@Entity
@Table(name = "pr_comment")
public class PrComment {

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
    private String author;

    @Column(length = 4096)
    private String path;

    private Integer line;

    @Enumerated(EnumType.STRING)
    @Column(length = 8)
    private Side side;

    @Lob
    @Column(nullable = false)
    private String body;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(nullable = false)
    private Instant createdAt;

    protected PrComment() {
    }

    public PrComment(long prId, int rev, String author, String path, Integer line, Side side, String body,
            Long parentId, Instant createdAt) {
        this.prId = prId;
        this.rev = rev;
        this.author = author;
        this.path = path;
        this.line = line;
        this.side = side;
        this.body = body;
        this.parentId = parentId;
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

    public String getAuthor() {
        return author;
    }

    public String getPath() {
        return path;
    }

    public Integer getLine() {
        return line;
    }

    public Side getSide() {
        return side;
    }

    public String getBody() {
        return body;
    }

    public Long getParentId() {
        return parentId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

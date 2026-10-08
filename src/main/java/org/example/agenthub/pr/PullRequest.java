package org.example.agenthub.pr;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

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
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import org.example.agenthub.repo.Repo;

/**
 * A pull request (§7.1, §11). {@code openName} equals {@code name} while the PR is open and is {@code null}
 * otherwise, so {@code UNIQUE(repo_id, author, open_name)} allows one open PR per author and name.
 */
@Entity
@Table(name = "pull_request", uniqueConstraints = {
    @UniqueConstraint(name = "uk_pull_request_number", columnNames = {"repo_id", "number"}),
    @UniqueConstraint(name = "uk_pull_request_open_name", columnNames = {"repo_id", "author", "open_name"})})
public class PullRequest {

    public static final int MAX_DESCRIPTION = 64 * 1024;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_id", nullable = false)
    private Long repoId;

    /** Only for the foreign key of §11; never navigated. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repo_id", insertable = false, updatable = false)
    private Repo repo;

    @Column(nullable = false)
    private int number;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "open_name", length = 100)
    private String openName;

    @Column(nullable = false, length = 39)
    private String author;

    @Column(nullable = false, length = 100)
    private String targetBranch;

    @Column(nullable = false, length = PushOptions.MAX_TITLE)
    private String title;

    @Lob
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PrState state;

    @Column(nullable = false)
    private boolean draft;

    /** {@code null}: follow the target branch protection (§7.8, phase 2b). */
    private Boolean autoMerge;

    @Column(nullable = false, length = 40)
    private String headSha;

    @Column(nullable = false)
    private int currentRev;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Mergeable mergeable = Mergeable.UNKNOWN;

    @Column(length = 40)
    private String mergeableTargetSha;

    @Lob
    private String conflictPaths;

    @Column(length = 500)
    private String mergeableError;

    @Column(length = 500)
    private String autoMergeBlockedReason;

    @Column(length = 40)
    private String mergingCommitSha;

    @Column(length = 40)
    private String mergeCommitSha;

    @Column(length = 39)
    private String mergedBy;

    private Instant mergedAt;

    @Column(nullable = false)
    private boolean autoMerged;

    @Version
    private long version;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected PullRequest() {
    }

    public static PullRequest open(long repoId, int number, String name, String author, String target, String title,
            String description, boolean draft, String headSha, Instant now) {
        PullRequest pr = new PullRequest();
        pr.repoId = repoId;
        pr.number = number;
        pr.name = name;
        pr.openName = name;
        pr.author = author;
        pr.targetBranch = target;
        pr.title = title;
        pr.description = description;
        pr.state = PrState.OPEN;
        pr.draft = draft;
        pr.headSha = headSha;
        pr.currentRev = 1;
        pr.createdAt = micros(now);
        pr.updatedAt = pr.createdAt;
        return pr;
    }

    private static Instant micros(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    void newRevision(int rev, String head, String target, Instant now) {
        this.currentRev = rev;
        this.headSha = head;
        this.targetBranch = target;
        touch(now);
    }

    void recordMergeability(Mergeable status, String targetSha, List<String> conflicts, String error) {
        this.mergeable = status;
        this.mergeableTargetSha = targetSha;
        this.conflictPaths = conflicts.isEmpty() ? null : String.join("\n", conflicts);
        this.mergeableError = error;
    }

    public void close(Instant now) {
        this.state = PrState.CLOSED;
        this.openName = null;
        touch(now);
    }

    void reopen(Instant now) {
        this.state = PrState.OPEN;
        this.openName = name;
        touch(now);
    }

    /** Merge intent of §7.7 step 3: the PR stays OPEN until the target ref has moved. */
    void startMerge(String commitSha, String by) {
        this.mergingCommitSha = commitSha;
        this.mergedBy = by;
    }

    void abandonMerge() {
        this.mergingCommitSha = null;
        this.mergedBy = null;
    }

    void merged(String commitSha, String by, Instant now, boolean automatically) {
        this.autoMerged = automatically;
        this.autoMergeBlockedReason = null;
        this.state = PrState.MERGED;
        this.openName = null;
        this.mergeCommitSha = commitSha;
        this.mergedBy = by;
        this.mergedAt = micros(now);
        this.mergingCommitSha = null;
        touch(now);
    }

    void touch(Instant now) {
        this.updatedAt = micros(now);
    }

    public Long getId() {
        return id;
    }

    public Long getRepoId() {
        return repoId;
    }

    public int getNumber() {
        return number;
    }

    public String getName() {
        return name;
    }

    public String getAuthor() {
        return author;
    }

    public String getTargetBranch() {
        return targetBranch;
    }

    public String getTitle() {
        return title;
    }

    void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    void setDescription(String description) {
        this.description = description;
    }

    public PrState getState() {
        return state;
    }

    public boolean isOpen() {
        return state == PrState.OPEN;
    }

    public boolean isDraft() {
        return draft;
    }

    void setDraft(boolean draft) {
        this.draft = draft;
    }

    public Boolean getAutoMerge() {
        return autoMerge;
    }

    public String getHeadSha() {
        return headSha;
    }

    public int getCurrentRev() {
        return currentRev;
    }

    public Mergeable getMergeable() {
        return mergeable;
    }

    public String getMergeableTargetSha() {
        return mergeableTargetSha;
    }

    public List<String> getConflictPaths() {
        return conflictPaths == null ? List.of() : Arrays.asList(conflictPaths.split("\n"));
    }

    public String getMergeableError() {
        return mergeableError;
    }

    public String getAutoMergeBlockedReason() {
        return autoMergeBlockedReason;
    }

    void setAutoMergeBlockedReason(String reason) {
        this.autoMergeBlockedReason = reason == null || reason.length() <= 500 ? reason : reason.substring(0, 500);
    }

    void setAutoMerge(Boolean autoMerge) {
        this.autoMerge = autoMerge;
    }

    public String getMergingCommitSha() {
        return mergingCommitSha;
    }

    public String getMergeCommitSha() {
        return mergeCommitSha;
    }

    public String getMergedBy() {
        return mergedBy;
    }

    public Instant getMergedAt() {
        return mergedAt;
    }

    public boolean isAutoMerged() {
        return autoMerged;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

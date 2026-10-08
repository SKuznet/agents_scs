package org.example.agenthub.repo;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Per-branch protection (§6.4). A branch without a record is unprotected: see {@link #unprotected}. */
@Entity
@Table(name = "branch_protection")
@IdClass(BranchProtection.Key.class)
public class BranchProtection {

    @Id
    @Column(name = "repo_id")
    private Long repoId;

    @Id
    @Column(length = 100)
    private String branch;

    /** Only for the foreign key of §11; never navigated. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repo_id", insertable = false, updatable = false)
    private Repo repo;

    @Column(nullable = false)
    private boolean requirePr;

    @Column(nullable = false)
    private boolean allowForcePush;

    @Column(nullable = false)
    private boolean allowDelete;

    @Column(nullable = false)
    private int requiredApprovals;

    @Column(nullable = false)
    private boolean autoMerge;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private MergeStrategy autoMergeStrategy;

    protected BranchProtection() {
    }

    private BranchProtection(Long repoId, String branch, boolean requirePr, boolean allowForcePush,
            boolean allowDelete, int requiredApprovals, boolean autoMerge, MergeStrategy autoMergeStrategy) {
        this.repoId = repoId;
        this.branch = branch;
        this.requirePr = requirePr;
        this.allowForcePush = allowForcePush;
        this.allowDelete = allowDelete;
        this.requiredApprovals = requiredApprovals;
        this.autoMerge = autoMerge;
        this.autoMergeStrategy = autoMergeStrategy;
    }

    /** The record created automatically for a default branch. */
    public static BranchProtection forDefaultBranch(Long repoId, String branch) {
        return new BranchProtection(repoId, branch, true, false, false, 1, true, MergeStrategy.SQUASH);
    }

    public static BranchProtection configured(Long repoId, String branch, boolean requirePr, boolean allowForcePush,
            boolean allowDelete, int requiredApprovals, boolean autoMerge, MergeStrategy autoMergeStrategy) {
        return new BranchProtection(repoId, branch, requirePr, allowForcePush, allowDelete, requiredApprovals,
                autoMerge, autoMergeStrategy);
    }

    void replaceWith(BranchProtection other) {
        this.requirePr = other.requirePr;
        this.allowForcePush = other.allowForcePush;
        this.allowDelete = other.allowDelete;
        this.requiredApprovals = other.requiredApprovals;
        this.autoMerge = other.autoMerge;
        this.autoMergeStrategy = other.autoMergeStrategy;
    }

    /** Effective settings of a branch that has no record; never persisted. */
    public static BranchProtection unprotected(Long repoId, String branch) {
        return new BranchProtection(repoId, branch, false, true, true, 0, false, null);
    }

    public Long getRepoId() {
        return repoId;
    }

    public String getBranch() {
        return branch;
    }

    public boolean isRequirePr() {
        return requirePr;
    }

    public boolean isAllowForcePush() {
        return allowForcePush;
    }

    public boolean isAllowDelete() {
        return allowDelete;
    }

    public int getRequiredApprovals() {
        return requiredApprovals;
    }

    public boolean isAutoMerge() {
        return autoMerge;
    }

    public MergeStrategy getAutoMergeStrategy() {
        return autoMergeStrategy;
    }

    public static class Key implements Serializable {
        private Long repoId;
        private String branch;

        protected Key() {
        }

        public Key(Long repoId, String branch) {
            this.repoId = repoId;
            this.branch = branch;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(repoId, k.repoId) && Objects.equals(branch, k.branch);
        }

        @Override
        public int hashCode() {
            return Objects.hash(repoId, branch);
        }
    }
}

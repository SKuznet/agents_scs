package org.example.agenthub.pr;

import java.io.Serializable;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Version K of a PR (§7.2): its head, the target tip it was based on, and who pushed it (§8). */
@Entity
@Table(name = "pr_revision")
@IdClass(PrRevision.Key.class)
public class PrRevision {

    @Id
    @Column(name = "pr_id")
    private Long prId;

    @Id
    private int rev;

    /** Only for the foreign key of §11; never navigated. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pr_id", insertable = false, updatable = false)
    private PullRequest pullRequest;

    @Column(nullable = false, length = 40)
    private String headSha;

    @Column(nullable = false, length = 40)
    private String baseSha;

    @Column(nullable = false, length = 39)
    private String pushedBy;

    @Column(length = 128)
    private String model;

    @Column(length = 128)
    private String session;

    @Column(length = 128)
    private String operator;

    @Column(length = 64)
    private String ip;

    @Column(length = 500)
    private String userAgent;

    @Column(nullable = false)
    private Instant createdAt;

    protected PrRevision() {
    }

    public PrRevision(long prId, int rev, String headSha, String baseSha, Provenance provenance, Instant createdAt) {
        this.prId = prId;
        this.rev = rev;
        this.headSha = headSha;
        this.baseSha = baseSha;
        this.pushedBy = provenance.agent();
        this.model = provenance.model();
        this.session = provenance.session();
        this.operator = provenance.operator();
        this.ip = provenance.ip();
        this.userAgent = provenance.userAgent() == null || provenance.userAgent().length() <= 500
                ? provenance.userAgent() : provenance.userAgent().substring(0, 500);
        this.createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
    }

    public Long getPrId() {
        return prId;
    }

    public int getRev() {
        return rev;
    }

    public String getHeadSha() {
        return headSha;
    }

    public String getBaseSha() {
        return baseSha;
    }

    public String getPushedBy() {
        return pushedBy;
    }

    public String getModel() {
        return model;
    }

    public String getSession() {
        return session;
    }

    public String getOperator() {
        return operator;
    }

    public String getIp() {
        return ip;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public static class Key implements Serializable {
        private Long prId;
        private int rev;

        protected Key() {
        }

        public Key(Long prId, int rev) {
            this.prId = prId;
            this.rev = rev;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(prId, k.prId) && rev == k.rev;
        }

        @Override
        public int hashCode() {
            return Objects.hash(prId, rev);
        }
    }
}

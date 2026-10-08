package org.example.agenthub.pr;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** A requested reviewer of a PR (§7.3, §11). */
@Entity
@Table(name = "pr_reviewer")
@IdClass(PrReviewer.Key.class)
public class PrReviewer {

    @Id
    @Column(name = "pr_id")
    private Long prId;

    @Id
    @Column(length = 39)
    private String agent;

    /** Only for the foreign key of §11; never navigated. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pr_id", insertable = false, updatable = false)
    private PullRequest pullRequest;

    protected PrReviewer() {
    }

    public PrReviewer(long prId, String agent) {
        this.prId = prId;
        this.agent = agent;
    }

    public String getAgent() {
        return agent;
    }

    public static class Key implements Serializable {
        private Long prId;
        private String agent;

        protected Key() {
        }

        public Key(Long prId, String agent) {
            this.prId = prId;
            this.agent = agent;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(prId, k.prId) && Objects.equals(agent, k.agent);
        }

        @Override
        public int hashCode() {
            return Objects.hash(prId, agent);
        }
    }
}

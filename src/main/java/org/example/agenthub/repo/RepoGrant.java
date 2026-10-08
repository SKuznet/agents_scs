package org.example.agenthub.repo;

import java.io.Serializable;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

import org.example.agenthub.access.Role;

@Entity
@Table(name = "repo_grant")
@IdClass(RepoGrant.Key.class)
public class RepoGrant {

    @Id
    @Column(name = "repo_id")
    private Long repoId;

    @Id
    @Column(length = 39)
    private String agent;

    /** Only for the foreign key of §11; never navigated. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repo_id", insertable = false, updatable = false)
    private Repo repo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    @Column(nullable = false, length = 39)
    private String grantedBy;

    @Column(nullable = false)
    private Instant createdAt;

    protected RepoGrant() {
    }

    public RepoGrant(Long repoId, String agent, Role role, String grantedBy, Instant createdAt) {
        this.repoId = repoId;
        this.agent = agent;
        this.role = role;
        this.grantedBy = grantedBy;
        this.createdAt = createdAt == null ? null : createdAt.truncatedTo(ChronoUnit.MICROS);
    }

    public Long getRepoId() {
        return repoId;
    }

    public String getAgent() {
        return agent;
    }

    public Role getRole() {
        return role;
    }

    public String getGrantedBy() {
        return grantedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    void change(Role role, String grantedBy) {
        this.role = role;
        this.grantedBy = grantedBy;
    }

    public static class Key implements Serializable {
        private Long repoId;
        private String agent;

        protected Key() {
        }

        public Key(Long repoId, String agent) {
            this.repoId = repoId;
            this.agent = agent;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(repoId, k.repoId) && Objects.equals(agent, k.agent);
        }

        @Override
        public int hashCode() {
            return Objects.hash(repoId, agent);
        }
    }
}

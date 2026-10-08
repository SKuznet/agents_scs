package org.example.agenthub.events;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/** Internal event log (§10.6, §11). No foreign key on {@code repo_id}: {@code repo.deleted} outlives the repo. */
@Entity
@Table(name = "event", indexes = @Index(name = "ix_event_repo", columnList = "repo_id, id"))
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_id")
    private Long repoId;

    @Column(name = "pr_id")
    private Long prId;

    @Column(nullable = false, length = 40)
    private String type;

    @Column(nullable = false, length = 39)
    private String actor;

    @Lob
    @Column(nullable = false)
    private String payload;

    @Column(nullable = false)
    private Instant createdAt;

    protected Event() {
    }

    Event(Long repoId, Long prId, String type, String actor, String payload, Instant createdAt) {
        this.repoId = repoId;
        this.prId = prId;
        this.type = type;
        this.actor = actor;
        this.payload = payload;
        this.createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
    }

    public Long getId() {
        return id;
    }

    public Long getRepoId() {
        return repoId;
    }

    public Long getPrId() {
        return prId;
    }

    public String getType() {
        return type;
    }

    public String getActor() {
        return actor;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

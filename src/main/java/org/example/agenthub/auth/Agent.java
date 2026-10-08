package org.example.agenthub.auth;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "agent")
public class Agent {

    @Id
    @Column(length = 39)
    private String handle;

    @Column(nullable = false)
    private Instant firstSeen;

    @Column(nullable = false)
    private Instant lastSeen;

    protected Agent() {
    }

    Agent(String handle, Instant seen) {
        this.handle = handle;
        this.firstSeen = seen.truncatedTo(ChronoUnit.MICROS);
        this.lastSeen = firstSeen;
    }

    public String getHandle() {
        return handle;
    }

    public Instant getFirstSeen() {
        return firstSeen;
    }

    public Instant getLastSeen() {
        return lastSeen;
    }

    void seen(Instant at) {
        this.lastSeen = at.truncatedTo(ChronoUnit.MICROS);
    }
}

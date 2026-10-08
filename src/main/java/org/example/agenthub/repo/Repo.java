package org.example.agenthub.repo;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.example.agenthub.access.Visibility;

@Entity
@Table(name = "repository", uniqueConstraints = @UniqueConstraint(name = "uk_repository_owner_name",
        columnNames = {"owner", "name"}))
public class Repo {

    public static final String INITIAL_BRANCH = "main";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 39)
    private String owner;

    /** {@code name~<id>} once deleted, which frees the name (§11). */
    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Visibility visibility;

    @Column(length = 1000)
    private String description;

    @Column(nullable = false, length = 100)
    private String defaultBranch = INITIAL_BRANCH;

    @Column(nullable = false)
    private int nextPrNumber = 1;

    @Column(nullable = false)
    private long sizeBytes;

    /** Whether the server made the initial commit ({@code init}); kept to answer repeated POSTs (§9.9). */
    @Column(nullable = false)
    private boolean initialized;

    private Instant deletedAt;

    @Column(nullable = false)
    private Instant createdAt;

    protected Repo() {
    }

    public Repo(String owner, String name, Visibility visibility, String description, boolean initialized) {
        this.owner = owner;
        this.name = name;
        this.visibility = visibility;
        this.description = description;
        this.initialized = initialized;
    }

    public Long getId() {
        return id;
    }

    public String getOwner() {
        return owner;
    }

    public String getName() {
        return name;
    }

    public String getFullName() {
        return owner + "/" + name;
    }

    public Visibility getVisibility() {
        return visibility;
    }

    public String getDescription() {
        return description;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
    }

    void setDescription(String description) {
        this.description = description;
    }

    void setVisibility(Visibility visibility) {
        this.visibility = visibility;
    }

    void setDefaultBranch(String defaultBranch) {
        this.defaultBranch = defaultBranch;
    }

    /** The next PR number of this repository; call under the repository lock (§7.1). */
    public int allocatePrNumber() {
        return nextPrNumber++;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    void markDeleted(Instant at) {
        this.deletedAt = at.truncatedTo(ChronoUnit.MICROS);
        this.name = name + "~" + id;
    }
}

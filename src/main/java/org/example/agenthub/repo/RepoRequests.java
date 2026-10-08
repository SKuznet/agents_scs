package org.example.agenthub.repo;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.example.agenthub.access.Visibility;

public final class RepoRequests {

    private RepoRequests() {
    }

    /** {@code init} and {@code visibility} default to {@code true} and {@code private}. */
    public record Create(@NotNull String name, Visibility visibility, @Size(max = 1000) String description,
            Boolean init) {
    }

    /** Absent fields stay unchanged. */
    public record Update(@Size(max = 1000) String description, Visibility visibility, String defaultBranch) {
    }
}

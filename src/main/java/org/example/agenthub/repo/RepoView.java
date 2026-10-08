package org.example.agenthub.repo;

import java.time.Instant;

import org.example.agenthub.access.Role;
import org.example.agenthub.access.Visibility;

public record RepoView(String owner, String name, Visibility visibility, String description, String defaultBranch,
        Role role, String cloneUrl, long sizeBytes, Instant createdAt) {
}

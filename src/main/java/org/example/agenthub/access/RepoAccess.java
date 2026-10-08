package org.example.agenthub.access;

import org.example.agenthub.repo.Repo;

/** A repository together with the caller's effective role in it. */
public record RepoAccess(Repo repo, Role role) {
}

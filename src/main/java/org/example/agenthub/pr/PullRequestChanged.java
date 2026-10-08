package org.example.agenthub.pr;

/** Something that may change mergeability or readiness for auto-merge happened to a PR (handled after commit). */
public record PullRequestChanged(long repoId, long prId) {
}

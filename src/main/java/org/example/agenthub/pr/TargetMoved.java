package org.example.agenthub.pr;

/** A branch moved (push or merge): the open PRs into it need their mergeability rechecked (§7.4). */
public record TargetMoved(long repoId, String branch) {
}

package org.example.agenthub.events;

/**
 * State that inboxes and long-polls observe changed without a {@link DomainEvent} (a mergeability rewrite with the
 * same value, branch protection); like every wake-up it is delivered after commit.
 */
public record StateChanged(long repoId) {
}

package org.example.agenthub.events;

import java.util.Map;

/**
 * Published with Spring's {@code ApplicationEventPublisher}; {@link EventWriter} stores it once the surrounding
 * transaction commits (immediately when there is none).
 */
public record DomainEvent(String type, Long repoId, Long prId, String actor, Map<String, ?> payload) {

    public static final String REPO_CREATED = "repo.created";
    public static final String REPO_DELETED = "repo.deleted";
    public static final String GRANT_CHANGED = "grant.changed";
    public static final String PUSH = "push";
}

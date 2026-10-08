package org.example.agenthub.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/** Catalog of agents that have connected at least once (§4.4). */
@Service
public class AgentDirectory {

    static final Duration LAST_SEEN_RESOLUTION = Duration.ofMinutes(1);

    private final AgentRepository agents;
    private final Clock clock;
    /** Last persisted {@code last_seen} per handle, so most requests skip the database. */
    private final ConcurrentMap<String, Instant> persistedLastSeen = new ConcurrentHashMap<>();

    public AgentDirectory(AgentRepository agents, Clock clock) {
        this.agents = agents;
        this.clock = clock;
    }

    public void touch(String handle) {
        Instant now = clock.instant();
        Instant known = persistedLastSeen.get(handle);
        if (known != null && now.isBefore(known.plus(LAST_SEEN_RESOLUTION))) {
            return;
        }
        Agent agent = agents.findById(handle).orElse(null);
        if (agent == null) {
            try {
                persistedLastSeen.put(handle, agents.saveAndFlush(new Agent(handle, now)).getLastSeen());
            } catch (DataIntegrityViolationException concurrentFirstRequest) {
                persistedLastSeen.remove(handle);
            }
            return;
        }
        if (!now.isBefore(agent.getLastSeen().plus(LAST_SEEN_RESOLUTION))) {
            agent.seen(now);
            agents.save(agent);
        }
        persistedLastSeen.put(handle, agent.getLastSeen());
    }

    public Optional<Agent> find(String handle) {
        return agents.findById(handle);
    }

    public boolean exists(String handle) {
        return agents.existsById(handle);
    }

    public List<Agent> search(String query, String after, int limit) {
        return agents.findByHandleContainingAndHandleGreaterThanOrderByHandle(query == null ? "" : query,
                after == null ? "" : after, PageRequest.ofSize(limit));
    }
}

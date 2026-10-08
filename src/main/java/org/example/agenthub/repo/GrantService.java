package org.example.agenthub.repo;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.example.agenthub.access.RepoAccessService;
import org.example.agenthub.access.Role;
import org.example.agenthub.auth.AgentDirectory;
import org.example.agenthub.events.DomainEvent;
import org.example.agenthub.web.ErrorCode;
import org.example.agenthub.web.HintedException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Grants of §5: ADMIN manages, READ+ lists; the owner and reserved identities are never granted. */
@Service
public class GrantService {

    private final RepoGrantRepository grants;
    private final RepoAccessService access;
    private final AgentDirectory directory;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public GrantService(RepoGrantRepository grants, RepoAccessService access, AgentDirectory directory,
            TransactionTemplate tx, Clock clock, ApplicationEventPublisher events) {
        this.grants = grants;
        this.access = access;
        this.directory = directory;
        this.tx = tx;
        this.clock = clock;
        this.events = events;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GrantView(String agent, Role role, String grantedBy, String warning) {
        static GrantView of(RepoGrant grant, String warning) {
            return new GrantView(grant.getAgent(), grant.getRole(), grant.getGrantedBy(), warning);
        }
    }

    public List<GrantView> list(String caller, String owner, String name) {
        Repo repo = access.require(caller, owner, name, Role.READ).repo();
        return grants.findByRepoIdOrderByAgent(repo.getId()).stream().map(g -> GrantView.of(g, null)).toList();
    }

    public GrantView put(String caller, String owner, String name, String agent, Role role) {
        Repo repo = access.require(caller, owner, name, Role.ADMIN).repo();
        checkGrantable(repo, agent);
        RepoGrant grant = tx.execute(status -> {
            RepoGrant existing = grants.findByRepoIdAndAgent(repo.getId(), agent).orElse(null);
            if (existing == null) {
                changed(repo, caller, agent, role, null);
                return grants.save(new RepoGrant(repo.getId(), agent, role, caller, clock.instant()));
            }
            if (existing.getRole() != role) {
                changed(repo, caller, agent, role, existing.getRole());
                existing.change(role, caller);
            }
            return existing;
        });
        String warning = directory.exists(agent) ? null : "agent '" + agent + "' has never connected";
        return GrantView.of(grant, warning);
    }

    public void revoke(String caller, String owner, String name, String agent) {
        Repo repo = access.require(caller, owner, name, Role.ADMIN).repo();
        checkGrantable(repo, agent);
        tx.executeWithoutResult(status -> grants.findByRepoIdAndAgent(repo.getId(), agent).ifPresent(grant -> {
            grants.delete(grant);
            changed(repo, caller, agent, null, grant.getRole());
        }));
    }

    private void changed(Repo repo, String actor, String agent, Role role, Role previous) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("repo", repo.getFullName());
        payload.put("agent", agent);
        payload.put("role", role);
        payload.put("previous", previous);
        events.publishEvent(new DomainEvent(DomainEvent.GRANT_CHANGED, repo.getId(), null, actor, payload));
    }

    private static void checkGrantable(Repo repo, String agent) {
        if (!NamePolicy.isValidHandle(agent)) {
            throw new HintedException(ErrorCode.INVALID_NAME, "'" + agent + "' is not a valid agent handle",
                    "handles match [a-z0-9][a-z0-9_-]{0,38}; find agents with GET /v1/agents?q=");
        }
        if (agent.equals(repo.getOwner())) {
            throw new HintedException(ErrorCode.VALIDATION_FAILED,
                    "'" + agent + "' owns " + repo.getFullName() + " and always has ADMIN",
                    "grant roles to other agents only");
        }
        // §5.4 names 'agenthub'; every reserved handle is refused because no token can carry one (§4.2)
        if (NamePolicy.isReservedHandle(agent)) {
            throw new HintedException(ErrorCode.VALIDATION_FAILED, "'" + agent + "' is a reserved identity",
                    "grant roles to agent handles only");
        }
    }
}

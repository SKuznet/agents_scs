package org.example.agenthub.auth;

import java.time.Instant;
import java.util.List;

import org.example.agenthub.config.AgentHubProperties;
import org.example.agenthub.events.InboxService;
import org.example.agenthub.repo.RepoService;
import org.example.agenthub.web.HintedException;
import org.example.agenthub.web.Paged;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
class AgentController {

    private final AgentDirectory directory;
    private final RepoService repos;
    private final InboxService inbox;
    private final AgentHubProperties properties;

    AgentController(AgentDirectory directory, RepoService repos, InboxService inbox, AgentHubProperties properties) {
        this.directory = directory;
        this.repos = repos;
        this.inbox = inbox;
        this.properties = properties;
    }

    /** The single entry point (§9.3). */
    @GetMapping("/me")
    MeView me(AgentPrincipal me) {
        return new MeView(me.handle(), me.model(), me.session(), repos.memberships(me.handle()),
                repos.internalOnlyCount(me.handle()), inbox.count(me.handle()), properties.publicUrl() + "/v1/setup");
    }

    @GetMapping("/agents")
    Paged<AgentView> agents(@RequestParam(required = false) String q, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        int size = Paged.limit(limit);
        return Paged.of(directory.search(q, cursor, size + 1), size, Agent::getHandle, AgentView::of);
    }

    @GetMapping("/agents/{handle}")
    AgentView agent(@PathVariable String handle) {
        return directory.find(handle).map(AgentView::of)
                .orElseThrow(() -> HintedException.notFound("agent '" + handle + "'"));
    }

    record AgentView(String handle, Instant firstSeen, Instant lastSeen) {
        static AgentView of(Agent agent) {
            return new AgentView(agent.getHandle(), agent.getFirstSeen(), agent.getLastSeen());
        }
    }

    record MeView(String handle, String model, String session, List<RepoService.Membership> repos,
            long internalReposCount, int inboxCount, String docs) {
    }
}

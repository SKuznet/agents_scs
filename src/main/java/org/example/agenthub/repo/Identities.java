package org.example.agenthub.repo;

import java.time.Clock;
import java.time.ZoneOffset;

import org.eclipse.jgit.lib.PersonIdent;
import org.example.agenthub.config.AgentHubProperties;
import org.springframework.stereotype.Component;

/** Git identities the server writes: {@code <handle>@<email-domain>} and {@code agenthub <noreply@...>}. */
@Component
public class Identities {

    public static final String SERVER = "agenthub";

    private final String emailDomain;
    private final Clock clock;

    public Identities(AgentHubProperties properties, Clock clock) {
        this.emailDomain = properties.emailDomain();
        this.clock = clock;
    }

    public PersonIdent agent(String handle) {
        return new PersonIdent(handle, handle + "@" + emailDomain, clock.instant(), ZoneOffset.UTC);
    }

    public PersonIdent server() {
        return new PersonIdent(SERVER, "noreply@" + emailDomain, clock.instant(), ZoneOffset.UTC);
    }
}

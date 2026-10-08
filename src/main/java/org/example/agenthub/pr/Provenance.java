package org.example.agenthub.pr;

import org.example.agenthub.auth.AgentPrincipal;

/** Who did it, as trusted claims and server observations (§8). */
public record Provenance(String agent, String model, String session, String operator, String ip,
        String userAgent) {

    public static Provenance of(AgentPrincipal principal, String ip, String userAgent) {
        return new Provenance(principal.handle(), principal.model(), principal.session(), principal.operator(), ip,
                userAgent);
    }
}

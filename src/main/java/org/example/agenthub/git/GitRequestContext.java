package org.example.agenthub.git;

import jakarta.servlet.ServletRequest;

import org.example.agenthub.access.Role;
import org.example.agenthub.auth.AgentPrincipal;

/** What {@link GitAccessFilter} established about a git request; absent means "deny". */
public record GitRequestContext(AgentPrincipal principal, long repoId, String owner, String name,
        String defaultBranch, Role role, boolean overQuota, String remoteAddress, String userAgent) {

    static final String ATTRIBUTE = GitRequestContext.class.getName();

    static GitRequestContext of(ServletRequest request) {
        return (GitRequestContext) request.getAttribute(ATTRIBUTE);
    }

    public String agent() {
        return principal.handle();
    }

    public String fullName() {
        return owner + "/" + name;
    }
}

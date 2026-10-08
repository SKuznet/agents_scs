package org.example.agenthub.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.example.agenthub.access.RepoAccessService;
import org.example.agenthub.access.Role;
import org.example.agenthub.auth.AgentDirectory;
import org.example.agenthub.auth.AgentPrincipal;
import org.example.agenthub.auth.AuthFilter;
import org.example.agenthub.auth.BearerTokens;
import org.example.agenthub.auth.JwsVerifier;
import org.example.agenthub.auth.TokenRejectedException;
import org.example.agenthub.repo.Repo;
import org.example.agenthub.web.ErrorCode;
import org.springframework.http.HttpHeaders;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Deny-by-default gate in front of {@code GitServlet} (§6.5). Refusals are written directly as
 * {@code text/plain} (never {@code sendError}, which Spring would replace with its error page), so git shows
 * them to the agent as {@code remote:} lines.
 */
public class GitAccessFilter extends OncePerRequestFilter {

    private static final String UPLOAD_PACK = "git-upload-pack";
    private static final String RECEIVE_PACK = "git-receive-pack";

    private final JwsVerifier verifier;
    private final AgentDirectory directory;
    private final RepoResolver resolver;
    private final RepoAccessService access;
    private final GitTrafficLocks traffic;
    private final long maxRepoSize;
    private final String publicUrl;
    private final String setupUrl;

    public GitAccessFilter(JwsVerifier verifier, AgentDirectory directory, RepoResolver resolver,
            RepoAccessService access, GitTrafficLocks traffic, DataSize maxRepoSize, String publicUrl) {
        this.verifier = verifier;
        this.directory = directory;
        this.resolver = resolver;
        this.access = access;
        this.traffic = traffic;
        this.maxRepoSize = maxRepoSize.toBytes();
        this.publicUrl = publicUrl;
        this.setupUrl = publicUrl + "/v1/setup";
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = BearerTokens.bearerOrBasicPassword(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (token == null) {
            // without a Basic challenge git would never send credentials
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"agenthub\"");
            refuse(response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "authentication required",
                    "configure git as described in GET " + setupUrl);
            return;
        }
        AgentPrincipal principal;
        try {
            principal = verifier.verify(token);
        } catch (TokenRejectedException e) {
            // 403, not 401: on 401 git would run a credential helper and drop this explanation
            refuse(response, HttpServletResponse.SC_FORBIDDEN, e.code(), e.getMessage(),
                    AuthFilter.tokenHint(e.code()));
            return;
        }
        directory.touch(principal.handle());

        String[] path = splitPath(request.getPathInfo());
        if (path == null) {
            refuse(response, HttpServletResponse.SC_NOT_FOUND, ErrorCode.NOT_FOUND, "no repository in this URL",
                    "clone URLs look like " + publicUrl + "/git/<owner>/<name>.git");
            return;
        }
        String owner = path[0];
        String name = RepoResolver.withoutDotGit(path[1]);
        Repo repo = resolver.resolve(owner, name).orElse(null);
        Role role = repo == null ? null : access.roleOf(principal.handle(), repo).orElse(null);
        if (role == null) {
            var notFound = RepoAccessService.notFound(owner, name);
            refuse(response, HttpServletResponse.SC_NOT_FOUND, notFound.code(), notFound.getMessage(), notFound.hint());
            return;
        }

        String service = smartService(request, path[2]);
        if (service == null) {
            refuse(response, HttpServletResponse.SC_NOT_FOUND, ErrorCode.NOT_FOUND,
                    "only the smart HTTP protocol is served (dumb HTTP is disabled)",
                    "use a git client with smart HTTP support (any git since 1.6.6)");
            return;
        }
        if (service.equals(RECEIVE_PACK) && !role.atLeast(Role.PROPOSE)) {
            var denied = RepoAccessService.insufficientRole(repo, principal.handle(), role, Role.PROPOSE);
            refuse(response, HttpServletResponse.SC_FORBIDDEN, denied.code(),
                    "pushing to " + repo.getFullName() + " needs PROPOSE (pull requests) or WRITE (branches);"
                            + " you have " + role,
                    denied.hint());
            return;
        }

        // §6.5: an over-quota repository still takes pushes, but only deletions (see RefPolicyHook)
        boolean overQuota = repo.getSizeBytes() >= maxRepoSize;
        request.setAttribute(GitRequestContext.ATTRIBUTE, new GitRequestContext(principal, repo.getId(),
                repo.getOwner(), repo.getName(), repo.getDefaultBranch(), role, overQuota, request.getRemoteAddr(),
                request.getHeader(HttpHeaders.USER_AGENT)));
        if (!traffic.acquireRead(repo.getId())) {
            refuse(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, ErrorCode.BUSY,
                    "repository maintenance is in progress", "retry in a minute");
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            traffic.releaseRead(repo.getId());
        }
    }

    /** {@code /owner/name[.git]/rest...} into {@code [owner, name, rest]}. */
    private static String[] splitPath(String pathInfo) {
        if (pathInfo == null) {
            return null;
        }
        String[] parts = pathInfo.replaceFirst("^/+", "").split("/", 3);
        return parts.length == 3 && !parts[2].isEmpty() ? parts : null;
    }

    private static String smartService(HttpServletRequest request, String rest) {
        String method = request.getMethod();
        if ("info/refs".equals(rest) && "GET".equals(method)) {
            String service = request.getParameter("service");
            return UPLOAD_PACK.equals(service) || RECEIVE_PACK.equals(service) ? service : null;
        }
        if ("POST".equals(method) && (UPLOAD_PACK.equals(rest) || RECEIVE_PACK.equals(rest))) {
            return rest;
        }
        return null;
    }

    private static void refuse(HttpServletResponse response, int status, ErrorCode code, String detail, String hint)
            throws IOException {
        response.setStatus(status);
        response.setContentType("text/plain;charset=UTF-8");
        String body = code.code() + ": " + detail + "\n" + (hint == null ? "" : "hint: " + hint + "\n");
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }
}

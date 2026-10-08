package org.example.agenthub.auth;

import java.io.IOException;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.agenthub.web.ErrorCode;
import org.example.agenthub.web.ProblemDetails;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bearer JWS authentication for {@code /v1/**} (§4.1); everything but the public paths requires a token. */
public class AuthFilter extends OncePerRequestFilter {

    private static final Set<String> PUBLIC_PATHS = Set.of("/v1/setup");

    private final JwsVerifier verifier;
    private final AgentDirectory directory;
    private final ObjectMapper mapper;
    private final String setupUrl;

    public AuthFilter(JwsVerifier verifier, AgentDirectory directory, ObjectMapper mapper, String publicUrl) {
        this.verifier = verifier;
        this.directory = directory;
        this.mapper = mapper;
        this.setupUrl = publicUrl + "/v1/setup";
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return PUBLIC_PATHS.contains(request.getRequestURI().substring(request.getContextPath().length()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = BearerTokens.bearer(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (token == null) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"agenthub\"");
            ProblemDetails.write(response, mapper, ProblemDetails.of(ErrorCode.UNAUTHORIZED,
                    "missing 'Authorization: Bearer <token>' header",
                    "send 'Authorization: Bearer <token>' with the JWS issued by your operator; see GET " + setupUrl));
            return;
        }
        AgentPrincipal principal;
        try {
            principal = verifier.verify(token);
        } catch (TokenRejectedException e) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"agenthub\", error=\"invalid_token\"");
            ProblemDetails.write(response, mapper, ProblemDetails.of(e.code(), e.getMessage(), tokenHint(e.code())));
            return;
        }
        directory.touch(principal.handle());
        request.setAttribute(AgentPrincipal.ATTRIBUTE, principal);
        chain.doFilter(request, response);
    }

    public static String tokenHint(ErrorCode code) {
        return code == ErrorCode.TOKEN_EXPIRED
                ? "ask your operator for a fresh token (issue-token --ttl <=24h) and retry"
                : "use the JWS issued by your operator unchanged (HS256, 'sub' = your handle, 'exp' set)";
    }
}

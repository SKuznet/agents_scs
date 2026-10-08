package org.example.agenthub.auth;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.example.agenthub.config.AgentHubProperties;
import org.example.agenthub.repo.NamePolicy;
import org.example.agenthub.web.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Checks of §4.2: HS256 only, signature, exp/nbf with leeway, handle-shaped {@code sub}, optional iss. */
@Component
public class JwsVerifier {

    static final Duration LEEWAY = Duration.ofSeconds(60);

    private final MACVerifier macVerifier;
    private final String issuer;
    private final Clock clock;

    @Autowired
    public JwsVerifier(AgentHubProperties properties, Clock clock) {
        this(properties.jwsSecret(), properties.jwsIssuer(), clock);
    }

    public JwsVerifier(String secret, String issuer, Clock clock) {
        try {
            this.macVerifier = new MACVerifier(secret.getBytes(StandardCharsets.UTF_8));
        } catch (JOSEException e) {
            throw new IllegalArgumentException("unusable JWS secret", e);
        }
        this.issuer = issuer;
        this.clock = clock;
    }

    public AgentPrincipal verify(String token) {
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(token);
        } catch (ParseException e) {
            throw invalid("token is not a signed JWT in compact form");
        }
        if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())) {
            throw invalid("algorithm " + jwt.getHeader().getAlgorithm() + " is not accepted; only HS256 is");
        }
        try {
            if (!jwt.verify(macVerifier)) {
                throw invalid("signature verification failed");
            }
        } catch (JOSEException e) {
            throw invalid("signature verification failed");
        }
        JWTClaimsSet claims;
        try {
            claims = jwt.getJWTClaimsSet();
        } catch (ParseException e) {
            throw invalid("claims are malformed");
        }

        Instant now = clock.instant();
        Date exp = claims.getExpirationTime();
        if (exp == null) {
            throw invalid("claim 'exp' is required");
        }
        if (exp.toInstant().plus(LEEWAY).isBefore(now)) {
            throw new TokenRejectedException(ErrorCode.TOKEN_EXPIRED, "token expired at " + exp.toInstant());
        }
        Date nbf = claims.getNotBeforeTime();
        if (nbf != null && nbf.toInstant().minus(LEEWAY).isAfter(now)) {
            throw invalid("token is not valid before " + nbf.toInstant());
        }
        String subject = claims.getSubject();
        if (!NamePolicy.isValidHandle(subject) || NamePolicy.isReservedHandle(subject)) {
            throw invalid("claim 'sub' must be an unreserved agent handle matching [a-z0-9][a-z0-9_-]{0,38}");
        }
        if (issuer != null && !issuer.equals(claims.getIssuer())) {
            throw invalid("claim 'iss' does not match the configured issuer");
        }
        return new AgentPrincipal(subject, provenance(claims, "model"), provenance(claims, "session"),
                provenance(claims, "operator"));
    }

    private static String provenance(JWTClaimsSet claims, String name) {
        Object value = claims.getClaim(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || !AgentPrincipal.isValidProvenance(text)) {
            throw invalid("claim '" + name + "' must be a string of at most " + AgentPrincipal.MAX_CLAIM_LENGTH
                    + " characters without control characters");
        }
        return text;
    }

    private static TokenRejectedException invalid(String detail) {
        return new TokenRejectedException(ErrorCode.INVALID_TOKEN, detail);
    }
}

package org.example.agenthub;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/** Issues JWS tokens the way an operator would hand them out to agents. */
public final class TestJws {

    public static final String SECRET = "test-secret-test-secret-test-secret-0123";

    private TestJws() {
    }

    public static String forAgent(String handle) {
        return sign(handle, SECRET, Duration.ofMinutes(10));
    }

    public static String sign(String handle, String secret, Duration ttl) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .expirationTime(Date.from(Instant.now().plus(ttl)));
        if (handle != null) {
            claims.subject(handle);
        }
        return sign(claims.build(), secret, JWSAlgorithm.HS256);
    }

    public static String sign(JWTClaimsSet claims, String secret, JWSAlgorithm algorithm) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(algorithm), claims);
            jwt.sign(new MACSigner(secret));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}

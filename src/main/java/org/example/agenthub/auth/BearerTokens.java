package org.example.agenthub.auth;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Extracts the JWS from {@code Authorization}: Bearer everywhere, Basic (password = JWS) for git (§4.1). */
public final class BearerTokens {

    private BearerTokens() {
    }

    public static String bearer(String authorization) {
        return credential(authorization, "Bearer ");
    }

    public static String bearerOrBasicPassword(String authorization) {
        String bearer = bearer(authorization);
        if (bearer != null) {
            return bearer;
        }
        String basic = credential(authorization, "Basic ");
        if (basic == null) {
            return null;
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(basic), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "";
        }
        int colon = decoded.indexOf(':');
        return colon < 0 ? "" : decoded.substring(colon + 1);
    }

    private static String credential(String authorization, String scheme) {
        if (authorization == null || !authorization.regionMatches(true, 0, scheme, 0, scheme.length())) {
            return null;
        }
        return authorization.substring(scheme.length()).trim();
    }
}

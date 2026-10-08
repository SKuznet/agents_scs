package org.example.agenthub.auth;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.example.agenthub.repo.NamePolicy;

/**
 * {@code java -jar agenthub.jar issue-token ...} (§4.3). Runs without a Spring context; the secret comes only
 * from {@code AGENTHUB_JWS_SECRET}. {@code --iss} is an addition for servers configured with
 * {@code agenthub.jws-issuer}, which would otherwise reject every issued token.
 */
public final class TokenIssuerCli {

    public static final String COMMAND = "issue-token";
    static final String SECRET_ENV = "AGENTHUB_JWS_SECRET";
    static final String USAGE = "usage: java -jar agenthub.jar issue-token --sub <handle> --ttl <90s|30m|8h|1d>"
            + " [--model <model>] [--session <session>] [--operator <operator>] [--iss <issuer>]";

    private static final Set<String> OPTIONS = Set.of("sub", "ttl", "model", "session", "operator", "iss");
    private static final Set<String> PROVENANCE = Set.of("model", "session", "operator");
    private static final Pattern TTL = Pattern.compile("([1-9][0-9]{0,6})([smhd])");

    private TokenIssuerCli() {
    }

    public static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            String name = args[i].startsWith("--") ? args[i].substring(2) : "";
            if (!OPTIONS.contains(name) || i + 1 >= args.length) {
                return fail(err, "unknown or incomplete option: " + args[i]);
            }
            options.put(name, args[i + 1]);
        }

        String secret = env.get(SECRET_ENV);
        if (secret == null || secret.isEmpty()) {
            return fail(err, SECRET_ENV + " is not set (the secret is read only from the environment)");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            return fail(err, SECRET_ENV + " must be at least 32 bytes");
        }
        String sub = options.get("sub");
        if (!NamePolicy.isValidHandle(sub) || NamePolicy.isReservedHandle(sub)) {
            return fail(err, "--sub must be an unreserved handle matching [a-z0-9][a-z0-9_-]{0,38}");
        }
        Duration ttl = parseTtl(options.get("ttl"));
        if (ttl == null) {
            return fail(err, "--ttl must look like 90s, 30m, 8h or 1d");
        }

        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject(sub)
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plus(ttl)));
        for (String claim : PROVENANCE) {
            String value = options.get(claim);
            if (value != null) {
                if (!AgentPrincipal.isValidProvenance(value)) {
                    return fail(err, "--" + claim + " must be at most 128 characters without control characters");
                }
                claims.claim(claim, value);
            }
        }
        if (options.containsKey("iss")) {
            claims.issuer(options.get("iss"));
        }

        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            out.println(jwt.serialize());
            return 0;
        } catch (JOSEException e) {
            return fail(err, "cannot sign token: " + e.getMessage());
        }
    }

    private static Duration parseTtl(String value) {
        Matcher m = value == null ? null : TTL.matcher(value);
        if (m == null || !m.matches()) {
            return null;
        }
        long amount = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            default -> Duration.ofDays(amount);
        };
    }

    private static int fail(PrintStream err, String message) {
        err.println("error: " + message);
        err.println(USAGE);
        return 2;
    }
}

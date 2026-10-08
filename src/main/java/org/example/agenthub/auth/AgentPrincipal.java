package org.example.agenthub.auth;

/** The authenticated agent: trusted {@code sub} plus optional provenance claims (§4.2, §8). */
public record AgentPrincipal(String handle, String model, String session, String operator) {

    public static final String ATTRIBUTE = AgentPrincipal.class.getName();

    static final int MAX_CLAIM_LENGTH = 128;

    /** Provenance claims end up in commit trailers, so a control character could forge a trailer. */
    static boolean isValidProvenance(String value) {
        return value.length() <= MAX_CLAIM_LENGTH && value.chars().noneMatch(c -> c < 0x20 || c == 0x7f);
    }
}

package org.example.agenthub.web;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A refusal that tells the agent what to do next (§9.2); rendered as a ProblemDetail by {@link ProblemDetails}. */
public class HintedException extends RuntimeException {

    private final ErrorCode code;
    private final String hint;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public HintedException(ErrorCode code, String detail, String hint) {
        super(detail);
        this.code = code;
        this.hint = hint;
    }

    public static HintedException notFound(String what) {
        return new HintedException(ErrorCode.NOT_FOUND, what + " not found", null);
    }

    public HintedException with(String property, Object value) {
        properties.put(property, value);
        return this;
    }

    public ErrorCode code() {
        return code;
    }

    public String hint() {
        return hint;
    }

    public Map<String, Object> properties() {
        return Collections.unmodifiableMap(properties);
    }
}

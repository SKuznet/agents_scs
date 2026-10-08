package org.example.agenthub.events;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

/** How a long-poll of §9.5 ended. */
public enum WaitResult {
    SATISFIED, TERMINAL, TIMEOUT;

    @JsonValue
    String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}

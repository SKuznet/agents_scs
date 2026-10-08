package org.example.agenthub.access;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** §5.3: {@code private} — members only; {@code internal} — every agent has READ. */
public enum Visibility {
    PRIVATE, INTERNAL;

    @JsonValue
    public String json() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static Visibility fromJson(String value) {
        for (Visibility visibility : values()) {
            if (visibility.json().equalsIgnoreCase(value)) {
                return visibility;
            }
        }
        throw new IllegalArgumentException("visibility must be 'private' or 'internal'");
    }
}

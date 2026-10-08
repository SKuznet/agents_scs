package org.example.agenthub.pr;

/** §7.4: {@code UNKNOWN} until computed, or when computed against an older tip of the target. */
public enum Mergeable {
    CLEAN, CONFLICT, ERROR, UNKNOWN
}

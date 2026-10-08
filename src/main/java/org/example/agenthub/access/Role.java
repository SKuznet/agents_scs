package org.example.agenthub.access;

/** Ordered roles of §5.1: each includes the previous ones. */
public enum Role {
    READ, PROPOSE, WRITE, ADMIN;

    public boolean atLeast(Role required) {
        return compareTo(required) >= 0;
    }

    public static Role max(Role a, Role b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.atLeast(b) ? a : b;
    }
}

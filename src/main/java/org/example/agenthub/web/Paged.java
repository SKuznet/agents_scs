package org.example.agenthub.web;

import java.util.List;
import java.util.function.Function;

/** Cursor page: {@code {items, nextCursor}}; {@code nextCursor} is null on the last page. */
public record Paged<T>(List<T> items, String nextCursor) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    public static int limit(Integer requested) {
        if (requested == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(MAX_LIMIT, requested));
    }

    /** Builds a page from {@code limit + 1} fetched rows; the extra row only signals that more exist. */
    public static <E, T> Paged<T> of(List<E> rows, int limit, Function<E, String> cursorOf, Function<E, T> view) {
        boolean more = rows.size() > limit;
        List<E> page = more ? rows.subList(0, limit) : rows;
        String next = more ? cursorOf.apply(page.get(page.size() - 1)) : null;
        return new Paged<>(page.stream().map(view).toList(), next);
    }
}

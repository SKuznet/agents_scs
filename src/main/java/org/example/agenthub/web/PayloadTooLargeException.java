package org.example.agenthub.web;

import java.io.IOException;

/** An IOException so that it surfaces through message converters as the cause of an unreadable body. */
public class PayloadTooLargeException extends IOException {

    private final long limit;

    public PayloadTooLargeException(long limit) {
        super("request body exceeds " + limit + " bytes");
        this.limit = limit;
    }

    public long limit() {
        return limit;
    }
}

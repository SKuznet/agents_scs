package org.example.agenthub.auth;

import org.example.agenthub.web.ErrorCode;

public class TokenRejectedException extends RuntimeException {

    private final ErrorCode code;

    public TokenRejectedException(ErrorCode code, String detail) {
        super(detail);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}

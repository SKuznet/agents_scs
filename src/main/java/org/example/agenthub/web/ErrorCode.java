package org.example.agenthub.web;

import java.util.Locale;

import org.springframework.http.HttpStatus;

/** Error codes of §14; {@link #code()} is what clients see in {@code code} and in git rejections. */
public enum ErrorCode {
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "Invalid token"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Token expired"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Not found"),
    INSUFFICIENT_ROLE(HttpStatus.FORBIDDEN, "Insufficient role"),
    SELF_REVIEW(HttpStatus.FORBIDDEN, "Cannot review own pull request"),
    APPROVALS_REQUIRED(HttpStatus.FORBIDDEN, "Approvals required"),
    PROTECTED_BRANCH(HttpStatus.FORBIDDEN, "Protected branch"),
    QUOTA_EXCEEDED(HttpStatus.FORBIDDEN, "Quota exceeded"),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
    INVALID_NAME(HttpStatus.BAD_REQUEST, "Invalid name"),
    INVALID_REF_NAME(HttpStatus.BAD_REQUEST, "Invalid ref name"),
    RESERVED_REF(HttpStatus.BAD_REQUEST, "Reserved ref"),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "Payload too large"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
    STALE_HEAD(HttpStatus.CONFLICT, "PR head moved"),
    STALE_REVISION(HttpStatus.CONFLICT, "Stale revision"),
    CONFLICT(HttpStatus.CONFLICT, "Merge conflict"),
    CONCURRENT_UPDATE(HttpStatus.CONFLICT, "Concurrent update"),
    ALREADY_EXISTS(HttpStatus.CONFLICT, "Already exists"),
    NOT_OPEN(HttpStatus.CONFLICT, "Pull request is not open"),
    DRAFT(HttpStatus.CONFLICT, "Pull request is a draft"),
    UNRELATED_HISTORY(HttpStatus.CONFLICT, "Unrelated history"),
    NO_NEW_COMMITS(HttpStatus.CONFLICT, "No new commits"),
    NO_EFFECTIVE_CHANGES(HttpStatus.CONFLICT, "No effective changes"),
    LINE_OUT_OF_RANGE(HttpStatus.UNPROCESSABLE_ENTITY, "Line out of range"),
    TOO_MANY_WAITS(HttpStatus.TOO_MANY_REQUESTS, "Too many waits"),
    BUSY(HttpStatus.SERVICE_UNAVAILABLE, "Repository busy"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error"),
    // git-only (REJECTED_OTHER_REASON); status is nominal
    UNKNOWN_PUSH_OPTION(HttpStatus.BAD_REQUEST, "Unknown push option"),
    ATOMIC_MIXED_PUSH(HttpStatus.BAD_REQUEST, "Atomic push mixes pull requests and refs"),
    TOO_MANY_COMMANDS(HttpStatus.BAD_REQUEST, "Too many ref updates in one push"),
    NOT_A_COMMIT(HttpStatus.BAD_REQUEST, "Not a commit");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }
}

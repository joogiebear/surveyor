package xyz.joogiebear.surveyor.core.agent;

/** The error codes an agent sees in a result's {@code errors} list. */
public enum ErrorCode {
    UNAUTHORIZED, OUT_OF_SCOPE, REGION_DENIED, LIMIT_EXCEEDED, RATE_LIMITED,
    INVALID_ARGUMENT, NOT_FOUND, CONFLICT, INTERNAL
}

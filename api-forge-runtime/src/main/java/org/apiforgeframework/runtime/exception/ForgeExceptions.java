package org.apiforgeframework.runtime.exception;

import org.springframework.http.HttpStatus;

// ────────────────────────────────────────────────────────────────────────────
// Ingress-side exceptions (thrown from service implementations)
// ────────────────────────────────────────────────────────────────────────────

/**
 * Throw when a requested resource does not exist.
 * Maps to HTTP 404 Not Found.
 *
 * <pre>{@code
 * throw new ForgeResourceNotFoundException("Order not found: " + orderId);
 * }</pre>
 */
class ForgeResourceNotFoundException extends ForgeException {
    public ForgeResourceNotFoundException(String message) {
        super("NOT_FOUND", message, HttpStatus.NOT_FOUND);
    }
}

/**
 * Throw when a resource already exists or a uniqueness constraint is violated.
 * Maps to HTTP 409 Conflict.
 */
class ForgeResourceConflictException extends ForgeException {
    public ForgeResourceConflictException(String message) {
        super("CONFLICT", message, HttpStatus.CONFLICT);
    }
}

/**
 * Throw for business-rule validation failures not caught by Jakarta Bean Validation.
 * Maps to HTTP 422 Unprocessable Entity.
 */
class ForgeBusinessValidationException extends ForgeException {
    public ForgeBusinessValidationException(String message) {
        super("BUSINESS_VALIDATION_FAILED", message, HttpStatus.UNPROCESSABLE_ENTITY);
    }
}

/**
 * Throw when the caller lacks permission to perform the requested operation.
 * Maps to HTTP 403 Forbidden.
 */
class ForgeForbiddenException extends ForgeException {
    public ForgeForbiddenException(String message) {
        super("FORBIDDEN", message, HttpStatus.FORBIDDEN);
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Egress-side exceptions (thrown from generated WebClient clients)
// ────────────────────────────────────────────────────────────────────────────

/**
 * Wraps HTTP errors from upstream services.
 * Contains the upstream status code and raw response body for logging.
 */
class ForgeEgressException extends ForgeException {

    private final int upstreamStatus;
    private final String upstreamBody;

    public ForgeEgressException(String clientName, int upstreamStatus, String upstreamBody) {
        super(
            "EGRESS_ERROR",
            String.format("Upstream '%s' returned %d", clientName, upstreamStatus),
            upstreamStatus >= 500 ? HttpStatus.BAD_GATEWAY : HttpStatus.UNPROCESSABLE_ENTITY
        );
        this.upstreamStatus = upstreamStatus;
        this.upstreamBody = upstreamBody;
    }

    public ForgeEgressException(String clientName, Throwable cause) {
        super("EGRESS_UNAVAILABLE",
              String.format("Upstream '%s' is unavailable: %s", clientName, cause.getMessage()),
              HttpStatus.SERVICE_UNAVAILABLE, cause);
        this.upstreamStatus = -1;
        this.upstreamBody = null;
    }

    public int getUpstreamStatus() { return upstreamStatus; }
    public String getUpstreamBody() { return upstreamBody; }

    /** Factory for use inside WebClient error handlers. */
    public static ForgeEgressException clientError(String clientName, int status, String body) {
        return new ForgeEgressException(clientName, status, body);
    }
}

/**
 * Thrown when Resilience4j circuit breaker is OPEN for an egress client.
 * The caller may handle this with a {@link org.apiforgeframework.annotations.ForgeFallback}.
 */
class ForgeCircuitOpenException extends ForgeException {
    public ForgeCircuitOpenException(String clientName) {
        super("CIRCUIT_OPEN",
              String.format("Circuit breaker is OPEN for client '%s'", clientName),
              HttpStatus.SERVICE_UNAVAILABLE);
    }
}

package org.apiforgeframework.runtime.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micrometer.tracing.Tracer;
import jakarta.validation.ConstraintViolationException;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Base exception handler for all API-Forge-managed controllers.
 *
 * <p>Handles the generated exception hierarchy and common Spring MVC exceptions.
 * Developers may extend this class with {@code @ControllerAdvice(order = HIGHEST_PRECEDENCE)}
 * to add application-specific handlers that take priority, or may add handlers to their
 * own {@code @ControllerAdvice} alongside this one (Spring calls all applicable handlers).
 *
 * <p>Response shape:
 * <pre>{@code
 * {
 *   "code": "NOT_FOUND",
 *   "message": "Order not found: order_abc",
 *   "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
 *   "timestamp": "2024-01-15T10:30:00Z",
 *   "fieldErrors": [...]   // only present for validation failures
 * }
 * }</pre>
 */
@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class ForgeExceptionHandler {

    private final Tracer tracer;

    // ── Forge domain exceptions ───────────────────────────────────────────────

    @ExceptionHandler(ForgeResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ForgeResourceNotFoundException ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(errorResponse(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(ForgeResourceConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(ForgeResourceConflictException ex) {
        log.warn("Resource conflict: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(errorResponse(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(ForgeBusinessValidationException.class)
    public ResponseEntity<ErrorResponse> handleBusinessValidation(ForgeBusinessValidationException ex) {
        log.warn("Business validation failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(errorResponse(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(ForgeForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(ForgeForbiddenException ex) {
        log.warn("Forbidden: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(errorResponse(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(ForgeEgressException.class)
    public ResponseEntity<ErrorResponse> handleEgressError(ForgeEgressException ex) {
        log.error("Egress error: {} (upstream status={})", ex.getMessage(), ex.getUpstreamStatus(), ex);
        return ResponseEntity.status(ex.getHttpStatus())
                .body(errorResponse(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(ForgeCircuitOpenException.class)
    public ResponseEntity<ErrorResponse> handleCircuitOpen(ForgeCircuitOpenException ex) {
        log.warn("Circuit open: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(errorResponse(ex.getErrorCode(), ex.getMessage()));
    }

    // ── Spring MVC / Validation exceptions ───────────────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBeanValidation(MethodArgumentNotValidException ex) {
        List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> FieldError.of(fe.getField(), fe.getDefaultMessage(), fe.getRejectedValue()))
                .collect(Collectors.toList());

        log.debug("Validation failed: {} field error(s)", fieldErrors.size());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.builder()
                        .code("VALIDATION_FAILED")
                        .message("Request validation failed")
                        .traceId(currentTraceId())
                        .timestamp(Instant.now().toString())
                        .fieldErrors(fieldErrors)
                        .build());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        List<FieldError> fieldErrors = ex.getConstraintViolations().stream()
                .map(cv -> FieldError.of(cv.getPropertyPath().toString(), cv.getMessage(), cv.getInvalidValue()))
                .collect(Collectors.toList());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.builder()
                        .code("CONSTRAINT_VIOLATION")
                        .message("Constraint violation")
                        .traceId(currentTraceId())
                        .timestamp(Instant.now().toString())
                        .fieldErrors(fieldErrors)
                        .build());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String msg = String.format("Parameter '%s' has invalid value: '%s'", ex.getName(), ex.getValue());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(errorResponse("INVALID_PARAMETER", msg));
    }

    // ── Catch-all ────────────────────────────────────────────────────────────

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorResponse("INTERNAL_ERROR", "An unexpected error occurred"));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ErrorResponse errorResponse(String code, String message) {
        return ErrorResponse.builder()
                .code(code)
                .message(message)
                .traceId(currentTraceId())
                .timestamp(Instant.now().toString())
                .build();
    }

    private String currentTraceId() {
        if (tracer == null || tracer.currentSpan() == null) return null;
        return tracer.currentSpan().context().traceId();
    }

    // ── Response model ────────────────────────────────────────────────────────

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ErrorResponse {
        private String code;
        private String message;
        private String traceId;
        private String timestamp;
        private List<FieldError> fieldErrors;
    }

    @Data
    @Builder
    public static class FieldError {
        private String field;
        private String message;
        private Object rejectedValue;

        public static FieldError of(String field, String message, Object rejectedValue) {
            return FieldError.builder()
                    .field(field)
                    .message(message)
                    .rejectedValue(rejectedValue)
                    .build();
        }
    }
}

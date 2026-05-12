package org.apiforgeframework.runtime.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Root of the API-Forge exception hierarchy.
 *
 * <p>All exceptions thrown from generated controllers or egress clients
 * extend this class, enabling a single catch block in cross-cutting concerns.
 *
 * <p>Generated {@code ForgeExceptionHandler} maps subclasses to HTTP status codes.
 * Developers may throw these from service implementations or throw their own
 * exceptions and register a custom {@code @ControllerAdvice} handler.
 */
@Getter
public abstract class ForgeException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus httpStatus;

    protected ForgeException(String errorCode, String message, HttpStatus httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    protected ForgeException(String errorCode, String message, HttpStatus httpStatus, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }
}

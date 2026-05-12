package org.apiforgeframework.plugin.generator;

/**
 * Common exception for all API-Forge code generation errors.
 */
public class ForgeGenerationException extends RuntimeException {

    public ForgeGenerationException(String message) {
        super(message);
    }

    public ForgeGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
package org.apiforgeframework.annotations;

import java.lang.annotation.*;

/**
 * Binds a generated controller method to its OpenAPI {@code operationId}.
 * Drives OTel span naming and structured log fields.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ForgeOperation {
    /** The operationId as declared in the OpenAPI spec. */
    String operationId();
    /** HTTP method for span tagging. */
    String httpMethod() default "";
    /** Path template for span tagging. */
    String path() default "";
}

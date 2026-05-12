package org.apiforgeframework.annotations;

import java.lang.annotation.*;

/**
 * Overrides the default Resilience4j retry configuration for a specific
 * generated egress client method.
 *
 * <p>Place on the generated client method or on an overriding subclass method.
 * Per-method config takes precedence over the global {@code forge.egress.<name>.resilience.retry} block.
 *
 * <pre>{@code
 * // In a custom subclass of InventoryApiClient (advanced use case)
 * @ForgeRetryable(maxAttempts = 5, waitMs = 200, exponential = true)
 * @Override
 * public ReservationResponse reserveStock(List<StockReservationRequest> items) {
 *     return super.reserveStock(items);
 * }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ForgeRetryable {
    int maxAttempts() default 3;
    long waitMs() default 500;
    boolean exponential() default true;
    double multiplier() default 2.0;
    long maxWaitMs() default 5000;
    /** Exception types that trigger a retry. Defaults to all exceptions. */
    Class<? extends Throwable>[] retryOn() default {Exception.class};
    /** Exception types that should NOT trigger a retry. */
    Class<? extends Throwable>[] ignoreOn() default {};
}

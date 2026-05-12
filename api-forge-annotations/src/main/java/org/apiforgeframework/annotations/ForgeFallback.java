package org.apiforgeframework.annotations;

import java.lang.annotation.*;

/**
 * Designates a developer-written fallback method for a Resilience4j-protected
 * egress operation.
 *
 * <p>The annotated method must:
 * <ol>
 *   <li>Be in the same class as the service implementation</li>
 *   <li>Match the signature of the target client method</li>
 *   <li>Accept a trailing {@code Throwable} parameter</li>
 *   <li>Return the same type as the target method</li>
 * </ol>
 *
 * <pre>{@code
 * @ForgeFallback(
 *     forClient = InventoryApiClient.class,
 *     forMethod = "reserveStock"
 * )
 * public ReservationResponse reserveStockFallback(
 *         List<StockReservationRequest> items,
 *         Throwable cause) {
 *     log.warn("Inventory unavailable, using async fallback", cause);
 *     outbox.enqueue(new PendingReservation(items));
 *     return ReservationResponse.pending();
 * }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ForgeFallback {

    /** The egress client class whose method this is a fallback for. */
    Class<?> forClient();

    /** The method name in the client class to fall back from. */
    String forMethod();
}

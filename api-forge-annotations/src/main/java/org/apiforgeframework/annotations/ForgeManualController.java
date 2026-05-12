package org.apiforgeframework.annotations;

import java.lang.annotation.*;

/**
 * Escape hatch — suppresses API-Forge controller generation for specific
 * operationIds or path patterns.
 *
 * <p>Use when the generated controller pattern cannot accommodate a specific
 * endpoint's requirements, such as:
 * <ul>
 *   <li>Server-Sent Events (SSE) streaming responses</li>
 *   <li>Multipart file upload handling</li>
 *   <li>Complex content negotiation</li>
 *   <li>Non-standard HTTP semantics</li>
 * </ul>
 *
 * <p>Place on the ingress spec configuration in the plugin, or as a per-operation
 * OpenAPI extension {@code x-forge-manual: true}.
 *
 * <pre>{@code
 * // In plugin configuration (pom.xml):
 * <manualOperations>
 *   <operation>streamOrderEvents</operation>
 *   <operation>uploadOrderAttachment</operation>
 * </manualOperations>
 *
 * // Then write the controller manually:
 * @RestController
 * @RequestMapping("/api/v1/orders")
 * public class OrderStreamController {
 *     @GetMapping(value = "/{orderId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
 *     public Flux<OrderEvent> streamOrderEvents(@PathVariable String orderId) { ... }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
@Documented
public @interface ForgeManualController {
    /** OperationIds excluded from generation. */
    String[] excludedOperations() default {};
}

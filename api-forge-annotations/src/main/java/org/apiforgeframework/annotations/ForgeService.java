package org.apiforgeframework.annotations;

import java.lang.annotation.*;

/**
 * Marks a generated service interface contract produced by api-forge-maven-plugin.
 *
 * <p>This annotation is placed on generated interfaces (e.g. {@code OrdersApiService}).
 * It carries metadata back to the plugin for incremental regeneration, and is used by
 * {@code @ForgeEgressTest} and contract-test scaffolding to locate the correct spec.
 *
 * <p>Developers should <strong>not</strong> place this annotation manually.
 * Implement the generated interface in {@code service/impl} instead.
 *
 * <pre>{@code
 * // Generated — do not edit
 * @ForgeService(spec = "orders-api.yaml")
 * public interface OrdersApiService {
 *     OrderResponse createOrder(CreateOrderRequest request);
 * }
 *
 * // Developer writes only this
 * @Service
 * public class OrdersApiServiceImpl implements OrdersApiService {
 *     public OrderResponse createOrder(CreateOrderRequest request) { ... }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)   // RUNTIME so test tooling can read it via reflection
@Documented
public @interface ForgeService {

    /**
     * Path to the OpenAPI spec file relative to the project root
     * (e.g. {@code "openapi/ingress/orders-api.yaml"}).
     */
    String spec();

    /**
     * The API version string as declared in the spec's {@code info.version}.
     * Used for documentation and manifest tracking.
     */
    String apiVersion() default "";

    /**
     * Human-readable title from the spec's {@code info.title}.
     */
    String title() default "";
}

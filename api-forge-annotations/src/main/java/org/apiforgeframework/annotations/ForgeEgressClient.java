package org.apiforgeframework.annotations;

import java.lang.annotation.*;

/**
 * Marks a generated egress (outbound) API client produced by api-forge-maven-plugin.
 *
 * <p>Annotated classes are Spring {@code @Component}s wired by
 * {@code ForgeEgressClientFactory}. They use WebClient internally and are configured
 * from {@code forge.egress.<clientName>.*} properties.
 *
 * <p>Injectable directly into service implementations:
 * <pre>{@code
 * @Service
 * @RequiredArgsConstructor
 * public class OrdersApiServiceImpl implements OrdersApiService {
 *     private final InventoryApiClient inventoryClient;   // injected — no config needed
 *     private final PaymentApiClient   paymentClient;
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ForgeEgressClient {

    /**
     * Logical client name matching the {@code forge.egress.<clientName>} config block.
     */
    String name();

    /**
     * Path to the egress OpenAPI spec file relative to the project root.
     */
    String spec();

    /**
     * Base URL property placeholder, e.g. {@code "${forge.egress.inventory.base-url}"}.
     */
    String baseUrl();

    /**
     * Authentication strategy for this client.
     */
    AuthType auth() default AuthType.NONE;

    /**
     * Whether Resilience4j circuit breaker is enabled for this client.
     */
    boolean circuitBreaker() default true;

    /**
     * Whether Resilience4j retry is enabled for this client.
     */
    boolean retry() default true;

    /**
     * Whether OpenTelemetry span propagation is enabled.
     */
    boolean tracing() default true;

    /**
     * Authentication strategies supported by API-Forge egress clients.
     */
    enum AuthType {
        NONE,
        BEARER_TOKEN,
        OAUTH2_CLIENT_CREDENTIALS,
        API_KEY_HEADER,
        API_KEY_QUERY,
        BASIC
    }
}

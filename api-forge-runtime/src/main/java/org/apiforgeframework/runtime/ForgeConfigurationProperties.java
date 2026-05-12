package org.apiforgeframework.runtime;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Root configuration properties for API-Forge runtime.
 *
 * <p>Bind via {@code forge.*} in application.yml / application.properties.
 *
 * <pre>{@code
 * forge:
 *   ingress:
 *     log-requests: true
 *   egress:
 *     inventory:
 *       base-url: https://inventory.internal
 *       timeout-ms: 3000
 *       auth:
 *         type: BEARER_TOKEN
 *         token-provider: vault
 *       resilience:
 *         circuit-breaker:
 *           failure-rate-threshold: 50
 *         retry:
 *           max-attempts: 3
 * }</pre>
 */
@Data
@ConfigurationProperties(prefix = "forge")
public class ForgeConfigurationProperties {

    private IngressProperties ingress = new IngressProperties();
    private Map<String, EgressClientProperties> egress = new HashMap<>();

    @Data
    public static class IngressProperties {
        /** Log incoming request/response pairs at DEBUG level. */
        private boolean logRequests = true;
        /** Include stack traces in error responses (disable in production). */
        private boolean exposeStackTraces = false;
        /** Default page size for paginated endpoints. */
        private int defaultPageSize = 20;
        /** Maximum page size clients may request. */
        private int maxPageSize = 200;
    }

    @Data
    public static class EgressClientProperties {
        private String baseUrl;
        private long timeoutMs = 5000;
        private int maxConnections = 500;
        private int pendingAcquireMaxCount = 1000;
        private AuthProperties auth = new AuthProperties();
        private ResilienceProperties resilience = new ResilienceProperties();
    }

    @Data
    public static class AuthProperties {
        private AuthType type = AuthType.NONE;
        /** For BEARER_TOKEN: how the token is sourced (env | vault | static | bean). */
        private String tokenProvider = "env";
        /** For BEARER_TOKEN with provider=static or env: the token value / env var name. */
        private String token;
        /** For OAUTH2: client ID. */
        private String clientId;
        /** For OAUTH2: client secret. */
        private String clientSecret;
        /** For OAUTH2: token endpoint. */
        private String tokenUrl;
        /** For API_KEY_HEADER / API_KEY_QUERY: header/param name. */
        private String keyName = "X-API-Key";
        /** For API_KEY: the key value. */
        private String keyValue;

        public enum AuthType {
            NONE, BEARER_TOKEN, OAUTH2_CLIENT_CREDENTIALS, API_KEY_HEADER, API_KEY_QUERY, BASIC
        }
    }

    @Data
    public static class ResilienceProperties {
        private CircuitBreakerProperties circuitBreaker = new CircuitBreakerProperties();
        private RetryProperties retry = new RetryProperties();
        private BulkheadProperties bulkhead = new BulkheadProperties();
        private TimeLimiterProperties timeLimiter = new TimeLimiterProperties();
    }

    @Data
    public static class CircuitBreakerProperties {
        private boolean enabled = true;
        private float failureRateThreshold = 50f;
        private float slowCallRateThreshold = 100f;
        private long slowCallDurationThresholdMs = 3000;
        private int slidingWindowSize = 10;
        private int minimumNumberOfCalls = 5;
        private long waitDurationInOpenStateMs = 30_000;
        private int permittedCallsInHalfOpenState = 3;
    }

    @Data
    public static class RetryProperties {
        private boolean enabled = true;
        private int maxAttempts = 3;
        private long waitDurationMs = 500;
        private boolean exponentialBackoff = true;
        private double exponentialMultiplier = 2.0;
        private long maxWaitDurationMs = 10_000;
    }

    @Data
    public static class BulkheadProperties {
        private boolean enabled = true;
        private int maxConcurrentCalls = 25;
        private long maxWaitDurationMs = 100;
    }

    @Data
    public static class TimeLimiterProperties {
        private boolean enabled = true;
        private Duration timeoutDuration = Duration.ofSeconds(5);
    }
}

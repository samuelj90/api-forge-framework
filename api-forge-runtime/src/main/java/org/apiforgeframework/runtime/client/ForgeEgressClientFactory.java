package org.apiforgeframework.runtime.client;

import org.apiforgeframework.runtime.ForgeAutoConfiguration;
import org.apiforgeframework.runtime.ForgeConfigurationProperties;
import org.apiforgeframework.runtime.auth.EnvForgeTokenProvider;
import org.apiforgeframework.runtime.auth.ForgeTokenProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central factory that builds and caches fully-configured {@link WebClient} instances,
 * Resilience4j {@link CircuitBreaker}s, and {@link Retry} instances for each
 * named egress client.
 *
 * <p>Generated client classes ({@code InventoryApiClient}, etc.) call methods on this
 * factory in their constructors. They should never build their own WebClient.
 *
 * <p>This class is a Spring {@code @Bean} registered by {@link ForgeAutoConfiguration}.
 */
@Slf4j
public class ForgeEgressClientFactory {

    private final ForgeConfigurationProperties props;
    private final WebClient.Builder webClientBuilder;
    private final Tracer tracer;
    private final Map<String, ForgeTokenProvider> tokenProviders;

    // Caches — one per client name
    private final ConcurrentHashMap<String, WebClient> webClients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Retry> retries = new ConcurrentHashMap<>();

    public ForgeEgressClientFactory(
            ForgeConfigurationProperties props,
            WebClient.Builder webClientBuilder,
            Tracer tracer,
            Map<String, ForgeTokenProvider> tokenProviders) {
        this.props = props;
        this.webClientBuilder = webClientBuilder;
        this.tracer = tracer;
        this.tokenProviders = tokenProviders;
    }

    // ── Public API used by generated clients ──────────────────────────────────

    /**
     * Returns a fully-configured, cached {@link WebClient} for the named client.
     * Includes auth, timeout, and logging filters.
     */
    public WebClient webClient(String clientName) {
        return webClients.computeIfAbsent(clientName, this::buildWebClient);
    }

    /**
     * Returns the shared {@link Tracer} for OTel span creation.
     */
    public Tracer tracer() {
        return tracer;
    }

    /**
     * Returns a cached, fully-configured {@link CircuitBreaker} for the named client.
     */
    public CircuitBreaker circuitBreaker(String clientName) {
        return circuitBreakers.computeIfAbsent(clientName, this::buildCircuitBreaker);
    }

    /**
     * Returns a cached, fully-configured {@link Retry} for the named client.
     */
    public Retry retry(String clientName) {
        return retries.computeIfAbsent(clientName, this::buildRetry);
    }

    // ── Builders ──────────────────────────────────────────────────────────────

    private WebClient buildWebClient(String clientName) {
        ForgeConfigurationProperties.EgressClientProperties clientProps =
                getClientProps(clientName);

        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofMillis(clientProps.getTimeoutMs()));

        WebClient.Builder builder = webClientBuilder.clone()
                .baseUrl(clientProps.getBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .filter(loggingFilter(clientName))
                .filter(tracingFilter(clientName));

        // Attach auth filter
        switch (clientProps.getAuth().getType()) {
            case BEARER_TOKEN -> {
                ForgeTokenProvider provider = resolveTokenProvider(clientName, clientProps);
                builder.filter(bearerTokenFilter(provider));
            }
            case API_KEY_HEADER -> {
                String keyName = clientProps.getAuth().getKeyName();
                String keyValue = clientProps.getAuth().getKeyValue();
                builder.filter(apiKeyHeaderFilter(keyName, keyValue));
            }
            case API_KEY_QUERY -> {
                // Applied per-request — handled in generated client methods
            }
            default -> log.debug("No auth configured for client '{}'", clientName);
        }

        log.info("API-Forge: WebClient built for '{}' → {}", clientName, clientProps.getBaseUrl());
        return builder.build();
    }

    private CircuitBreaker buildCircuitBreaker(String clientName) {
        ForgeConfigurationProperties.EgressClientProperties clientProps = getClientProps(clientName);
        ForgeConfigurationProperties.CircuitBreakerProperties cbProps =
                clientProps.getResilience().getCircuitBreaker();

        if (!cbProps.isEnabled()) {
            log.info("Circuit breaker DISABLED for client '{}'", clientName);
            return CircuitBreakerRegistry.ofDefaults().circuitBreaker(clientName + "-noop",
                    CircuitBreakerConfig.custom()
                            .slidingWindowSize(Integer.MAX_VALUE)
                            .failureRateThreshold(100)
                            .build());
        }

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(cbProps.getFailureRateThreshold())
                .slowCallRateThreshold(cbProps.getSlowCallRateThreshold())
                .slowCallDurationThreshold(Duration.ofMillis(cbProps.getSlowCallDurationThresholdMs()))
                .slidingWindowSize(cbProps.getSlidingWindowSize())
                .minimumNumberOfCalls(cbProps.getMinimumNumberOfCalls())
                .waitDurationInOpenState(Duration.ofMillis(cbProps.getWaitDurationInOpenStateMs()))
                .permittedNumberOfCallsInHalfOpenState(cbProps.getPermittedCallsInHalfOpenState())
                .recordExceptions(Exception.class)
                .build();

        CircuitBreaker cb = CircuitBreakerRegistry.of(config).circuitBreaker(clientName);
        cb.getEventPublisher()
                .onStateTransition(e ->
                        log.warn("Circuit breaker '{}' transitioned: {} → {}",
                                clientName, e.getStateTransition().getFromState(),
                                e.getStateTransition().getToState()));
        return cb;
    }

    private Retry buildRetry(String clientName) {
        ForgeConfigurationProperties.EgressClientProperties clientProps = getClientProps(clientName);
        ForgeConfigurationProperties.RetryProperties retryProps =
                clientProps.getResilience().getRetry();

        if (!retryProps.isEnabled()) {
            return RetryRegistry.ofDefaults().retry(clientName + "-noop",
                    RetryConfig.custom().maxAttempts(1).build());
        }

        RetryConfig.Builder<Object> configBuilder = RetryConfig.custom()
                .maxAttempts(retryProps.getMaxAttempts());

        if (retryProps.isExponentialBackoff()) {
            configBuilder.intervalFunction(
                    io.github.resilience4j.core.IntervalFunction.ofExponentialBackoff(
                            retryProps.getWaitDurationMs(),
                            retryProps.getExponentialMultiplier(),
                            retryProps.getMaxWaitDurationMs()));
        } else {
            configBuilder.waitDuration(Duration.ofMillis(retryProps.getWaitDurationMs()));
        }

        Retry retry = RetryRegistry.of(configBuilder.build()).retry(clientName);
        retry.getEventPublisher()
                .onRetry(e -> log.warn("Retrying '{}' — attempt {}/{}, cause: {}",
                        clientName, e.getNumberOfRetryAttempts(),
                        retryProps.getMaxAttempts(), e.getLastThrowable().getMessage()));
        return retry;
    }

    // ── Filters ───────────────────────────────────────────────────────────────

    private ExchangeFilterFunction loggingFilter(String clientName) {
        return ExchangeFilterFunction.ofRequestProcessor(request -> {
            log.debug("[{}] → {} {}", clientName, request.method(), request.url());
            return Mono.just(request);
        }).andThen(ExchangeFilterFunction.ofResponseProcessor(response -> {
            log.debug("[{}] ← {}", clientName, response.statusCode());
            return Mono.just(response);
        }));
    }

    private ExchangeFilterFunction tracingFilter(String clientName) {
        return (request, next) -> {
            if (tracer.currentSpan() != null) {
                // Propagate W3C trace context headers
                ClientRequest traced = ClientRequest.from(request)
                        .header("traceparent", buildTraceparent())
                        .build();
                return next.exchange(traced);
            }
            return next.exchange(request);
        };
    }

    private ExchangeFilterFunction bearerTokenFilter(ForgeTokenProvider provider) {
        return ExchangeFilterFunction.ofRequestProcessor(request ->
                Mono.just(ClientRequest.from(request)
                        .header(HttpHeaders.AUTHORIZATION, provider.authorizationHeaderValue())
                        .build()));
    }

    private ExchangeFilterFunction apiKeyHeaderFilter(String headerName, String value) {
        return ExchangeFilterFunction.ofRequestProcessor(request ->
                Mono.just(ClientRequest.from(request)
                        .header(headerName, value)
                        .build()));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ForgeConfigurationProperties.EgressClientProperties getClientProps(String clientName) {
        ForgeConfigurationProperties.EgressClientProperties p = props.getEgress().get(clientName);
        if (p == null) {
            throw new IllegalStateException(
                "API-Forge: No configuration found for egress client '" + clientName +
                "'. Add 'forge.egress." + clientName + ".*' to your application.yml.");
        }
        return p;
    }

    private ForgeTokenProvider resolveTokenProvider(
            String clientName,
            ForgeConfigurationProperties.EgressClientProperties clientProps) {

        // Check for a developer-registered custom provider (@ForgeAuthProvider bean)
        if (tokenProviders.containsKey(clientName)) {
            log.debug("Using custom ForgeTokenProvider for client '{}'", clientName);
            return tokenProviders.get(clientName);
        }

        // Fall back to config-driven providers
        String providerType = clientProps.getAuth().getTokenProvider();
        return switch (providerType) {
            case "env" -> new EnvForgeTokenProvider(clientProps.getAuth().getToken());
            case "static" -> () -> clientProps.getAuth().getToken();
            default -> throw new IllegalStateException(
                "API-Forge: Unknown token-provider '" + providerType +
                "' for client '" + clientName + "'. Valid values: env, static, vault, bean");
        };
    }

    private String buildTraceparent() {
        if (tracer == null || tracer.currentSpan() == null) return "";
        var ctx = tracer.currentSpan().context();
        return String.format("00-%s-%s-01", ctx.traceId(), ctx.spanId());
    }
}

package org.apiforgeframework.runtime.auth;

import lombok.extern.slf4j.Slf4j;
import org.apiforgeframework.runtime.auth.ForgeTokenProvider;
import org.apiforgeframework.runtime.ForgeConfigurationProperties;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fetches tokens via OAuth2 client-credentials grant with automatic refresh.
 *
 * <p>Tokens are cached until 60 seconds before expiry. Thread-safe.
 *
 * <p>Config: {@code forge.egress.<name>.auth.type=OAUTH2_CLIENT_CREDENTIALS}
 */
@Slf4j
public class OAuth2ForgeTokenProvider implements ForgeTokenProvider {

    private final WebClient tokenClient;
    private final String clientId;
    private final String clientSecret;
    private final String tokenUrl;

    // Cached token + expiry
    private final AtomicReference<CachedToken> cache = new AtomicReference<>();

    OAuth2ForgeTokenProvider(
            ForgeConfigurationProperties.AuthProperties auth,
            WebClient.Builder webClientBuilder) {
        this.tokenClient = webClientBuilder.baseUrl(auth.getTokenUrl()).build();
        this.clientId = auth.getClientId();
        this.clientSecret = auth.getClientSecret();
        this.tokenUrl = auth.getTokenUrl();
    }

    @Override
    public String getToken() {
        CachedToken cached = cache.get();
        if (cached != null && !cached.isExpired()) {
            return cached.token;
        }
        return refreshToken();
    }

    private synchronized String refreshToken() {
        // Double-checked inside synchronized
        CachedToken cached = cache.get();
        if (cached != null && !cached.isExpired()) {
            return cached.token;
        }

        log.debug("Refreshing OAuth2 token for client '{}'", clientId);

        Map<?, ?> response = tokenClient.post()
                .uri(tokenUrl)
                .body(BodyInserters.fromFormData("grant_type", "client_credentials")
                        .with("client_id", clientId)
                        .with("client_secret", clientSecret))
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        if (response == null || !response.containsKey("access_token")) {
            throw new IllegalStateException("OAuth2 token response missing 'access_token'");
        }

        String token = (String) response.get("access_token");
        int expiresIn = response.containsKey("expires_in")
                ? (Integer) response.get("expires_in") : 3600;

        cache.set(new CachedToken(token, Instant.now().plusSeconds(expiresIn - 60)));
        return token;
    }

    private record CachedToken(String token, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}

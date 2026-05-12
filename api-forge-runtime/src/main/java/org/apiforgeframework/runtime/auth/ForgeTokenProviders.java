package org.apiforgeframework.runtime.auth;

import org.apiforgeframework.runtime.ForgeConfigurationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Returns a static token from configuration.
 * Suitable for development/testing only — never use in production.
 */
@RequiredArgsConstructor
class StaticForgeTokenProvider implements ForgeTokenProvider {

    private final String token;

    @Override
    public String getToken() {
        return token;
    }
}



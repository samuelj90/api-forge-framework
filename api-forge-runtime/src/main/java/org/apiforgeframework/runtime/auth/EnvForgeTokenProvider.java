package org.apiforgeframework.runtime.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Reads a bearer token from a named environment variable.
 *
 * <p>Config: {@code forge.egress.<name>.auth.token-provider=env}
 * {@code forge.egress.<name>.auth.token=MY_TOKEN_ENV_VAR}
 */
@RequiredArgsConstructor
@Slf4j
public class EnvForgeTokenProvider implements ForgeTokenProvider {

    private final String envVarName;

    @Override
    public String getToken() {
        String token = System.getenv(envVarName);
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "API-Forge auth: environment variable '" + envVarName + "' is not set or empty");
        }
        return token;
    }
}

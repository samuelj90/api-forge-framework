package org.apiforgeframework.runtime.auth;

/**
 * Strategy interface for supplying bearer tokens to egress clients.
 *
 * <p>API-Forge ships three built-in providers:
 * <ul>
 *   <li>{@link EnvForgeTokenProvider} — reads from an environment variable</li>
 *   <li>{@link StaticForgeTokenProvider} — uses a static config value</li>
 *   <li>{@link OAuth2ForgeTokenProvider} — fetches via client-credentials grant</li>
 * </ul>
 *
 * <p>For custom providers (e.g. HashiCorp Vault), implement this interface
 * and annotate with {@code @ForgeAuthProvider(forClient = "my-client")}:
 *
 * <pre>{@code
 * @Component
 * @ForgeAuthProvider(forClient = "payment")
 * public class VaultTokenProvider implements ForgeTokenProvider {
 *     public String getToken() {
 *         return vaultTemplate.read("secret/payment").getData().get("token").toString();
 *     }
 * }
 * }</pre>
 */
public interface ForgeTokenProvider {

    /**
     * Returns a valid bearer token (without the "Bearer " prefix).
     * Implementations should cache tokens and refresh before expiry.
     */
    String getToken();

    /**
     * Returns the token formatted as an Authorization header value.
     * Default: {@code "Bearer <token>"}.
     */
    default String authorizationHeaderValue() {
        return "Bearer " + getToken();
    }
}

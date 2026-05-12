package org.apiforgeframework.plugin.model;

import lombok.Builder;
import lombok.Data;

import java.io.File;
import java.util.List;
import java.util.Set;

/**
 * Internal data model representing a single code-generation task.
 * Populated by the Maven Mojo from plugin XML config and passed to generators.
 */
@Data
@Builder
public class ForgeGenerationConfig {

    public enum GenerationType { INGRESS, EGRESS }

    // ── Common ───────────────────────────────────────────────────────────────

    private GenerationType type;
    private File specFile;
    private String basePackage;
    private File outputDirectory;
    private String pluginVersion;
    private boolean generateServiceImpl;
    private File serviceImplSourceDirectory;

    // ── Feature flags ────────────────────────────────────────────────────────

    @Builder.Default private boolean lombokEnabled = true;
    @Builder.Default private boolean openTelemetryEnabled = true;
    @Builder.Default private boolean validationEnabled = true;
    @Builder.Default private boolean exceptionHandlingEnabled = true;
    @Builder.Default private boolean loggingEnabled = true;

    // ── Ingress-specific ─────────────────────────────────────────────────────

    /** OperationIds to skip controller generation for (manual escape hatch). */
    @Builder.Default private Set<String> manualOperations = Set.of();

    /** API version string injected into @RequestMapping prefix (e.g. "v1"). */
    private String apiVersionPrefix;

    // ── Egress-specific ──────────────────────────────────────────────────────

    /**
     * Logical client name matching {@code forge.egress.<clientName>.*} config.
     * Used as the Resilience4j instance name and for log prefixes.
     */
    private String clientName;

    @Builder.Default private boolean resilience4jEnabled = true;
    @Builder.Default private boolean retryEnabled = true;
    @Builder.Default private boolean circuitBreakerEnabled = true;

    private AuthType authType;

    public enum AuthType {
        NONE, BEARER_TOKEN, OAUTH2_CLIENT_CREDENTIALS, API_KEY_HEADER, API_KEY_QUERY, BASIC
    }
}

package org.apiforgeframework.plugin.mojo;

import org.apiforgeframework.plugin.generator.*;
import org.apiforgeframework.plugin.model.ForgeGenerationConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

/**
 * Maven goal: {@code api-forge:generate-egress}
 *
 * <p>Reads an upstream (egress) OpenAPI spec and generates:
 * <ul>
 *   <li>A Spring {@code @Component} WebClient-based API client</li>
 *   <li>Response/request DTOs in a namespaced egress model package</li>
 * </ul>
 *
 * <p>Multiple executions generate multiple clients. Each uses its own
 * {@code clientName} to match {@code forge.egress.<clientName>.*} properties.
 *
 * <pre>{@code
 * <execution>
 *   <id>generate-egress-inventory</id>
 *   <goals><goal>generate-egress</goal></goals>
 *   <configuration>
 *     <specFile>openapi/egress/inventory-api.yaml</specFile>
 *     <clientName>inventory</clientName>
 *     <basePackage>com.company.orders</basePackage>
 *     <authentication>BEARER_TOKEN</authentication>
 *   </configuration>
 * </execution>
 * }</pre>
 */
@Mojo(
    name = "generate-egress",
    defaultPhase = LifecyclePhase.GENERATE_SOURCES,
    requiresDependencyResolution = ResolutionScope.COMPILE
)
public class GenerateEgressMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    /** Path to the upstream OpenAPI spec file (relative to project root). */
    @Parameter(property = "forge.egress.specFile", required = true)
    private String specFile;

    /**
     * Logical client name. Must match the {@code forge.egress.<clientName>} config block.
     * Used as package suffix and Resilience4j instance name.
     */
    @Parameter(property = "forge.egress.clientName", required = true)
    private String clientName;

    /** Base Java package. Client lands at {@code <basePackage>.client.<clientName>}. */
    @Parameter(property = "forge.egress.basePackage", required = true)
    private String basePackage;

    /** Output directory for generated sources. */
    @Parameter(defaultValue = "${project.build.directory}/generated-sources/api-forge")
    private File outputDirectory;

    /** Authentication type for the generated client. */
    @Parameter(defaultValue = "NONE")
    private String authentication;

    /** Enable Resilience4j circuit breaker on all client methods. */
    @Parameter(defaultValue = "true")
    private boolean circuitBreaker;

    /** Enable Resilience4j retry on all client methods. */
    @Parameter(defaultValue = "true")
    private boolean retry;

    /** Generate OpenTelemetry span creation and W3C trace propagation. */
    @Parameter(defaultValue = "true")
    private boolean openTelemetry;

    /** Generate Lombok annotations on egress DTOs. */
    @Parameter(defaultValue = "true")
    private boolean lombok;

    /** Skip generation entirely. */
    @Parameter(property = "forge.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("API-Forge egress generation skipped (forge.skip=true)");
            return;
        }

        File specFileResolved = resolveSpecFile();
        getLog().info(String.format(
                "API-Forge: Generating egress client '%s' from %s",
                clientName, specFileResolved.getName()));

        // Parse
        ForgeApiModel model;
        try {
            model = new ForgeSpecParser(specFileResolved).parse();
        } catch (ForgeSpecParser.ForgeGenerationException e) {
            throw new MojoFailureException("Spec parsing failed for client '" + clientName
                    + "': " + e.getMessage(), e);
        }

        // Config
        ForgeGenerationConfig config = ForgeGenerationConfig.builder()
                .type(ForgeGenerationConfig.GenerationType.EGRESS)
                .specFile(specFileResolved)
                .basePackage(basePackage)
                .outputDirectory(outputDirectory)
                .clientName(clientName)
                .pluginVersion(getPluginVersion())
                .authType(parseAuthType(authentication))
                .circuitBreakerEnabled(circuitBreaker)
                .retryEnabled(retry)
                .openTelemetryEnabled(openTelemetry)
                .lombokEnabled(lombok)
                .resilience4jEnabled(circuitBreaker || retry)
                .build();

        // Generate
        ForgeEgressGenerator generator = new ForgeEgressGenerator(config, model);
        ForgeIngressGenerator.GenerationResult result;
        try {
            result = generator.generate();
        } catch (ForgeSpecParser.ForgeGenerationException e) {
            throw new MojoExecutionException("Egress generation failed: " + e.getMessage(), e);
        }

        // Register generated sources
        project.addCompileSourceRoot(outputDirectory.getAbsolutePath());

        // Write per-client manifest entry
        writeManifest(specFileResolved, result);

        getLog().info(String.format(
                "API-Forge: Generated %d file(s) for egress client '%s'",
                result.generatedFiles().size(), clientName));
    }

    private File resolveSpecFile() throws MojoFailureException {
        File f = new File(specFile);
        if (!f.isAbsolute()) {
            f = new File(project.getBasedir(), specFile);
        }
        if (!f.exists()) {
            throw new MojoFailureException(
                "Egress spec file not found: " + f.getAbsolutePath() +
                "\nCheck <specFile> in the '" + clientName + "' execution configuration.");
        }
        return f;
    }

    private ForgeGenerationConfig.AuthType parseAuthType(String auth) {
        try {
            return ForgeGenerationConfig.AuthType.valueOf(auth.toUpperCase());
        } catch (IllegalArgumentException e) {
            getLog().warn("Unknown auth type '" + auth + "', defaulting to NONE. " +
                    "Valid values: NONE, BEARER_TOKEN, OAUTH2_CLIENT_CREDENTIALS, API_KEY_HEADER, API_KEY_QUERY, BASIC");
            return ForgeGenerationConfig.AuthType.NONE;
        }
    }

    private String getPluginVersion() {
        try {
            var pkg = getClass().getPackage();
            return pkg != null && pkg.getImplementationVersion() != null
                   ? pkg.getImplementationVersion() : "dev";
        } catch (Exception e) {
            return "dev";
        }
    }

    private void writeManifest(File specFile, ForgeIngressGenerator.GenerationResult result) {
        try {
            File manifestFile = new File(outputDirectory,
                    ".forge-manifest-egress-" + clientName + ".json");
            manifestFile.getParentFile().mkdirs();

            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("generatedAt", Instant.now().toString());
            manifest.put("clientName", clientName);
            manifest.put("specFile", specFile.getAbsolutePath());
            manifest.put("specChecksum", checksum(specFile));
            manifest.put("authType", authentication);
            manifest.put("generatedFiles", result.generatedFiles());

            new ObjectMapper()
                    .enable(SerializationFeature.INDENT_OUTPUT)
                    .writeValue(manifestFile, manifest);
        } catch (Exception e) {
            getLog().warn("Failed to write forge egress manifest: " + e.getMessage());
        }
    }

    private String checksum(File file) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(file.toPath()));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            return "unknown";
        }
    }
}

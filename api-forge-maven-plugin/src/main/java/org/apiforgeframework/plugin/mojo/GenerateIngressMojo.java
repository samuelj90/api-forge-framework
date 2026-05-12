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
 * Maven goal: {@code api-forge:generate-ingress}
 *
 * <p>Reads the ingress OpenAPI spec and generates:
 * <ul>
 *   <li>A {@code @RestController} that delegates to the service interface</li>
 *   <li>The service interface ({@code XxxApiService})</li>
 *   <li>Request/response DTOs with Lombok and Jakarta validation</li>
 * </ul>
 *
 * <p>Bind to {@code generate-sources} phase for automatic execution:
 * <pre>{@code
 * <execution>
 *   <id>generate-ingress</id>
 *   <goals><goal>generate-ingress</goal></goals>
 *   <phase>generate-sources</phase>
 *   <configuration>
 *     <specFile>openapi/ingress/orders-api.yaml</specFile>
 *     <basePackage>com.company.orders</basePackage>
 *   </configuration>
 * </execution>
 * }</pre>
 */
@Mojo(
    name = "generate-ingress",
    defaultPhase = LifecyclePhase.GENERATE_SOURCES,
    requiresDependencyResolution = ResolutionScope.COMPILE
)
public class GenerateIngressMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    /** Path to the ingress OpenAPI spec file (relative to project root). */
    @Parameter(property = "forge.ingress.specFile", required = true)
    private String specFile;

    /** Base Java package for all generated classes. */
    @Parameter(property = "forge.ingress.basePackage", required = true)
    private String basePackage;

    /** Output directory for generated sources. */
    @Parameter(defaultValue = "${project.build.directory}/generated-sources/api-forge")
    private File outputDirectory;

    /** Generate Lombok annotations on DTOs. */
    @Parameter(defaultValue = "true")
    private boolean lombok;

    /** Generate OpenTelemetry instrumentation hooks. */
    @Parameter(defaultValue = "true")
    private boolean openTelemetry;

    /** Generate Jakarta validation annotations on DTOs. */
    @Parameter(defaultValue = "true")
    private boolean validation;

    /** Generate the base @ControllerAdvice exception handler. */
    @Parameter(defaultValue = "true")
    private boolean exceptionHandling;

    /** Generate structured logging in controllers. */
    @Parameter(defaultValue = "true")
    private boolean logging;

    /** OperationIds to skip generation for (manual escape hatch). */
    @Parameter
    private List<String> manualOperations = new ArrayList<>();

    /** API version prefix injected into request mappings (e.g. "v1"). */
    @Parameter
    private String apiVersionPrefix;

    /** Skip generation entirely. */
    @Parameter(property = "forge.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("API-Forge ingress generation skipped (forge.skip=true)");
            return;
        }

        File specFileResolved = resolveSpecFile();
        getLog().info("API-Forge: Generating ingress from " + specFileResolved.getName());

        // Parse spec
        ForgeApiModel model;
        try {
            model = new ForgeSpecParser(specFileResolved).parse();
        } catch (ForgeSpecParser.ForgeGenerationException e) {
            throw new MojoFailureException("Spec parsing failed: " + e.getMessage(), e);
        }

        // Build config
        ForgeGenerationConfig config = ForgeGenerationConfig.builder()
                .type(ForgeGenerationConfig.GenerationType.INGRESS)
                .specFile(specFileResolved)
                .basePackage(basePackage)
                .outputDirectory(outputDirectory)
                .pluginVersion(getPluginVersion())
                .lombokEnabled(lombok)
                .openTelemetryEnabled(openTelemetry)
                .validationEnabled(validation)
                .exceptionHandlingEnabled(exceptionHandling)
                .loggingEnabled(logging)
                .manualOperations(new HashSet<>(manualOperations))
                .apiVersionPrefix(apiVersionPrefix)
                .build();

        // Generate
        ForgeIngressGenerator generator = new ForgeIngressGenerator(config, model);
        ForgeIngressGenerator.GenerationResult result;
        try {
            result = generator.generate();
        } catch (ForgeSpecParser.ForgeGenerationException e) {
            throw new MojoExecutionException("Generation failed: " + e.getMessage(), e);
        }

        // Register generated sources with Maven
        project.addCompileSourceRoot(outputDirectory.getAbsolutePath());

        // Write manifest for incremental build support
        writeManifest(specFileResolved, result);

        getLog().info(String.format("API-Forge: Generated %d file(s) from %s",
                result.generatedFiles().size(), specFileResolved.getName()));
    }

    private File resolveSpecFile() throws MojoFailureException {
        File f = new File(specFile);
        if (!f.isAbsolute()) {
            f = new File(project.getBasedir(), specFile);
        }
        if (!f.exists()) {
            throw new MojoFailureException(
                "Ingress spec file not found: " + f.getAbsolutePath() +
                "\nCheck <specFile> in your plugin configuration.");
        }
        return f;
    }

    private String getPluginVersion() {
        // Loaded from plugin's own manifest at runtime
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
            File manifestFile = new File(outputDirectory, ".forge-manifest.json");
            manifestFile.getParentFile().mkdirs();

            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("generatedAt", Instant.now().toString());
            manifest.put("specFile", specFile.getAbsolutePath());
            manifest.put("specChecksum", checksum(specFile));
            manifest.put("generatedFiles", result.generatedFiles());
            manifest.put("skippedFiles", result.skippedFiles());

            new ObjectMapper()
                    .enable(SerializationFeature.INDENT_OUTPUT)
                    .writeValue(manifestFile, manifest);
        } catch (Exception e) {
            getLog().warn("Failed to write forge manifest: " + e.getMessage());
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

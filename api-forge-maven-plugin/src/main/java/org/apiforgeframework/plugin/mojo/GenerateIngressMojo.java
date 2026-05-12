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
 * <p>Reads an ingress OpenAPI spec and generates into
 * {@code target/generated-sources/api-forge}:
 * <ul>
 *   <li>A {@code @RestController} delegating to the service interface</li>
 *   <li>The service interface ({@code XxxApiService})</li>
 *   <li>Request/response DTOs with Lombok and Jakarta validation</li>
 * </ul>
 *
 * <p>Optionally, when {@code generateServiceImpl=true}, writes a one-time
 * implementation scaffold into {@code src/main/java} (never overwrites).
 *
 * <pre>{@code
 * <execution>
 *   <id>generate-ingress</id>
 *   <goals><goal>generate-ingress</goal></goals>
 *   <configuration>
 *     <specFile>openapi/ingress/orders-api.yaml</specFile>
 *     <basePackage>com.company.orders</basePackage>
 *     <generateServiceImpl>true</generateServiceImpl>
 *   </configuration>
 * </execution>
 * }</pre>
 */
@Mojo(
    name = "generate-ingress",
    defaultPhase = LifecyclePhase.GENERATE_SOURCES,
    requiresDependencyResolution = ResolutionScope.COMPILE,
    threadSafe = true
)
public class GenerateIngressMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** Path to the ingress OpenAPI spec file, relative to project root. */
    @Parameter(property = "forge.ingress.specFile", required = true)
    private String specFile;

    /** Base Java package for all generated classes. */
    @Parameter(property = "forge.ingress.basePackage", required = true)
    private String basePackage;

    /**
     * Output directory for generated sources (controller, service interface, DTOs).
     * This directory is added to the compile source root automatically.
     */
    @Parameter(defaultValue = "${project.build.directory}/generated-sources/api-forge")
    private File outputDirectory;

    /** Generate Lombok annotations on DTOs. */
    @Parameter(defaultValue = "true")
    private boolean lombok;

    /** Generate OpenTelemetry instrumentation in the controller. */
    @Parameter(defaultValue = "true")
    private boolean openTelemetry;

    /** Generate Jakarta Bean Validation annotations on DTOs. */
    @Parameter(defaultValue = "true")
    private boolean validation;

    /** Generate the base @ControllerAdvice exception handler scaffolding. */
    @Parameter(defaultValue = "true")
    private boolean exceptionHandling;

    /** Generate structured logging statements in the controller. */
    @Parameter(defaultValue = "true")
    private boolean logging;

    /**
     * OperationIds to exclude from controller generation.
     * Use for endpoints that need hand-written controllers (e.g. SSE, multipart).
     *
     * <pre>{@code
     * <manualOperations>
     *   <operation>streamOrderEvents</operation>
     * </manualOperations>
     * }</pre>
     */
    @Parameter
    private List<String> manualOperations = new ArrayList<>();

    /**
     * When {@code true}, write a one-time service implementation scaffold into
     * {@code serviceImplSourceDirectory} if the file does not already exist.
     *
     * <p>The scaffold implements the generated service interface with stub methods
     * that throw {@code UnsupportedOperationException}. It is written exactly once
     * and never overwritten — treat it as hand-written code from that point on.
     *
     * <p>Default: {@code false} (opt-in, to avoid surprising writes to src/).
     */
    @Parameter(defaultValue = "false")
    private boolean generateServiceImpl;

    /**
     * Root of the hand-written source tree. The service impl scaffold is placed
     * under this directory at the correct package path.
     *
     * <p>Defaults to {@code ${project.basedir}/src/main/java}.
     * Override only for non-standard project layouts.
     */
    @Parameter(defaultValue = "${project.basedir}/src/main/java")
    private File serviceImplSourceDirectory;

    /** Skip all generation. Useful for profiles or quick rebuilds. */
    @Parameter(property = "forge.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("API-Forge ingress generation skipped (forge.skip=true)");
            return;
        }

        File spec = resolveSpecFile();
        getLog().info("API-Forge: Generating ingress from " + spec.getName());

        ForgeApiModel model = parseSpec(spec);

        ForgeGenerationConfig config = ForgeGenerationConfig.builder()
                .type(ForgeGenerationConfig.GenerationType.INGRESS)
                .specFile(spec)
                .basePackage(basePackage)
                .outputDirectory(outputDirectory)
                .pluginVersion(pluginVersion())
                .lombokEnabled(lombok)
                .openTelemetryEnabled(openTelemetry)
                .validationEnabled(validation)
                .exceptionHandlingEnabled(exceptionHandling)
                .loggingEnabled(logging)
                .manualOperations(new HashSet<>(manualOperations))
                .generateServiceImpl(generateServiceImpl)
                .serviceImplSourceDirectory(serviceImplSourceDirectory)
                .build();

        // ── 1. Generate controller / service interface / DTOs ──────────────
        ForgeIngressGenerator.GenerationResult result;
        try {
            result = new ForgeIngressGenerator(config, model).generate();
        } catch (ForgeSpecParser.ForgeGenerationException e) {
            throw new MojoExecutionException("Ingress generation failed: " + e.getMessage(), e);
        }

        // Register generated directory as a compile source root
        project.addCompileSourceRoot(outputDirectory.getAbsolutePath());
        getLog().info(String.format("API-Forge: Generated %d file(s) from %s",
                result.generatedFiles().size(), spec.getName()));

        // ── 2. Optionally scaffold the service implementation ──────────────
        if (generateServiceImpl) {
            ForgeServiceImplGenerator.ScaffoldResult scaffold;
            try {
                scaffold = new ForgeServiceImplGenerator(config, model).generate();
            } catch (ForgeSpecParser.ForgeGenerationException e) {
                throw new MojoExecutionException(
                    "Service impl scaffold failed: " + e.getMessage(), e);
            }
            switch (scaffold.status()) {
                case WRITTEN ->
                    getLog().info("API-Forge: Service impl scaffold written → " +
                            scaffold.file().getAbsolutePath());
                case SKIPPED_EXISTS ->
                    getLog().info("API-Forge: Service impl already exists, skipping → " +
                            scaffold.file().getAbsolutePath());
                case SKIPPED_DISABLED -> { /* unreachable when generateServiceImpl=true */ }
            }
        }

        writeManifest(spec, result);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private File resolveSpecFile() throws MojoFailureException {
        File f = new File(specFile);
        if (!f.isAbsolute()) f = new File(project.getBasedir(), specFile);
        if (!f.exists()) {
            throw new MojoFailureException(
                "Ingress spec not found: " + f.getAbsolutePath() +
                "\nCheck <specFile> in your plugin configuration.");
        }
        return f;
    }

    private ForgeApiModel parseSpec(File spec) throws MojoFailureException {
        try {
            return new ForgeSpecParser(spec).parse();
        } catch (ForgeSpecParser.ForgeGenerationException e) {
            throw new MojoFailureException("Spec parsing failed: " + e.getMessage(), e);
        }
    }

    private String pluginVersion() {
        try {
            var pkg = getClass().getPackage();
            return pkg != null && pkg.getImplementationVersion() != null
                   ? pkg.getImplementationVersion() : "dev";
        } catch (Exception e) {
            return "dev";
        }
    }

    private void writeManifest(File spec, ForgeIngressGenerator.GenerationResult result) {
        try {
            File f = new File(outputDirectory, ".forge-manifest.json");
            f.getParentFile().mkdirs();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("generatedAt",    Instant.now().toString());
            m.put("specFile",       spec.getAbsolutePath());
            m.put("specChecksum",   sha256(spec));
            m.put("generatedFiles", result.generatedFiles());
            m.put("skippedFiles",   result.skippedFiles());
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(f, m);
        } catch (Exception e) {
            getLog().warn("Failed to write forge manifest: " + e.getMessage());
        }
    }

    private String sha256(File file) {
        try {
            var d = java.security.MessageDigest.getInstance("SHA-256");
            d.update(Files.readAllBytes(file.toPath()));
            return HexFormat.of().formatHex(d.digest());
        } catch (Exception e) {
            return "unknown";
        }
    }
}

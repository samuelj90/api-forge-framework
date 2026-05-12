package org.apiforgeframework.plugin.generator;

import org.apiforgeframework.plugin.model.ForgeGenerationConfig;
import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Generates a one-time service implementation scaffold into the real source tree
 * ({@code src/main/java}), only when:
 * <ol>
 *   <li>{@code generateServiceImpl=true} is set in the plugin configuration</li>
 *   <li>The target file does <strong>not</strong> already exist</li>
 * </ol>
 *
 * <h3>Safety guarantee</h3>
 * This generator checks for the file's existence before writing and will never
 * overwrite an existing file under any circumstances. The generated scaffold is
 * treated as hand-written code from the moment it is written.
 *
 * <h3>What is generated</h3>
 * <ul>
 *   <li>{@code @Service}, {@code @RequiredArgsConstructor}, {@code @Slf4j}</li>
 *   <li>{@code implements XxxApiService} (the generated interface)</li>
 *   <li>One stub method per operation with a {@code TODO} comment and
 *       {@code throw new UnsupportedOperationException(...)} body</li>
 *   <li>Commented-out egress client injection hints</li>
 * </ul>
 *
 * <h3>Output location</h3>
 * {@code {serviceImplSourceDirectory}/{basePackage}/service/impl/{Prefix}ApiServiceImpl.java}
 */
@Slf4j
public class ForgeServiceImplGenerator {

    private static final String TEMPLATE = "templates/ingress/ServiceImpl.java.mustache";

    private final ForgeGenerationConfig config;
    private final ForgeApiModel model;
    private final MustacheFactory mf;

    public ForgeServiceImplGenerator(ForgeGenerationConfig config, ForgeApiModel model) {
        this.config = config;
        this.model  = model;
        this.mf     = new DefaultMustacheFactory();
    }

    /**
     * Attempts to write the scaffold. Returns a result describing what happened.
     *
     * @return {@link ScaffoldResult} with status WRITTEN, SKIPPED_EXISTS, or SKIPPED_DISABLED
     */
    public ScaffoldResult generate() {
        if (!config.isGenerateServiceImpl()) {
            log.debug("generateServiceImpl=false — skipping service impl scaffold");
            return ScaffoldResult.skippedDisabled();
        }

        File implFile = resolveImplFile();

        if (implFile.exists()) {
            log.info("Service impl already exists — skipping scaffold: {}", implFile.getAbsolutePath());
            return ScaffoldResult.skippedExists(implFile);
        }

        writeScaffold(implFile);
        log.info("Service impl scaffold written: {}", implFile.getAbsolutePath());
        return ScaffoldResult.written(implFile);
    }

    // ── File resolution ───────────────────────────────────────────────────────

    private File resolveImplFile() {
        File sourceRoot = config.getServiceImplSourceDirectory();
        String implPackage  = config.getBasePackage() + ".service.impl";
        String implFileName = classPrefix() + "ApiServiceImpl.java";

        return Path.of(
                sourceRoot.getAbsolutePath(),
                implPackage.replace('.', File.separatorChar),
                implFileName
        ).toFile();
    }

    // ── Scaffold writing ──────────────────────────────────────────────────────

    private void writeScaffold(File implFile) {
        implFile.getParentFile().mkdirs();

        Map<String, Object> scope = buildScope(implFile);

        try (InputStream tmpl = getClass().getClassLoader().getResourceAsStream(TEMPLATE)) {
            if (tmpl == null) {
                throw new ForgeSpecParser.ForgeGenerationException(
                    "Service impl template not found: " + TEMPLATE);
            }
            Mustache m = mf.compile(new InputStreamReader(tmpl), TEMPLATE);
            try (FileWriter w = new FileWriter(implFile)) {
                m.execute(w, scope).flush();
            }
        } catch (IOException e) {
            throw new ForgeSpecParser.ForgeGenerationException(
                "Failed to write service impl scaffold: " + implFile.getAbsolutePath(), e);
        }
    }

    // ── Scope builder ─────────────────────────────────────────────────────────

    private Map<String, Object> buildScope(File implFile) {
        String prefix         = classPrefix();
        String serviceInterface = prefix + "ApiService";
        String implPackage    = config.getBasePackage() + ".service.impl";
        String serviceApiPkg  = config.getBasePackage() + ".service.api";
        String modelPkg       = config.getBasePackage() + ".model.ingress";

        Map<String, Object> s = new LinkedHashMap<>();
        s.put("implPackage",      implPackage);
        s.put("serviceApiPackage", serviceApiPkg);
        s.put("modelPackage",     modelPkg);
        s.put("className",        prefix + "ApiServiceImpl");
        s.put("serviceInterface", serviceInterface);
        s.put("specFile",         config.getSpecFile().getName());
        s.put("generatedAt",      Instant.now().toString());
        s.put("lombokEnabled",    config.isLombokEnabled());
        s.put("operations",       buildOperationScopes());
        return s;
    }

    private List<Map<String, Object>> buildOperationScopes() {
        List<Map<String, Object>> ops = new ArrayList<>();
        for (ForgeApiModel.ForgeOperation op : model.getOperations()) {
            if (config.getManualOperations().contains(op.getOperationId())) continue;

            Map<String, Object> s = new LinkedHashMap<>();
            s.put("operationId",             op.getOperationId());
            s.put("summary",                 op.getSummary() != null ? op.getSummary() : "");
            s.put("returnType",              op.isReturnsVoid() ? "void" : op.getResponseSchema());
            s.put("serviceMethodDeclaration", op.getServiceMethodDeclaration());
            s.put("returnsVoid",             op.isReturnsVoid());
            s.put("deprecated",              op.isDeprecated());
            ops.add(s);
        }
        return ops;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String classPrefix() {
        String title = model.getTitle();
        if (title == null) return "Api";
        return Arrays.stream(title.split("\\s+"))
                .filter(w -> !w.equalsIgnoreCase("api")
                          && !w.equalsIgnoreCase("service")
                          && !w.equalsIgnoreCase("the"))
                .map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1).toLowerCase())
                .reduce("", String::concat);
    }

    // ── Result type ───────────────────────────────────────────────────────────

    public enum ScaffoldStatus { WRITTEN, SKIPPED_EXISTS, SKIPPED_DISABLED }

    public record ScaffoldResult(ScaffoldStatus status, File file) {
        static ScaffoldResult written(File f)         { return new ScaffoldResult(ScaffoldStatus.WRITTEN, f); }
        static ScaffoldResult skippedExists(File f)   { return new ScaffoldResult(ScaffoldStatus.SKIPPED_EXISTS, f); }
        static ScaffoldResult skippedDisabled()       { return new ScaffoldResult(ScaffoldStatus.SKIPPED_DISABLED, null); }
        public boolean wasWritten()                   { return status == ScaffoldStatus.WRITTEN; }
    }
}
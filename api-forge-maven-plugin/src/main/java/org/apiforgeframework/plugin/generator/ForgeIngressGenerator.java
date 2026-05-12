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
 * Generates the ingress stack from an OpenAPI spec:
 * <ul>
 *   <li>{@code XxxApiController} — @RestController delegating to the service interface</li>
 *   <li>{@code XxxApiService} — generated service interface</li>
 *   <li>Request/response DTOs in {@code model/ingress}</li>
 * </ul>
 *
 * <h3>HTML-escaping fix</h3>
 * Mustache's {@link DefaultMustacheFactory} HTML-escapes {@code {{var}}} by default,
 * turning {@code =} into {@code &#61;} and {@code <} into {@code &lt;}.
 * All Java source code in templates uses triple-brace {@code {{{var}}}} which bypasses
 * escaping. This class passes pre-joined parameter declaration strings to templates,
 * eliminating the trailing-comma compile error entirely.
 */
@Slf4j
public class ForgeIngressGenerator {

    private static final String TEMPLATE_BASE = "templates/ingress/";

    private final ForgeGenerationConfig config;
    private final ForgeApiModel model;
    private final MustacheFactory mf;

    public ForgeIngressGenerator(ForgeGenerationConfig config, ForgeApiModel model) {
        this.config = config;
        this.model  = model;
        this.mf     = new DefaultMustacheFactory();
    }

    public GenerationResult generate() {
        List<String> generated = new ArrayList<>();
        String base      = config.getBasePackage();
        File   outDir    = config.getOutputDirectory();
        String prefix    = classPrefix(model.getTitle());

        // 1. Controller
        String ctrlPkg  = base + ".controller";
        String ctrlName = prefix + "ApiController";
        File   ctrlFile = toFile(outDir, ctrlPkg, ctrlName);
        render("Controller.java.mustache", buildControllerScope(prefix, ctrlPkg, base), ctrlFile);
        generated.add(ctrlFile.getAbsolutePath());
        log.info("Generated controller: {}", ctrlName);

        // 2. Service interface
        String svcPkg  = base + ".service.api";
        String svcName = prefix + "ApiService";
        File   svcFile = toFile(outDir, svcPkg, svcName);
        render("ServiceInterface.java.mustache", buildServiceScope(prefix, svcPkg, base), svcFile);
        generated.add(svcFile.getAbsolutePath());
        log.info("Generated service interface: {}", svcName);

        // 3. DTOs
        String modelPkg = base + ".model.ingress";
        for (ForgeApiModel.ForgeSchema schema : model.getSchemas()) {
            File dto = toFile(outDir, modelPkg, schema.getName());
            render("Dto.java.mustache", buildDtoScope(schema, modelPkg), dto);
            generated.add(dto.getAbsolutePath());
        }
        log.info("Generated {} DTOs", model.getSchemas().size());

        return new GenerationResult(generated, List.of());
    }

    // ── Scope builders ────────────────────────────────────────────────────────

    private Map<String, Object> buildControllerScope(String prefix, String ctrlPkg, String base) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("controllerPackage", ctrlPkg);
        s.put("modelPackage",      base + ".model.ingress");
        s.put("servicePackage",    base + ".service.api");
        s.put("className",         prefix + "ApiController");
        s.put("serviceInterface",  prefix + "ApiService");
        s.put("specFile",          config.getSpecFile().getName());
        s.put("pluginVersion",     config.getPluginVersion());
        s.put("generatedAt",       Instant.now().toString());
        s.put("basePath",          model.getBasePath());
        s.put("openTelemetryEnabled", config.isOpenTelemetryEnabled());
        s.put("loggingEnabled",    config.isLoggingEnabled());
        s.put("operations",        buildOperationScopes(true));
        return s;
    }

    private Map<String, Object> buildServiceScope(String prefix, String svcPkg, String base) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("servicePackage", svcPkg);
        s.put("modelPackage",   base + ".model.ingress");
        s.put("className",      prefix + "ApiService");
        s.put("specFile",       config.getSpecFile().getName());
        s.put("title",          model.getTitle());
        s.put("apiVersion",     model.getVersion());
        s.put("pluginVersion",  config.getPluginVersion());
        s.put("generatedAt",    Instant.now().toString());
        s.put("operations",     buildOperationScopes(false));
        return s;
    }

    /**
     * Builds per-operation scope maps that are fed to Mustache templates.
     *
     * Key: pre-computed {@code parameterDeclaration} and {@code parameterCallArgs}
     * strings are produced in Java (not in the template) to avoid the trailing-comma
     * issue that Mustache cannot solve elegantly.
     */
    private List<Map<String, Object>> buildOperationScopes(boolean forController) {
        List<Map<String, Object>> ops = new ArrayList<>();
        for (ForgeApiModel.ForgeOperation op : model.getOperations()) {
            if (config.getManualOperations().contains(op.getOperationId())) {
                log.info("Skipping manual operation: {}", op.getOperationId());
                continue;
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("operationId",           op.getOperationId());
            s.put("summary",               op.getSummary());
            s.put("httpMethod",            op.getHttpMethod());
            s.put("path",                  op.getPath());
            s.put("springMappingAnnotation", op.getSpringMappingAnnotation());
            s.put("hasRequestBody",        op.isHasRequestBody());
            s.put("requestBodySchema",     op.getRequestBodySchema());
            s.put("responseSchema",        op.isReturnsVoid() ? null : op.getResponseSchema());
            s.put("returnsVoid",           op.isReturnsVoid());
            // returnType: used in service interface template
            s.put("returnType", op.isReturnsVoid() ? "void" : op.getResponseSchema());
            s.put("successStatusCode",     op.getSuccessStatusCode());
            s.put("deprecated",            op.isDeprecated());

            // Pre-joined param strings — eliminates trailing-comma bug
            s.put("parameterDeclaration",    op.getParameterDeclaration());
            s.put("parameterCallArgs",       op.getParameterCallArgs());
            s.put("serviceMethodDeclaration", op.getServiceMethodDeclaration());
            ops.add(s);
        }
        return ops;
    }

    private Map<String, Object> buildDtoScope(ForgeApiModel.ForgeSchema schema, String modelPkg) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("modelPackage",   modelPkg);
        s.put("className",      schema.getName());
        s.put("lombokEnabled",  config.isLombokEnabled());
        s.put("validationEnabled", config.isValidationEnabled());
        s.put("specFile",       config.getSpecFile().getName());
        s.put("pluginVersion",  config.getPluginVersion());
        s.put("generatedAt",    Instant.now().toString());

        List<Map<String, Object>> fields = new ArrayList<>();
        for (ForgeApiModel.ForgeProperty p : schema.getProperties()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name",        p.getCamelCaseName());
            f.put("javaType",    p.getJavaType());
            f.put("description", p.getDescription());
            // validationAnnotations list — Mustache iterates and triple-braces each
            f.put("validationAnnotations", p.getValidationAnnotations());
            fields.add(f);
        }
        s.put("fields", fields);
        return s;
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    private void render(String template, Map<String, Object> scope, File out) {
        try {
            out.getParentFile().mkdirs();
            InputStream tmpl = getClass().getClassLoader()
                    .getResourceAsStream(TEMPLATE_BASE + template);
            if (tmpl == null) {
                throw new ForgeSpecParser.ForgeGenerationException(
                    "Template not found: " + TEMPLATE_BASE + template);
            }
            Mustache m = mf.compile(new InputStreamReader(tmpl), template);
            try (FileWriter w = new FileWriter(out)) {
                m.execute(w, scope).flush();
            }
        } catch (IOException e) {
            throw new ForgeSpecParser.ForgeGenerationException(
                "Failed to render " + template, e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** "Orders API" → "Orders", "Payment Gateway API" → "PaymentGateway" */
    private String classPrefix(String title) {
        if (title == null) return "Api";
        return Arrays.stream(title.split("\\s+"))
                .filter(w -> !w.equalsIgnoreCase("api")
                          && !w.equalsIgnoreCase("service")
                          && !w.equalsIgnoreCase("the"))
                .map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1).toLowerCase())
                .reduce("", String::concat);
    }

    private File toFile(File outDir, String pkg, String className) {
        return Path.of(outDir.getAbsolutePath(), pkg.replace('.', File.separatorChar))
                .resolve(className + ".java").toFile();
    }

    public record GenerationResult(List<String> generatedFiles, List<String> skippedFiles) {}
}

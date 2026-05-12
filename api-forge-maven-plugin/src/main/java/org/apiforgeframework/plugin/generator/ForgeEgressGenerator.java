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
import java.util.stream.Collectors;

/**
 * Generates the egress (outbound client) stack from an upstream OpenAPI spec:
 * <ul>
 *   <li>{@code XxxApiClient} — Spring @Component WebClient stub</li>
 *   <li>DTOs in {@code model/egress/<clientName>}</li>
 * </ul>
 *
 * Same HTML-escaping fix as ForgeIngressGenerator:
 * all Java code uses triple-brace in templates; param strings are pre-joined here.
 */
@Slf4j
public class ForgeEgressGenerator {

    private static final String TEMPLATE_BASE = "templates/egress/";

    private final ForgeGenerationConfig config;
    private final ForgeApiModel model;
    private final MustacheFactory mf;

    public ForgeEgressGenerator(ForgeGenerationConfig config, ForgeApiModel model) {
        this.config = config;
        this.model  = model;
        this.mf     = new DefaultMustacheFactory();
    }

    public ForgeIngressGenerator.GenerationResult generate() {
        List<String> generated = new ArrayList<>();
        String base = config.getBasePackage();
        File outDir = config.getOutputDirectory();
        String clientName = config.getClientName().toLowerCase();
        String prefix = capitalize(config.getClientName());
        String clientClass = prefix + "ApiClient";

        // Client class
        String clientPkg = base + ".client." + clientName;
        File clientFile = toFile(outDir, clientPkg, clientClass);

        render("ApiClient.java.mustache",
                buildClientScope(prefix, clientPkg, base, clientName),
                clientFile);
        generated.add(clientFile.getAbsolutePath());
        log.info("Generated egress client: {}", clientClass);

        // Egress DTOs
        String modelPkg = base + ".model.egress." + clientName;
        for (ForgeApiModel.ForgeSchema schema : model.getSchemas()) {
            File dto = toFile(outDir, modelPkg, schema.getName());
            render("EgressDto.java.mustache", buildDtoScope(schema, modelPkg), dto);
            generated.add(dto.getAbsolutePath());
        }

        log.info("Generated {} egress DTOs for client '{}'", model.getSchemas().size(), clientName);
        return new ForgeIngressGenerator.GenerationResult(generated, List.of());
    }

    // ── Scope builders ────────────────────────────────────────────────────────

    private Map<String, Object> buildClientScope(String prefix, String clientPkg, String base, String clientName) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("clientPackage", clientPkg);
        s.put("modelPackage", base + ".model.egress." + clientName);
        s.put("className", prefix + "ApiClient");
        s.put("clientName", clientName);
        s.put("specFile", config.getSpecFile().getName());
        s.put("pluginVersion", config.getPluginVersion());
        s.put("generatedAt", Instant.now().toString());
        s.put("circuitBreakerEnabled", config.isCircuitBreakerEnabled());
        s.put("retryEnabled", config.isRetryEnabled());
        s.put("openTelemetryEnabled", config.isOpenTelemetryEnabled());
        s.put("operations", buildClientOpScopes());

        // Conditional imports
        s.put("hasPathParams", hasPathParams());
        s.put("hasQueryParams", hasQueryParams());

        return s;
    }

    private List<Map<String, Object>> buildClientOpScopes() {
        List<Map<String, Object>> ops = new ArrayList<>();
        for (ForgeApiModel.ForgeOperation op : model.getOperations()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("operationId", op.getOperationId());
            s.put("summary", op.getSummary() != null ? op.getSummary() : "");
            s.put("httpMethod", op.getHttpMethod());
            s.put("httpMethodLower", op.getHttpMethod().toLowerCase());
            s.put("path", op.getPath());
            s.put("hasRequestBody", op.isHasRequestBody());
            s.put("requestBodySchema", op.getRequestBodySchema());
            s.put("responseSchema", op.isReturnsVoid() ? null : op.getResponseSchema());
            s.put("returnsVoid", op.isReturnsVoid());
            s.put("returnType", op.isReturnsVoid() ? "Void" : op.getResponseSchema());
            s.put("openTelemetryEnabled", config.isOpenTelemetryEnabled());
            s.put("circuitBreakerEnabled", config.isCircuitBreakerEnabled());
            s.put("retryEnabled", config.isRetryEnabled());

            s.put("parameterDeclaration", op.getParameterDeclaration());
            s.put("parameterCallArgs", op.getParameterCallArgs());

            // Path params for URI
            String pathArgs = op.getPathParams().stream()
                    .map(ForgeApiModel.ForgeParameter::getCamelCaseName)
                    .collect(Collectors.joining(", "));
            s.put("pathParamCallArgs", pathArgs);

            ops.add(s);
        }
        return ops;
    }

    private Map<String, Object> buildDtoScope(ForgeApiModel.ForgeSchema schema, String modelPkg) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("modelPackage", modelPkg);
        s.put("className", schema.getName());
        s.put("lombokEnabled", config.isLombokEnabled());
        s.put("specFile", config.getSpecFile().getName());
        s.put("pluginVersion", config.getPluginVersion());
        s.put("generatedAt", Instant.now().toString());

        List<Map<String, Object>> fields = schema.getProperties().stream().map(p -> {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name", p.getCamelCaseName());
            f.put("javaType", p.getJavaType());
            f.put("description", p.getDescription());
            return f;
        }).collect(Collectors.toList());

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
                throw new ForgeSpecParser.ForgeGenerationException("Template not found: " + template);
            }
            Mustache m = mf.compile(new InputStreamReader(tmpl), template);
            try (FileWriter w = new FileWriter(out)) {
                m.execute(w, scope).flush();
            }
        } catch (IOException e) {
            throw new ForgeSpecParser.ForgeGenerationException("Failed to render " + template, e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean hasPathParams() {
        return model.getOperations().stream()
                .anyMatch(op -> !op.getPathParams().isEmpty());
    }

    private boolean hasQueryParams() {
        return model.getOperations().stream()
                .anyMatch(op -> !op.getQueryParams().isEmpty());
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private File toFile(File outDir, String pkg, String className) {
        return Path.of(outDir.getAbsolutePath(), pkg.replace('.', File.separatorChar))
                .resolve(className + ".java").toFile();
    }
}

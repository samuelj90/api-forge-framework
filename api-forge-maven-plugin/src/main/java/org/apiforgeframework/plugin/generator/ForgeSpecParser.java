package org.apiforgeframework.plugin.generator;

import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Parses an OpenAPI 3.x spec into a clean {@link ForgeApiModel}.
 *
 * <h3>Phase-1 convention assumptions</h3>
 * <ul>
 *   <li>Request bodies use a named $ref under components/schemas</li>
 *   <li>2xx responses use a named $ref under components/schemas</li>
 *   <li>Every operation has a unique operationId</li>
 *   <li>servers[0].url provides the base path (e.g. /api/v1)</li>
 * </ul>
 *
 * <h3>Critical parse option</h3>
 * {@code resolveFully=false} and {@code flatten=false} are mandatory.
 * Setting either to true causes swagger-parser to explode every $ref into an
 * anonymous inline schema, producing names like inline_response_200,
 * orders_body, orderId_ship_body — the exact issue seen in the generated output.
 */
@Slf4j
public class ForgeSpecParser {

    /**
     * Matches anonymous schema names that swagger-parser generates when flatten/resolveFully
     * are enabled. All of these are artefacts and must be filtered out.
     */
    private static final Pattern ANONYMOUS_NAME = Pattern.compile(
        "(?i)^(inline_response_\\d+.*|.*_body$|.*_items$|.*_payment$|" +
        ".*_content$|.*_shipping_address$|[a-z][a-z0-9]*(_[a-z][a-z0-9]*)+)$"
    );

    private final File specFile;

    public ForgeSpecParser(File specFile) {
        this.specFile = specFile;
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    public ForgeApiModel parse() {
        log.info("Parsing spec: {}", specFile.getAbsolutePath());

        ParseOptions options = new ParseOptions();
        options.setResolve(true);       // resolve cross-file $refs
        options.setResolveFully(false); // MUST be false — preserve $ref strings
        options.setFlatten(false);      // MUST be false — no anonymous name explosion

        SwaggerParseResult result = new OpenAPIParser()
                .readLocation(specFile.getAbsolutePath(), null, options);

        if (result.getMessages() != null) {
            List<String> errors = result.getMessages().stream()
                    .filter(m -> m.toLowerCase().contains("error"))
                    .toList();
            if (!errors.isEmpty()) {
                throw new ForgeGenerationException(
                    "Spec errors in " + specFile.getName() + ":\n  - " +
                    String.join("\n  - ", errors));
            }
            result.getMessages().forEach(w ->
                    log.warn("Spec warning [{}]: {}", specFile.getName(), w));
        }

        OpenAPI api = result.getOpenAPI();
        if (api == null) {
            throw new ForgeGenerationException(
                    "Failed to parse: " + specFile.getAbsolutePath());
        }
        return buildModel(api);
    }

    // ── Top-level model assembly ───────────────────────────────────────────────

    private ForgeApiModel buildModel(OpenAPI api) {
        String title   = api.getInfo() != null ? api.getInfo().getTitle()   : "Unknown API";
        String version = api.getInfo() != null ? api.getInfo().getVersion() : "1.0.0";
        String base    = extractBasePath(api);

        List<ForgeApiModel.ForgeOperation> ops = new ArrayList<>();
        if (api.getPaths() != null) {
            api.getPaths().forEach((path, item) -> extractOperations(path, item, ops));
        }

        List<ForgeApiModel.ForgeSchema> schemas = new ArrayList<>();
        if (api.getComponents() != null && api.getComponents().getSchemas() != null) {
            api.getComponents().getSchemas().forEach((name, schema) -> {
                if (isAnonymous(name)) {
                    log.debug("Filtering anonymous schema '{}'", name);
                } else {
                    schemas.add(buildSchemaModel(name, schema));
                }
            });
        }

        log.info("'{}' v{}: {} ops, {} schemas, basePath='{}'",
                title, version, ops.size(), schemas.size(), base);

        return ForgeApiModel.builder()
                .title(title).version(version).basePath(base)
                .operations(ops).schemas(schemas)
                .build();
    }

    // ── Operations ────────────────────────────────────────────────────────────

    private void extractOperations(String path, PathItem item,
                                   List<ForgeApiModel.ForgeOperation> out) {
        item.readOperationsMap().forEach((method, op) -> {
            if (op.getOperationId() == null) {
                log.warn("No operationId at {} {} — skipping", method, path);
                return;
            }
            // Respect x-forge-manual: true
            if (op.getExtensions() != null &&
                Boolean.TRUE.equals(op.getExtensions().get("x-forge-manual"))) {
                log.info("Skipping manual operation '{}'", op.getOperationId());
                return;
            }

            out.add(ForgeApiModel.ForgeOperation.builder()
                    .operationId(op.getOperationId())
                    .summary(op.getSummary())
                    .description(op.getDescription())
                    .httpMethod(method.name())
                    .path(path)
                    .parameters(extractParameters(op))
                    .requestBodyRequired(op.getRequestBody() != null &&
                            Boolean.TRUE.equals(op.getRequestBody().getRequired()))
                    .requestBodySchema(extractRequestBodyType(op))
                    .responseSchema(extractSuccessResponseType(op))
                    .successStatusCode(extractSuccessStatus(op, method))
                    .tags(op.getTags() != null ? op.getTags() : List.of())
                    .deprecated(Boolean.TRUE.equals(op.getDeprecated()))
                    .build());
        });
    }

    // ── $ref extraction — the core fix ────────────────────────────────────────

    @SuppressWarnings("rawtypes")
    private String extractRequestBodyType(Operation op) {
        if (op.getRequestBody() == null) return null;
        var content = op.getRequestBody().getContent();
        if (content == null) return null;
        var mt = content.get("application/json");
        if (mt == null || mt.getSchema() == null) return null;
        return schemaToJavaType(mt.getSchema());
    }

    private String extractSuccessResponseType(Operation op) {
        if (op.getResponses() == null) return null;
        return op.getResponses().entrySet().stream()
                .filter(e -> e.getKey().startsWith("2"))
                .sorted(Map.Entry.comparingByKey())
                .findFirst()
                .map(e -> responseBodyType(e.getValue()))
                .orElse(null);
    }

    @SuppressWarnings("rawtypes")
    private String responseBodyType(ApiResponse resp) {
        if (resp.getContent() == null) return null;
        var mt = resp.getContent().get("application/json");
        if (mt == null || mt.getSchema() == null) return null;
        return schemaToJavaType(mt.getSchema());
    }

    /**
     * Reads the actual HTTP status code written in the spec for the first 2xx response.
     * Does NOT default to 201 for all POST — only when no 2xx response is declared.
     */
    private int extractSuccessStatus(Operation op, PathItem.HttpMethod method) {
        if (op.getResponses() != null) {
            Optional<String> first2xx = op.getResponses().keySet().stream()
                    .filter(k -> k.matches("2\\d\\d"))
                    .sorted().findFirst();
            if (first2xx.isPresent()) {
                try { return Integer.parseInt(first2xx.get()); }
                catch (NumberFormatException ignored) {}
            }
        }
        return switch (method) {
            case POST   -> 201;
            case DELETE -> 204;
            default     -> 200;
        };
    }

    // ── Schema → Java type ────────────────────────────────────────────────────

    /**
     * Converts a schema to a Java type name.
     *
     * With resolveFully=false, named schemas retain their $ref string.
     * We just extract the last segment. Primitives are mapped directly.
     */
    @SuppressWarnings("rawtypes")
    private String schemaToJavaType(Schema schema) {
        if (schema == null) return null;

        // Named component reference: preserve as class name
        if (schema.get$ref() != null) {
            return refToName(schema.get$ref());
        }

        // Array: recurse on items
        if ("array".equals(schema.getType())) {
            Schema items = schema.getItems();
            String itemType = items != null ? schemaToJavaType(items) : "Object";
            return "List<" + (itemType != null ? itemType : "Object") + ">";
        }

        // Primitive types
        return mapPrimitive(schema.getType(), schema.getFormat());
    }

    private String refToName(String ref) {
        if (ref == null) return null;
        int slash = ref.lastIndexOf('/');
        return slash >= 0 ? ref.substring(slash + 1) : ref;
    }

    private String mapPrimitive(String type, String format) {
        if (type == null) return "Object";
        return switch (type) {
            case "string"  -> {
                if ("date".equals(format))      yield "java.time.LocalDate";
                if ("date-time".equals(format)) yield "java.time.OffsetDateTime";
                if ("binary".equals(format))    yield "byte[]";
                yield "String";
            }
            case "integer" -> "int64".equals(format) ? "Long" : "Integer";
            case "number"  -> "float".equals(format) ? "Float" : "java.math.BigDecimal";
            case "boolean" -> "Boolean";
            default        -> "Object";
        };
    }

    // ── Parameters ────────────────────────────────────────────────────────────

    private List<ForgeApiModel.ForgeParameter> extractParameters(Operation op) {
        if (op.getParameters() == null) return List.of();
        return op.getParameters().stream()
                .map(p -> ForgeApiModel.ForgeParameter.builder()
                        .name(p.getName())
                        .in(p.getIn())
                        .required(Boolean.TRUE.equals(p.getRequired()))
                        .javaType(p.getSchema() != null
                                ? mapPrimitive(p.getSchema().getType(), p.getSchema().getFormat())
                                : "String")
                        .build())
                .collect(Collectors.toList());
    }

    // ── DTO Schema model ──────────────────────────────────────────────────────

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ForgeApiModel.ForgeSchema buildSchemaModel(String name, Schema<?> schema) {
        List<ForgeApiModel.ForgeProperty> props = new ArrayList<>();

        if (schema.getProperties() != null) {
            Set<String> required = schema.getRequired() != null
                    ? new HashSet<>(schema.getRequired()) : Set.of();

            schema.getProperties().forEach((pName, pSchema) -> {
                Schema ps = (Schema) pSchema;
                boolean integer = "integer".equals(ps.getType());

                props.add(ForgeApiModel.ForgeProperty.builder()
                        .name(pName)
                        .javaType(schemaToJavaType(ps))
                        .required(required.contains(pName))
                        .description(ps.getDescription())
                        .format(ps.getFormat())
                        .minimum(ps.getMinimum() != null ? ps.getMinimum().toPlainString() : null)
                        .maximum(ps.getMaximum() != null ? ps.getMaximum().toPlainString() : null)
                        .minLength(ps.getMinLength())
                        .maxLength(ps.getMaxLength())
                        .pattern(ps.getPattern())
                        .isIntegerType(integer)   // drives @Min/@Max vs @DecimalMin/@DecimalMax
                        .build());
            });
        }

        return ForgeApiModel.ForgeSchema.builder()
                .name(name).properties(props).build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String extractBasePath(OpenAPI api) {
        if (api.getServers() == null || api.getServers().isEmpty()) return "";
        String url = api.getServers().get(0).getUrl();
        if (url == null || url.isBlank() || "/".equals(url)) return "";
        try {
            String path = URI.create(url).getPath();
            return (path == null || path.isBlank()) ? "" : path;
        } catch (Exception e) {
            log.warn("Could not parse server URL '{}': {}", url, e.getMessage());
            return "";
        }
    }

    private boolean isAnonymous(String name) {
        return ANONYMOUS_NAME.matcher(name).matches();
    }

    // ── Exception ─────────────────────────────────────────────────────────────

    public static class ForgeGenerationException extends RuntimeException {
        public ForgeGenerationException(String message) { super(message); }
        public ForgeGenerationException(String message, Throwable cause) { super(message, cause); }
    }
}

package org.apiforgeframework.plugin.generator;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Intermediate representation of an OpenAPI spec consumed by the code generators.
 * Decouples Mustache templates from the swagger-parser object graph.
 */
@Data
@Builder
public class ForgeApiModel {

    private String title;
    private String version;
    /** Base path extracted from servers[0], e.g. "/api/v1". Empty string if absent. */
    private String basePath;
    private List<ForgeOperation> operations;
    private List<ForgeSchema> schemas;

    // ── Operation ─────────────────────────────────────────────────────────────

    @Data
    @Builder
    public static class ForgeOperation {
        private String operationId;
        private String summary;
        private String description;
        private String httpMethod;
        private String path;
        private List<ForgeParameter> parameters;
        private boolean requestBodyRequired;
        private String requestBodySchema;
        private String responseSchema;
        /** Actual HTTP status code read from spec — NOT defaulted to 201 for all POST */
        private int successStatusCode;
        private List<String> tags;
        private boolean deprecated;

        // ── Template helpers ──────────────────────────────────────────────────

        public String getSpringMappingAnnotation() {
            return switch (httpMethod.toUpperCase()) {
                case "GET"    -> "@GetMapping";
                case "POST"   -> "@PostMapping";
                case "PUT"    -> "@PutMapping";
                case "DELETE" -> "@DeleteMapping";
                case "PATCH"  -> "@PatchMapping";
                default       -> "@RequestMapping(method = RequestMethod." + httpMethod + ")";
            };
        }

        public boolean isHasRequestBody() {
            return requestBodySchema != null && !requestBodySchema.isBlank();
        }

        public boolean isReturnsVoid() {
            return responseSchema == null || responseSchema.isBlank();
        }

        /** Returns path params only */
        public List<ForgeParameter> getPathParams() {
            if (parameters == null) return List.of();
            return parameters.stream().filter(p -> "path".equals(p.getIn())).toList();
        }

        /** Returns query params only */
        public List<ForgeParameter> getQueryParams() {
            if (parameters == null) return List.of();
            return parameters.stream().filter(p -> "query".equals(p.getIn())).toList();
        }

        /**
         * Returns all parameters as a pre-built comma-joined Java declaration string.
         * Eliminates the trailing-comma bug in Mustache templates.
         *
         * Example: "@PathVariable String orderId, @Valid @RequestBody CreateOrderRequest requestBody"
         */
        public String getParameterDeclaration() {
            List<String> parts = new ArrayList<>();
            if (parameters != null) {
                for (ForgeParameter p : parameters) {
                    String ann = p.getSpringAnnotation();
                    parts.add((ann.isBlank() ? "" : ann + " ") + p.getJavaType() + " " + p.getCamelCaseName());
                }
            }
            if (isHasRequestBody()) {
                parts.add("@Valid @RequestBody " + requestBodySchema + " requestBody");
            }
            return String.join(", ", parts);
        }

        /**
         * Returns all parameter names as a comma-joined call argument string.
         * Eliminates the trailing-comma bug in Mustache templates.
         *
         * Example: "orderId, requestBody"
         */
        public String getParameterCallArgs() {
            List<String> args = new ArrayList<>();
            if (parameters != null) {
                parameters.forEach(p -> args.add(p.getCamelCaseName()));
            }
            if (isHasRequestBody()) {
                args.add("requestBody");
            }
            return String.join(", ", args);
        }

        /** For service interface — same args but "request" instead of "requestBody" */
        public String getServiceMethodDeclaration() {
            List<String> parts = new ArrayList<>();
            if (parameters != null) {
                for (ForgeParameter p : parameters) {
                    parts.add(p.getJavaType() + " " + p.getCamelCaseName());
                }
            }
            if (isHasRequestBody()) {
                parts.add(requestBodySchema + " request");
            }
            return String.join(", ", parts);
        }
    }

    // ── Parameter ─────────────────────────────────────────────────────────────

    @Data
    @Builder
    public static class ForgeParameter {
        private String name;
        private String in;   // path | query | header | cookie
        private boolean required;
        private String javaType;

        public String getSpringAnnotation() {
            return switch (in) {
                case "path"   -> "@PathVariable";
                case "query"  -> required ? "@RequestParam" : "@RequestParam(required = false)";
                case "header" -> "@RequestHeader(\"" + name + "\")";
                default       -> "";
            };
        }

        /** Converts snake_case or kebab-case param name to camelCase for Java identifiers */
        public String getCamelCaseName() {
            if (name == null || name.isEmpty()) return name;
            String[] parts = name.split("[-_]");
            if (parts.length == 1) return name;
            StringBuilder sb = new StringBuilder(parts[0].toLowerCase());
            for (int i = 1; i < parts.length; i++) {
                String p = parts[i];
                if (!p.isEmpty()) {
                    sb.append(Character.toUpperCase(p.charAt(0)));
                    sb.append(p.substring(1).toLowerCase());
                }
            }
            return sb.toString();
        }
    }

    // ── Schema (DTO) ──────────────────────────────────────────────────────────

    @Data
    @Builder
    public static class ForgeSchema {
        private String name;
        private List<ForgeProperty> properties;
    }

    @Data
    @Builder
    public static class ForgeProperty {
        private String name;
        private String javaType;
        private boolean required;
        private String description;
        private String format;
        private String minimum;
        private String maximum;
        private Integer minLength;
        private Integer maxLength;
        private String pattern;
        /** True when the OpenAPI type is "integer" — drives @Min/@Max vs @DecimalMin/@DecimalMax */
        private boolean isIntegerType;

        /**
         * Returns Jakarta Validation annotations appropriate for this property's type.
         *
         * Fix: integer fields use @Min/@Max; decimal/number fields use @DecimalMin/@DecimalMax.
         * This matches the Jakarta Validation spec — @DecimalMin on an Integer field
         * compiles but is semantically wrong and triggers IDE warnings.
         */
        public List<String> getValidationAnnotations() {
            List<String> anns = new ArrayList<>();

            if (required) {
                if ("String".equals(javaType)) {
                    anns.add("@NotBlank");
                } else {
                    anns.add("@NotNull");
                }
            }

            if (minimum != null) {
                anns.add(isIntegerType
                        ? "@Min(" + minimum.replaceAll("\\.0+$", "") + ")"
                        : "@DecimalMin(\"" + minimum + "\")");
            }
            if (maximum != null) {
                anns.add(isIntegerType
                        ? "@Max(" + maximum.replaceAll("\\.0+$", "") + ")"
                        : "@DecimalMax(\"" + maximum + "\")");
            }
            if (minLength != null || maxLength != null) {
                String sizeAnn = "@Size(";
                if (minLength != null) sizeAnn += "min = " + minLength;
                if (minLength != null && maxLength != null) sizeAnn += ", ";
                if (maxLength != null) sizeAnn += "max = " + maxLength;
                sizeAnn += ")";
                anns.add(sizeAnn);
            }
            if (pattern != null) {
                // Escape backslashes for Java string literal
                String escaped = pattern.replace("\\", "\\\\").replace("\"", "\\\"");
                anns.add("@Pattern(regexp = \"" + escaped + "\")");
            }
            // Cascade validation into nested objects / list items
            if (javaType != null && (javaType.startsWith("List<") || isComplexType())) {
                anns.add("@Valid");
            }
            return anns;
        }

        private boolean isComplexType() {
            if (javaType == null) return false;
            // Not a known primitive or java.* type → treat as a complex DTO
            return !javaType.startsWith("java.")
                    && !javaType.startsWith("List")
                    && !List.of("String","Integer","Long","Boolean",
                                "Float","Double","Object","byte[]").contains(javaType);
        }

        /** Converts snake_case property name to camelCase field name */
        public String getCamelCaseName() {
            if (name == null || name.isEmpty()) return name;
            String[] parts = name.split("_");
            if (parts.length == 1) return name;
            StringBuilder sb = new StringBuilder(parts[0].toLowerCase());
            for (int i = 1; i < parts.length; i++) {
                String p = parts[i];
                if (!p.isEmpty()) {
                    sb.append(Character.toUpperCase(p.charAt(0)));
                    sb.append(p.substring(1).toLowerCase());
                }
            }
            return sb.toString();
        }
    }
}

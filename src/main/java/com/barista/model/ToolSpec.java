package com.barista.model;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * A callable tool authored as a notebook.
 *
 * There is no new storage format — a tool notebook is a normal {@link Notebook} with
 * {@code metadata.kind = "tool"} and a {@code metadata.tool} block carrying the signature;
 * {@link com.barista.service.ToolService} projects it into this shape, binds the arguments as
 * language-native variables, and runs the notebook's code cells through that language's existing
 * execution service.
 *
 * @param id          notebook id (the tool's stable handle)
 * @param name        display name
 * @param slug        kebab-cased name, used for MCP tool names and deployed filenames
 * @param description one-line description (becomes the MCP tool description)
 * @param mode        execution mode of the body — jshell, java, nodejs, typescript, csharp,
 *                    fsharp, cpp or python
 * @param params      declared parameters, in order
 * @param body        the implementation — the notebook's code cells, concatenated
 */
public record ToolSpec(String id, String name, String slug, String description,
                       String mode, List<Param> params, String body) {

    /**
     * One declared parameter.
     *
     * @param name        identifier, also the variable name bound in the body
     * @param type        string | integer | number | boolean
     * @param description one-line description
     * @param required    whether a caller must supply it
     * @param defaultValue value used when an optional param is omitted (may be null)
     */
    public record Param(String name, String type, String description,
                        boolean required, String defaultValue) {

        /** Normalize an arbitrary type string to one of the four supported types. */
        public static String typeOf(String s) {
            if (s == null) return "string";
            return switch (s.toLowerCase().trim()) {
                case "int", "integer", "long" -> "integer";
                case "number", "double", "float" -> "number";
                case "bool", "boolean" -> "boolean";
                default -> "string";
            };
        }
    }

    /** JSON-Schema projection of this tool's parameters, for MCP {@code inputSchema}. */
    public Map<String, Object> inputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new java.util.ArrayList<>();
        for (Param p : params) {
            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", p.type());
            if (p.description() != null && !p.description().isBlank()) {
                prop.put("description", p.description());
            }
            properties.put(p.name(), prop);
            if (p.required()) required.add(p.name());
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        return schema;
    }
}

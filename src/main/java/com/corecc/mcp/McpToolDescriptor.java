package com.corecc.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Tool metadata returned by tools/list.
 */
public class McpToolDescriptor {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String name;
    private final String title;
    private final String description;
    private final Map<String, Object> inputSchema;
    private final Map<String, Object> annotations;

    public McpToolDescriptor(String name, String title, String description,
                             Map<String, Object> inputSchema,
                             Map<String, Object> annotations) {
        this.name = name;
        this.title = title;
        this.description = description;
        this.inputSchema = inputSchema != null ? inputSchema : Map.of("type", "object");
        this.annotations = annotations != null ? annotations : Map.of();
    }

    public static McpToolDescriptor fromJson(JsonNode node) {
        Map<String, Object> schema = node.has("inputSchema") && node.get("inputSchema").isObject()
            ? MAPPER.convertValue(node.get("inputSchema"), new TypeReference<Map<String, Object>>() {})
            : Map.of("type", "object", "additionalProperties", false);
        Map<String, Object> annotations = node.has("annotations") && node.get("annotations").isObject()
            ? MAPPER.convertValue(node.get("annotations"), new TypeReference<Map<String, Object>>() {})
            : Map.of();

        return new McpToolDescriptor(
            node.path("name").asText(),
            node.path("title").asText(""),
            node.path("description").asText(""),
            schema,
            annotations
        );
    }

    public String getName() {
        return name;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public Map<String, Object> getInputSchema() {
        return inputSchema;
    }

    public boolean readOnlyHint() {
        Object value = annotations.get("readOnlyHint");
        return value instanceof Boolean && (Boolean) value;
    }
}

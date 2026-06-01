package com.corecc.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads Claude-style mcpServers JSON configuration.
 */
public class McpConfigLoader {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static List<McpServerConfig> load(Path configPath, int timeoutSec) throws IOException {
        if (configPath == null || !Files.isRegularFile(configPath)) {
            return List.of();
        }

        JsonNode root = MAPPER.readTree(Files.readString(configPath));
        JsonNode servers = root.path("mcpServers");
        if (!servers.isObject()) {
            return List.of();
        }

        List<McpServerConfig> result = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = servers.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode node = field.getValue();
            String command = text(node.get("command"));
            if (command.isBlank()) {
                continue;
            }

            result.add(new McpServerConfig(
                cleanName(field.getKey()),
                command,
                stringList(node.get("args")),
                stringMap(node.get("env")),
                pathOrNull(node.get("cwd")),
                node.path("trusted").asBoolean(false),
                timeoutSec
            ));
        }
        return result;
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            values.add(item.asText());
        }
        return values;
    }

    private static Map<String, String> stringMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, Object> raw = MAPPER.convertValue(
            node, new TypeReference<Map<String, Object>>() {});
        Map<String, String> values = new LinkedHashMap<>();
        raw.forEach((key, value) -> values.put(key, value == null ? "" : String.valueOf(value)));
        return values;
    }

    private static Path pathOrNull(JsonNode node) {
        String value = text(node);
        return value.isBlank() ? null : Path.of(value);
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText();
    }

    private static String cleanName(String raw) {
        String cleaned = raw == null ? "" : raw.trim().replaceAll("[^A-Za-z0-9_.-]", "_");
        return cleaned.isEmpty() ? "server" : cleaned;
    }
}

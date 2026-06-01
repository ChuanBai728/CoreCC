package com.corecc.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class FakeMcpServer {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode request = MAPPER.readTree(line);
                String method = request.path("method").asText("");
                if (!request.has("id")) {
                    continue;
                }

                switch (method) {
                    case "initialize" -> respond(request, Map.of(
                        "protocolVersion", McpClient.PROTOCOL_VERSION,
                        "capabilities", Map.of("tools", Map.of("listChanged", false)),
                        "serverInfo", Map.of("name", "fake", "title", "Fake MCP", "version", "1.0.0"),
                        "instructions", "Use fake tools for tests."
                    ));
                    case "tools/list" -> respond(request, Map.of(
                        "tools", List.of(Map.of(
                            "name", "echo",
                            "title", "Echo",
                            "description", "Echo text back",
                            "inputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of("text", Map.of("type", "string")),
                                "required", List.of("text")
                            ),
                            "annotations", Map.of("readOnlyHint", true)
                        ))
                    ));
                    case "tools/call" -> {
                        String text = request.path("params").path("arguments").path("text").asText("");
                        respond(request, Map.of(
                            "content", List.of(Map.of("type", "text", "text", "echo:" + text)),
                            "isError", false
                        ));
                    }
                    default -> respondError(request, -32601, "Unknown method: " + method);
                }
            }
        }
    }

    private static void respond(JsonNode request, Map<String, Object> result) throws Exception {
        System.out.println(MAPPER.writeValueAsString(Map.of(
            "jsonrpc", "2.0",
            "id", request.get("id"),
            "result", result
        )));
        System.out.flush();
    }

    private static void respondError(JsonNode request, int code, String message) throws Exception {
        System.out.println(MAPPER.writeValueAsString(Map.of(
            "jsonrpc", "2.0",
            "id", request.get("id"),
            "error", Map.of("code", code, "message", message)
        )));
        System.out.flush();
    }
}

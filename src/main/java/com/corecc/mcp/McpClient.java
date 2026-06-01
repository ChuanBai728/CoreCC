package com.corecc.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal stdio MCP JSON-RPC client.
 */
public class McpClient implements Closeable {
    public static final String PROTOCOL_VERSION = "2025-11-25";

    private final McpServerConfig config;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong nextId = new AtomicLong(1);
    private final BlockingQueue<JsonNode> incoming = new LinkedBlockingQueue<>();
    private final Object writeLock = new Object();
    private final Process process;
    private final BufferedWriter writer;
    private volatile String serverInstructions = "";
    private volatile String serverDisplayName;

    private McpClient(McpServerConfig config, Process process) {
        this.config = config;
        this.process = process;
        this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.serverDisplayName = config.getName();
    }

    public static McpClient start(McpServerConfig config) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(config.getCommand());
        command.addAll(config.getArgs());

        ProcessBuilder builder = new ProcessBuilder(command);
        if (config.getCwd() != null) {
            builder.directory(config.getCwd().toFile());
        }
        builder.environment().putAll(config.getEnv());

        Process process = builder.start();
        McpClient client = new McpClient(config, process);
        client.startReaders();
        client.initialize();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }));
        return client;
    }

    public String getServerName() {
        return config.getName();
    }

    public String getServerDisplayName() {
        return serverDisplayName;
    }

    public String getServerInstructions() {
        return serverInstructions;
    }

    public synchronized List<McpToolDescriptor> listTools() throws IOException {
        List<McpToolDescriptor> tools = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 20; page++) {
            Map<String, Object> params = new LinkedHashMap<>();
            if (cursor != null && !cursor.isBlank()) {
                params.put("cursor", cursor);
            }

            JsonNode result = request("tools/list", params);
            JsonNode toolNodes = result.path("tools");
            if (toolNodes.isArray()) {
                for (JsonNode toolNode : toolNodes) {
                    if (!toolNode.path("name").asText("").isBlank()) {
                        tools.add(McpToolDescriptor.fromJson(toolNode));
                    }
                }
            }

            cursor = result.path("nextCursor").asText("");
            if (cursor.isBlank()) {
                break;
            }
        }
        return tools;
    }

    public synchronized String callTool(String toolName, Map<String, Object> arguments) throws IOException {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", toolName);
        params.put("arguments", arguments != null ? arguments : Map.of());

        JsonNode result = request("tools/call", params);
        return formatToolResult(result);
    }

    @Override
    public void close() throws IOException {
        process.destroy();
    }

    private void initialize() throws IOException {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of(
            "name", "corecc",
            "title", "CoreCC",
            "version", "0.3.0"
        ));

        JsonNode result = request("initialize", params);
        serverDisplayName = result.path("serverInfo").path("title").asText(
            result.path("serverInfo").path("name").asText(config.getName()));
        serverInstructions = result.path("instructions").asText("");
        sendNotification("notifications/initialized", null);
    }

    private JsonNode request(String method, Map<String, Object> params) throws IOException {
        String id = String.valueOf(nextId.getAndIncrement());
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.put("params", params != null ? params : Map.of());

        write(message);
        long deadline = System.nanoTime() + Duration.ofSeconds(config.getTimeoutSec()).toNanos();
        while (true) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new IOException("MCP server '" + config.getName() + "' timed out waiting for " + method);
            }

            JsonNode response;
            try {
                response = incoming.poll(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted waiting for MCP response", e);
            }
            if (response == null) {
                continue;
            }

            if (isResponseFor(response, id)) {
                if (response.has("error")) {
                    throw new IOException("MCP error from '" + config.getName() + "': " + response.get("error"));
                }
                return response.path("result");
            }
            handleServerMessage(response);
        }
    }

    private void sendNotification(String method, Map<String, Object> params) throws IOException {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        if (params != null) {
            message.put("params", params);
        }
        write(message);
    }

    private void handleServerMessage(JsonNode message) throws IOException {
        if (!message.has("method") || !message.has("id")) {
            return;
        }

        String method = message.path("method").asText("");
        Object id = mapper.convertValue(message.get("id"), Object.class);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        if ("ping".equals(method)) {
            response.put("result", Map.of());
        } else {
            response.put("error", Map.of(
                "code", -32601,
                "message", "CoreCC MCP client does not implement " + method
            ));
        }
        write(response);
    }

    private boolean isResponseFor(JsonNode message, String expectedId) {
        return message.has("id") && expectedId.equals(message.get("id").asText());
    }

    private void write(Map<String, Object> message) throws IOException {
        synchronized (writeLock) {
            writer.write(mapper.writeValueAsString(message));
            writer.write("\n");
            writer.flush();
        }
    }

    private void startReaders() {
        Thread stdout = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        incoming.offer(mapper.readTree(line));
                    } catch (JsonProcessingException ignored) {
                    }
                }
            } catch (IOException ignored) {
            }
        }, "corecc-mcp-stdout-" + config.getName());
        stdout.setDaemon(true);
        stdout.start();

        Thread stderr = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                while (reader.readLine() != null) {
                    // Drain stderr to avoid blocking noisy servers.
                }
            } catch (IOException ignored) {
            }
        }, "corecc-mcp-stderr-" + config.getName());
        stderr.setDaemon(true);
        stderr.start();
    }

    private String formatToolResult(JsonNode result) {
        List<String> parts = new ArrayList<>();
        JsonNode content = result.path("content");
        if (content.isArray()) {
            for (JsonNode item : content) {
                if ("text".equals(item.path("type").asText("")) && item.has("text")) {
                    parts.add(item.path("text").asText());
                } else {
                    parts.add(compactJson(item));
                }
            }
        }

        if (result.has("structuredContent")) {
            parts.add("structuredContent: " + compactJson(result.get("structuredContent")));
        }

        String text = String.join("\n", parts).trim();
        if (text.isEmpty()) {
            text = compactJson(result);
        }
        if (result.path("isError").asBoolean(false)) {
            text = "MCP tool error: " + text;
        }
        return text;
    }

    private String compactJson(JsonNode node) {
        try {
            Object value = mapper.convertValue(node, new TypeReference<Object>() {});
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return node.toString();
        }
    }
}

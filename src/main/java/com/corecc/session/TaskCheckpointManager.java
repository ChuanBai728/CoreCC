package com.corecc.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Task-level checkpoints for crash-safe recovery.
 */
public class TaskCheckpointManager {
    private static final Pattern SAFE_ID_RE = Pattern.compile("[^A-Za-z0-9._-]+");
    private static final ObjectMapper mapper = new ObjectMapper();

    private TaskCheckpointManager() {
    }

    public static String newCheckpointId() {
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now());
        String random = UUID.randomUUID().toString().substring(0, 8);
        return "task_" + timestamp + "_" + random;
    }

    public static String normalizeCheckpointId(String checkpointId) {
        if (checkpointId == null || checkpointId.trim().isEmpty()) {
            return newCheckpointId();
        }
        String name = checkpointId.trim().replace("\\", "/");
        if (name.contains("/")) {
            name = name.substring(name.lastIndexOf("/") + 1);
        }
        if (name.endsWith(".json")) {
            name = name.substring(0, name.length() - ".json".length());
        }
        name = SAFE_ID_RE.matcher(name).replaceAll("-").replaceAll("^[._-]+|[._-]+$", "");
        return name.isEmpty() ? newCheckpointId() : name;
    }

    public static Path checkpointsDir() {
        String override = System.getProperty("corecc.checkpointsDir");
        if (override != null && !override.isBlank()) {
            return Paths.get(override).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.home"), ".corecc", "checkpoints")
            .toAbsolutePath()
            .normalize();
    }

    public static Path getCheckpointPath(String checkpointId) {
        Path root = checkpointsDir();
        Path path = root.resolve(normalizeCheckpointId(checkpointId) + ".json").toAbsolutePath().normalize();
        if (!root.equals(path.getParent())) {
            throw new IllegalArgumentException("Invalid checkpoint ID");
        }
        return path;
    }

    public static String saveCheckpoint(String checkpointId,
                                        String model,
                                        List<Map<String, Object>> messages,
                                        String status,
                                        String phase,
                                        int round,
                                        String taskInput,
                                        Map<String, Object> metadata) {
        try {
            String id = normalizeCheckpointId(checkpointId);
            Path path = getCheckpointPath(id);
            Files.createDirectories(path.getParent());

            TaskCheckpoint existing = loadCheckpoint(id);
            String createdAt = existing != null ? existing.createdAt() : now();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", "task_checkpoint");
            data.put("id", id);
            data.put("model", model);
            data.put("created_at", createdAt);
            data.put("updated_at", now());
            data.put("status", safe(status, "running"));
            data.put("phase", safe(phase, "unknown"));
            data.put("round", round);
            data.put("task_input", taskInput != null ? taskInput : "");
            data.put("messages", messages != null ? messages : List.of());
            data.put("metadata", metadata != null ? metadata : Map.of());

            Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data),
                StandardCharsets.UTF_8);
            return id;
        } catch (IOException e) {
            throw new RuntimeException("Failed to save task checkpoint: " + e.getMessage(), e);
        }
    }

    public static TaskCheckpoint loadCheckpoint(String checkpointId) {
        try {
            Path path = getCheckpointPath(checkpointId);
            if (!Files.exists(path)) {
                return null;
            }
            Map<String, Object> data = mapper.readValue(Files.readString(path, StandardCharsets.UTF_8),
                new TypeReference<Map<String, Object>>() {});
            if (!"task_checkpoint".equals(data.get("type"))) {
                return null;
            }
            return fromMap(data);
        } catch (IOException e) {
            return null;
        }
    }

    public static SessionManager.SessionData loadAsSession(String checkpointId) {
        TaskCheckpoint checkpoint = loadCheckpoint(checkpointId);
        if (checkpoint == null) {
            return null;
        }
        return new SessionManager.SessionData(checkpoint.messages(), checkpoint.model());
    }

    public static List<TaskCheckpointInfo> listCheckpoints() {
        try {
            Path dir = checkpointsDir();
            if (!Files.isDirectory(dir)) {
                return List.of();
            }
            try (var paths = Files.list(dir)) {
                return paths
                    .filter(path -> path.toString().endsWith(".json"))
                    .sorted(Comparator.comparing(Path::toString).reversed())
                    .limit(20)
                    .map(TaskCheckpointManager::loadInfoQuietly)
                    .filter(info -> info != null)
                    .collect(Collectors.toList());
            }
        } catch (IOException e) {
            return List.of();
        }
    }

    private static TaskCheckpointInfo loadInfoQuietly(Path path) {
        try {
            Map<String, Object> data = mapper.readValue(Files.readString(path, StandardCharsets.UTF_8),
                new TypeReference<Map<String, Object>>() {});
            if (!"task_checkpoint".equals(data.get("type"))) {
                return null;
            }
            return new TaskCheckpointInfo(
                string(data.get("id")),
                string(data.get("model")),
                string(data.get("status")),
                string(data.get("phase")),
                string(data.get("updated_at")),
                preview(string(data.get("task_input")))
            );
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static TaskCheckpoint fromMap(Map<String, Object> data) {
        Object rawMessages = data.get("messages");
        List<Map<String, Object>> messages = rawMessages instanceof List
            ? (List<Map<String, Object>>) rawMessages
            : new ArrayList<>();
        Object rawMetadata = data.get("metadata");
        Map<String, Object> metadata = rawMetadata instanceof Map
            ? (Map<String, Object>) rawMetadata
            : Map.of();
        int round = data.get("round") instanceof Number ? ((Number) data.get("round")).intValue() : -1;
        return new TaskCheckpoint(
            string(data.get("id")),
            string(data.get("model")),
            string(data.get("created_at")),
            string(data.get("updated_at")),
            string(data.get("status")),
            string(data.get("phase")),
            round,
            string(data.get("task_input")),
            messages,
            metadata
        );
    }

    private static String now() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now());
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String preview(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.length() <= 80 ? text : text.substring(0, 80);
    }

    public record TaskCheckpoint(String id, String model, String createdAt, String updatedAt,
                                 String status, String phase, int round, String taskInput,
                                 List<Map<String, Object>> messages, Map<String, Object> metadata) {
    }

    public record TaskCheckpointInfo(String id, String model, String status, String phase,
                                     String updatedAt, String preview) {
    }
}

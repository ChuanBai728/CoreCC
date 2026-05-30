package com.corecc.session;

import com.corecc.llm.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 会话持久化 —— 保存和恢复对话。
 *
 * 对应 Python 版的 corecc/session.py。
 */
public class SessionManager {
    private static final Path SESSIONS_DIR = Paths.get(System.getProperty("user.home"), ".corecc", "sessions");
    private static final Pattern SAFE_SESSION_RE = Pattern.compile("[^A-Za-z0-9._-]+");
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * 规范化会话 ID。
     */
    public static String normalizeSessionId(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return newSessionId();
        }

        String name = sessionId.trim().replace("\\", "/");
        if (name.contains("/")) {
            name = name.substring(name.lastIndexOf("/") + 1);
        }
        name = SAFE_SESSION_RE.matcher(name).replaceAll("-").replaceAll("^[._-]+|[._-]+$", "");
        return name.isEmpty() ? newSessionId() : name;
    }

    /**
     * 生成新的会话 ID。
     */
    public static String newSessionId() {
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now());
        String random = UUID.randomUUID().toString().substring(0, 8);
        return String.format("session_%s_%s", timestamp, random);
    }

    /**
     * 获取会话文件路径。
     */
    public static Path getSessionPath(String sessionId) {
        Path path = SESSIONS_DIR.resolve(normalizeSessionId(sessionId) + ".json").toAbsolutePath().normalize();
        Path root = SESSIONS_DIR.toAbsolutePath().normalize();
        if (!root.equals(path.getParent())) {
            throw new IllegalArgumentException("无效的会话 ID");
        }
        return path;
    }

    /**
     * 保存会话到磁盘。
     */
    public static String saveSession(List<Map<String, Object>> messages, String model, String sessionId) {
        try {
            Files.createDirectories(SESSIONS_DIR);
            String id = normalizeSessionId(sessionId);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", id);
            data.put("model", model);
            data.put("saved_at", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.now()));
            data.put("messages", messages);

            Path path = getSessionPath(id);
            Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data));
            return id;
        } catch (IOException e) {
            throw new RuntimeException("保存会话失败：" + e.getMessage(), e);
        }
    }

    /**
     * 加载已保存的会话。
     */
    public static SessionData loadSession(String sessionId) {
        try {
            Path path = getSessionPath(sessionId);
            if (!Files.exists(path)) {
                return null;
            }

            Map<String, Object> data = mapper.readValue(Files.readString(path),
                new TypeReference<Map<String, Object>>() {});

            String model = (String) data.get("model");
            List<Map<String, Object>> messages = (List<Map<String, Object>>) data.get("messages");
            return new SessionData(messages, model);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 列出所有可用会话。
     */
    public static List<SessionInfo> listSessions() {
        try {
            if (!Files.exists(SESSIONS_DIR)) {
                return Collections.emptyList();
            }

            List<SessionInfo> sessions = new ArrayList<>();
            List<Path> files = Files.list(SESSIONS_DIR)
                .filter(p -> p.toString().endsWith(".json"))
                .sorted(Comparator.reverseOrder())
                .limit(20)
                .collect(Collectors.toList());

            for (Path f : files) {
                try {
                    Map<String, Object> data = mapper.readValue(Files.readString(f),
                        new TypeReference<Map<String, Object>>() {});

                    String preview = "";
                    List<Map<String, Object>> messages = (List<Map<String, Object>>) data.get("messages");
                    if (messages != null) {
                        for (Map<String, Object> m : messages) {
                            if ("user".equals(m.get("role")) && m.get("content") != null) {
                                preview = ((String) m.get("content")).substring(0,
                                    Math.min(80, ((String) m.get("content")).length()));
                                break;
                            }
                        }
                    }

                    sessions.add(new SessionInfo(
                        (String) data.getOrDefault("id", f.getFileName().toString().replace(".json", "")),
                        (String) data.getOrDefault("model", "?"),
                        (String) data.getOrDefault("saved_at", "?"),
                        preview
                    ));
                } catch (Exception e) {
                    // Skip invalid session files
                }
            }

            return sessions;
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    /**
     * 会话数据。
     */
    public static class SessionData {
        public final List<Map<String, Object>> messages;
        public final String model;

        public SessionData(List<Map<String, Object>> messages, String model) {
            this.messages = messages;
            this.model = model;
        }
    }

    /**
     * 会话信息。
     */
    public static class SessionInfo {
        public final String id;
        public final String model;
        public final String savedAt;
        public final String preview;

        public SessionInfo(String id, String model, String savedAt, String preview) {
            this.id = id;
            this.model = model;
            this.savedAt = savedAt;
            this.preview = preview;
        }
    }
}

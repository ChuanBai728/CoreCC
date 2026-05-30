package com.corecc.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 工作区范围的长期记忆存储。
 *
 * 对应 Python 版的 corecc/memory.py 中的 MemoryStore 类。
 */
public class MemoryStore {
    public static final Path MEMORY_ROOT = Paths.get(System.getProperty("user.home"), ".corecc", "memory");
    public static final Set<String> MEMORY_TYPES = Set.of("user", "feedback", "project", "reference");
    public static final Set<String> MEMORY_SCOPES = Set.of("private", "team");
    public static final String INDEX_NAME = "MEMORY.md";

    private static final Pattern SECRET_RE = Pattern.compile(
        "(sk-[A-Za-z0-9_-]{12,}|api[_-]?key\\s*[=:]|password\\s*[=:]|secret\\s*[=:]|token\\s*[=:])",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern TRANSIENT_RE = Pattern.compile(
        "(当前任务|这次任务|今天|本周|当前分支|临时分支|PR\\s*#?\\d+|正在修|刚刚改|还有\\d+项)",
        Pattern.CASE_INSENSITIVE
    );

    private final Path path;
    private final String workspace;

    public MemoryStore(Path path, String workspace) {
        this.path = path;
        this.workspace = workspace;
    }

    /**
     * 为当前工作区创建 MemoryStore。
     */
    public static MemoryStore forWorkspace(Path cwd, Path root) {
        String workspace = (cwd != null ? cwd : Paths.get("").toAbsolutePath()).toString();
        String digest = sha256(workspace.toLowerCase());
        Path base = root != null ? root : MEMORY_ROOT;
        return new MemoryStore(base.resolve(digest.substring(0, 16)), workspace);
    }

    /**
     * 获取索引文件路径。
     */
    public Path getIndexpath() {
        return path.resolve(INDEX_NAME);
    }

    /**
     * 添加一条记忆。
     */
    public MemoryEntry add(String content, List<String> tags, String name, String description,
                          String memoryType, String scope) {
        content = content.trim();
        validateMemoryContent(content);

        String memType = normalizeType(memoryType != null ? memoryType : "project");
        String memScope = normalizeScope(scope != null ? scope : defaultScope(memType));

        List<MemoryEntry> entries = load();
        String now = now();
        String entryId = UUID.randomUUID().toString().substring(0, 8);
        String entryName = slug(name != null ? name : nameFromContent(content));
        if (entryName.isEmpty()) entryName = "memory-" + entryId;

        MemoryEntry entry = new MemoryEntry(
            entryId,
            entryName,
            description != null ? description : content.substring(0, Math.min(120, content.length())),
            memType,
            content,
            memScope,
            tags != null ? tags : new ArrayList<>(),
            now, now, 0,
            entryName + "-" + entryId + ".md"
        );

        entries.add(entry);
        save(entries);
        return entry;
    }

    /**
     * 列出最近的记忆。
     */
    public List<MemoryEntry> listRecent(int limit) {
        List<MemoryEntry> entries = load();
        entries.sort((a, b) -> {
            String dateA = a.getUpdatedAt() != null ? a.getUpdatedAt() : a.getCreatedAt();
            String dateB = b.getUpdatedAt() != null ? b.getUpdatedAt() : b.getCreatedAt();
            return dateB.compareTo(dateA);
        });
        return entries.subList(0, Math.min(limit, entries.size()));
    }

    /**
     * 搜索记忆。
     */
    public List<MemoryEntry> search(String query, int limit, boolean recordHits) {
        query = query.trim();
        if (query.isEmpty()) {
            return listRecent(limit);
        }

        List<MemoryEntry> entries = load();
        Set<String> terms = terms(query);
        List<ScoredEntry> scored = new ArrayList<>();

        for (MemoryEntry entry : entries) {
            String text = String.join(" ", entry.getName(), entry.getDescription(),
                entry.getType(), entry.getScope(), entry.getContent(),
                String.join(" ", entry.getTags())).toLowerCase();

            int score = 0;
            for (String term : terms) {
                if (text.contains(term)) score += 3;
            }
            if (text.contains(query.toLowerCase())) score += 5;

            if (score > 0) {
                scored.add(new ScoredEntry(score + entry.getHits(), entry));
            }
        }

        scored.sort((a, b) -> {
            if (a.score != b.score) return b.score - a.score;
            return b.entry.getUpdatedAt().compareTo(a.entry.getUpdatedAt());
        });

        List<MemoryEntry> matches = scored.stream()
            .limit(limit)
            .map(se -> se.entry)
            .collect(Collectors.toList());

        if (recordHits && !matches.isEmpty()) {
            Set<String> matchIds = matches.stream()
                .map(MemoryEntry::getId)
                .collect(Collectors.toSet());
            String now = now();
            for (MemoryEntry entry : entries) {
                if (matchIds.contains(entry.getId())) {
                    entry.setHits(entry.getHits() + 1);
                    entry.setUpdatedAt(now);
                }
            }
            save(entries);
        }

        return matches;
    }

    /**
     * 删除记忆。
     */
    public boolean delete(String entryId) {
        String prefix = entryId.trim().toLowerCase();
        if (prefix.isEmpty()) return false;

        List<MemoryEntry> entries = load();
        List<MemoryEntry> kept = entries.stream()
            .filter(e -> !e.getId().toLowerCase().startsWith(prefix) &&
                        !e.getName().toLowerCase().startsWith(prefix))
            .collect(Collectors.toList());

        if (kept.size() == entries.size()) return false;
        save(kept);
        return true;
    }

    /**
     * 加载所有记忆条目。
     */
    private List<MemoryEntry> load() {
        List<MemoryEntry> entries = loadMarkdownEntries();
        return entries;
    }

    /**
     * 从 Markdown 文件加载记忆。
     */
    private List<MemoryEntry> loadMarkdownEntries() {
        if (!Files.exists(path)) {
            return new ArrayList<>();
        }

        List<MemoryEntry> entries = new ArrayList<>();
        try {
            Files.list(path)
                .filter(p -> p.toString().endsWith(".md") && !p.getFileName().toString().equals(INDEX_NAME))
                .sorted()
                .forEach(p -> {
                    MemoryEntry parsed = readMemoryFile(p);
                    if (parsed != null) {
                        entries.add(parsed);
                    }
                });
        } catch (IOException e) {
            // Return empty on error
        }
        return entries;
    }

    /**
     * 保存记忆到磁盘。
     */
    private void save(List<MemoryEntry> entries) {
        try {
            Files.createDirectories(path);

            // Remove files not in current entries
            Set<String> currentFiles = entries.stream()
                .map(e -> e.getFilename() != null ? e.getFilename() : e.getName() + "-" + e.getId() + ".md")
                .collect(Collectors.toSet());

            if (Files.exists(path)) {
                Files.list(path)
                    .filter(p -> !p.getFileName().toString().equals(INDEX_NAME) &&
                                !currentFiles.contains(p.getFileName().toString()))
                    .forEach(p -> {
                        try { Files.delete(p); } catch (IOException ignored) {}
                    });
            }

            // Write each entry
            for (MemoryEntry entry : entries) {
                if (entry.getFilename() == null) {
                    entry.setFilename(entry.getName() + "-" + entry.getId() + ".md");
                }
                Files.writeString(path.resolve(entry.getFilename()), formatMemoryFile(entry));
            }

            // Write index
            Files.writeString(getIndexpath(), formatIndex(entries, workspace));
        } catch (IOException e) {
            throw new RuntimeException("保存记忆失败：" + e.getMessage(), e);
        }
    }

    /**
     * 格式化记忆为 Markdown 文件。
     */
    private String formatMemoryFile(MemoryEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("id: ").append(escapeMeta(entry.getId())).append("\n");
        sb.append("name: ").append(escapeMeta(entry.getName())).append("\n");
        sb.append("description: ").append(escapeMeta(entry.getDescription())).append("\n");
        sb.append("type: ").append(escapeMeta(entry.getType())).append("\n");
        sb.append("scope: ").append(escapeMeta(entry.getScope())).append("\n");
        sb.append("tags: ").append(escapeMeta(String.join(", ", entry.getTags()))).append("\n");
        sb.append("created_at: ").append(escapeMeta(entry.getCreatedAt())).append("\n");
        sb.append("updated_at: ").append(escapeMeta(entry.getUpdatedAt())).append("\n");
        sb.append("hits: ").append(entry.getHits()).append("\n");
        sb.append("---\n\n");
        sb.append(entry.getContent().trim()).append("\n");
        return sb.toString();
    }

    /**
     * 格式化索引文件。
     */
    private String formatIndex(List<MemoryEntry> entries, String workspace) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Memory Index\n\n");
        sb.append("Workspace: ").append(workspace != null ? workspace : "?").append("\n\n");
        sb.append("Only store cross-session knowledge that cannot be cheaply rediscovered from the current repo.\n\n");

        entries.stream()
            .sorted(Comparator.comparing(MemoryEntry::getType).thenComparing(MemoryEntry::getName))
            .forEach(e -> sb.append(String.format("- %s: %s [%s/%s] (%s)\n",
                e.getName(), e.getDescription(), e.getType(), e.getScope(), e.getId())));

        sb.append("\n");
        return sb.toString();
    }

    /**
     * 读取记忆文件。
     */
    private MemoryEntry readMemoryFile(Path path) {
        try {
            String text = Files.readString(path);
            FrontmatterResult result = splitFrontmatter(text);
            if (result.meta.isEmpty()) return null;

            Map<String, String> meta = result.meta;
            String memType = normalizeType(meta.getOrDefault("type", "project"));
            String scope = normalizeScope(meta.getOrDefault("scope", defaultScope(memType)));
            String entryId = meta.getOrDefault("id", path.getFileName().toString().split("-")[0]);
            String name = slug(meta.getOrDefault("name", path.getFileName().toString()));

            return new MemoryEntry(
                entryId,
                name,
                meta.getOrDefault("description", result.content.substring(0, Math.min(120, result.content.length()))),
                memType,
                result.content.trim(),
                scope,
                Arrays.stream(meta.getOrDefault("tags", "").split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList()),
                meta.getOrDefault("created_at", ""),
                meta.getOrDefault("updated_at", ""),
                Integer.parseInt(meta.getOrDefault("hits", "0")),
                path.getFileName().toString()
            );
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 分割 frontmatter 和内容。
     */
    private FrontmatterResult splitFrontmatter(String text) {
        if (!text.startsWith("---\n")) {
            return new FrontmatterResult(Map.of(), text);
        }

        int end = text.indexOf("\n---", 4);
        if (end == -1) {
            return new FrontmatterResult(Map.of(), text);
        }

        String rawMeta = text.substring(4, end).trim();
        String content = text.substring(end + 4).trim();

        Map<String, String> meta = new LinkedHashMap<>();
        for (String line : rawMeta.split("\n")) {
            int colonIdx = line.indexOf(':');
            if (colonIdx == -1) continue;
            String key = line.substring(0, colonIdx).trim();
            String value = line.substring(colonIdx + 1).trim();
            if (value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            meta.put(key, value);
        }

        return new FrontmatterResult(meta, content);
    }

    /**
     * 验证记忆内容。
     */
    private void validateMemoryContent(String content) {
        if (content.isEmpty()) {
            throw new IllegalArgumentException("记忆内容不能为空");
        }
        if (SECRET_RE.matcher(content).find()) {
            throw new IllegalArgumentException("拒绝保存疑似密钥、密码或凭证");
        }
        if (TRANSIENT_RE.matcher(content).find()) {
            throw new IllegalArgumentException("这更像当前任务状态，不适合进入长期记忆");
        }
    }

    private String normalizeType(String type) {
        String value = type != null ? type.trim().toLowerCase() : "project";
        if (!MEMORY_TYPES.contains(value)) {
            throw new IllegalArgumentException("无效记忆类型：" + type + "，可选：" + String.join(", ", MEMORY_TYPES));
        }
        return value;
    }

    private String normalizeScope(String scope) {
        String value = scope != null ? scope.trim().toLowerCase() : "private";
        if (!MEMORY_SCOPES.contains(value)) {
            throw new IllegalArgumentException("无效记忆作用域：" + scope + "，可选：" + String.join(", ", MEMORY_SCOPES));
        }
        return value;
    }

    private String defaultScope(String type) {
        return "project".equals(type) || "reference".equals(type) ? "team" : "private";
    }

    private String nameFromContent(String content) {
        Set<String> terms = terms(content);
        return terms.stream().sorted().limit(6).collect(Collectors.joining("-"));
    }

    private String slug(String text) {
        String value = text.replaceAll("[^A-Za-z0-9._\\-\\u4e00-\\u9fff]+", "-").trim();
        value = value.replaceAll("^[._-]+|[._-]+$", "");
        return value.length() > 60 ? value.substring(0, 60) : value;
    }

    private String escapeMeta(String value) {
        String text = value != null ? value.replace("\n", " ").trim() : "";
        if (text.contains(":") || text.contains("#")) {
            return "\"" + text + "\"";
        }
        return text;
    }

    private Set<String> terms(String text) {
        Set<String> tokens = new HashSet<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '/' || c == '-' || c == ':') {
                current.append(Character.toLowerCase(c));
            } else {
                if (current.length() > 1 || (current.length() == 1 && isCJK(current.charAt(0)))) {
                    tokens.add(current.toString());
                }
                current.setLength(0);
            }
        }
        if (current.length() > 1 || (current.length() == 1 && isCJK(current.charAt(0)))) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private boolean isCJK(char c) {
        return (c >= '一' && c <= '鿿');
    }

    private String now() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneId.of("UTC"))
            .format(Instant.now());
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return "error";
        }
    }

    /**
     * 推断记忆类型。
     */
    public static String inferMemoryType(String content) {
        String lowered = content.toLowerCase();
        if (lowered.contains("http://") || lowered.contains("https://") ||
            content.contains("看板") || content.contains("资料")) {
            return "reference";
        }
        if (content.contains("不要") || content.contains("以后") || content.contains("纠正") ||
            content.contains("之前错") || content.contains("下次")) {
            return "feedback";
        }
        if (content.contains("我喜欢") || content.contains("我希望") || content.contains("我偏好") ||
            content.contains("用户偏好") || content.contains("我的")) {
            return "user";
        }
        return "project";
    }

    /**
     * 格式化记忆块用于注入系统提示。
     */
    public static String formatMemoryBlock(List<MemoryEntry> entries, int maxChars) {
        if (entries == null || entries.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("[长期记忆]\n");
        sb.append("这些记忆只提供方向，不替代当前观察；若与当前用户指令或仓库真实状态冲突，以当前事实为准。\n");

        for (MemoryEntry entry : entries) {
            String content = entry.getContent().replace("\n", " ").trim();
            sb.append(String.format("- (%s) [%s/%s] %s: %s\n",
                entry.getId(), entry.getType(), entry.getScope(),
                entry.getDescription(), content.substring(0, Math.min(420, content.length()))));
        }

        String block = sb.toString();
        if (block.length() > maxChars) {
            block = block.substring(0, maxChars - 30) + "\n...（长期记忆已截断）";
        }
        return block;
    }

    /**
     * 是否应该忽略记忆。
     */
    public static boolean shouldIgnoreMemory(String text) {
        String lowered = text.toLowerCase();
        return lowered.contains("ignore memory") ||
               lowered.contains("ignore memories") ||
               lowered.contains("do not use memory") ||
               lowered.contains("don't use memory") ||
               lowered.contains("忽略 memory") ||
               lowered.contains("忽略记忆") ||
               lowered.contains("不要参考 memory") ||
               lowered.contains("不要参考记忆") ||
               lowered.contains("不使用记忆");
    }

    private static class ScoredEntry {
        final int score;
        final MemoryEntry entry;

        ScoredEntry(int score, MemoryEntry entry) {
            this.score = score;
            this.entry = entry;
        }
    }

    private static class FrontmatterResult {
        final Map<String, String> meta;
        final String content;

        FrontmatterResult(Map<String, String> meta, String content) {
            this.meta = meta;
            this.content = content;
        }
    }
}

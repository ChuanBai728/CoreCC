package com.corecc.context;

import com.corecc.llm.LLM;
import com.corecc.llm.LLMResponse;
import com.corecc.tools.Tool;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 多层上下文压缩 —— 管理 128K token 窗口的核心机制。
 *
 * 对应 Python 版的 corecc/context.py。
 *
 * CoreCC 精简为 3 层：
 *   第 1 层（tool_snip）     - 将冗长的工具输出替换为截断版本（零成本）
 *   第 2 层（summarize）     - 用大模型对旧对话生成摘要（有 LLM 成本）
 *   第 3 层（hard_collapse） - 最后手段：仅保留摘要 + 最近的消息
 */
public class ContextManager {
    private static final Path TOOL_RESULT_STORE = Path.of(".corecc", "tool-results");
    private static final int TOOL_RESULT_PERSIST_CHARS = 4_000;
    private static final int TOOL_PREVIEW_LINES = 12;
    private static final int OLD_TOOL_RESULT_CHARS = 1_200;
    private static final int SUMMARY_KEEP_RECENT = 8;
    private static final int HARD_KEEP_RECENT = 4;

    private final int maxTokens;
    private final int snipAt;
    private final int summarizeAt;
    private final int collapseAt;
    private final List<Integer> tokenHistory;
    private int predictedTokens;
    private double predictedPressure;
    private String summaryText;
    private final Map<String, String> readCache;
    private CompressionReport lastReport;
    private final Object lock;

    public ContextManager(int maxTokens) {
        this.maxTokens = maxTokens;
        this.snipAt = (int) (maxTokens * 0.50);      // 50% -> 截断工具输出
        this.summarizeAt = (int) (maxTokens * 0.70);  // 70% -> 大模型摘要
        this.collapseAt = (int) (maxTokens * 0.90);   // 90% -> 硬性折叠
        this.tokenHistory = new ArrayList<>();
        this.predictedTokens = 0;
        this.predictedPressure = 0.0;
        this.summaryText = "";
        this.readCache = new ConcurrentHashMap<>();
        this.lastReport = CompressionReport.empty(0, "");
        this.lock = new Object();
    }

    /**
     * 获取最大 token 数。
     */
    public int getMaxTokens() { return maxTokens; }

    /**
     * 获取最后的压缩报告。
     */
    public CompressionReport getLastReport() { return lastReport; }

    /**
     * 按需执行上下文优化管线，返回压缩报告。
     */
    public CompressionReport maybeCompress(List<Map<String, Object>> messages, LLM llm,
                                           String trigger, boolean force) {
        int before = estimateTokens(messages);
        int current = before;
        List<String> actions = new ArrayList<>();
        List<String> reasons = new ArrayList<>();

        int predicted = predictTokens(current);
        boolean predictive = predicted > (int) (maxTokens * 0.85) && current > (int) (maxTokens * 0.45);
        if (predictive) {
            reasons.add("predictive");
        }

        // 第 1 层：截断冗长的工具输出
        if (force || current > snipAt) {
            if (snipToolOutputs(messages)) {
                actions.add("tool_snip");
                current = estimateTokens(messages);
            }
        }

        // 第 2 层：旧工具结果 microcompact
        if (force || current > snipAt) {
            if (microcompactOldToolOutputs(messages)) {
                actions.add("microcompact_tool");
                current = estimateTokens(messages);
            }
        }

        // 第 3 层：预测式或阈值式摘要压缩
        boolean shouldSummarize = (force || predictive || current > summarizeAt) && messages.size() > 10;
        if (shouldSummarize) {
            if (summarizeOld(messages, llm, SUMMARY_KEEP_RECENT)) {
                actions.add(predictive && current <= summarizeAt ? "predictive_summarize" : "summarize");
                current = estimateTokens(messages);
            }
        }

        // 第 4 层：API 超长或硬阈值时进行最后折叠
        boolean shouldCollapse = ((force && "reactive".equals(trigger)) || current > collapseAt) && messages.size() > 4;
        if (shouldCollapse) {
            hardCollapse(messages, llm);
            actions.add("hard_collapse");
            current = estimateTokens(messages);
        }

        int after = estimateTokens(messages);
        CompressionReport report = new CompressionReport(
            after < before || !actions.isEmpty(),
            before, after,
            Math.max(0, before - after),
            actions,
            actions.isEmpty() ? trigger : String.join(",", reasons) + "," + trigger
        );
        lastReport = report;
        return report;
    }

    /**
     * 优化单个工具结果：读缓存去重、缓存失效、大输出落盘。
     */
    public String optimizeToolResult(String toolName, Map<String, Object> arguments, String result) {
        synchronized (lock) {
            int tokensBefore = approxTokens(result);
            List<String> actions = new ArrayList<>();
            String optimized = result;

            // Invalidate read cache on write/edit
            if ("edit_file".equals(toolName) || "write_file".equals(toolName)) {
                if (invalidateReadCache(arguments)) {
                    actions.add("invalidate_read_cache");
                }
            } else if ("bash".equals(toolName)) {
                if (!readCache.isEmpty()) {
                    readCache.clear();
                    actions.add("clear_read_cache");
                }
            }

            // Dedup read_file results
            if ("read_file".equals(toolName) && !looksLikeError(result)) {
                String key = readCacheKey(arguments);
                String digest = sha256(result);
                if (digest.equals(readCache.get(key))) {
                    optimized = readDedupStub(arguments, result);
                    actions.add("read_dedup");
                } else {
                    readCache.put(key, digest);
                }
            }

            // Persist large results to disk
            if (optimized.length() > TOOL_RESULT_PERSIST_CHARS) {
                optimized = persistToolResult(toolName, arguments, optimized);
                actions.add("persist_tool_result");
            }

            int tokensAfter = approxTokens(optimized);
            CompressionReport report = new CompressionReport(
                !optimized.equals(result) || !actions.isEmpty(),
                tokensBefore, tokensAfter,
                Math.max(0, tokensBefore - tokensAfter),
                actions,
                actions.isEmpty() ? "" : "tool:" + toolName
            );
            lastReport = report;
            return optimized;
        }
    }

    /**
     * 估算消息列表的总 token 数。
     */
    public static int estimateTokens(List<Map<String, Object>> messages) {
        int total = 0;
        for (Map<String, Object> m : messages) {
            Object content = m.get("content");
            if (content instanceof String) {
                total += approxTokens((String) content);
            }
            Object toolCalls = m.get("tool_calls");
            if (toolCalls != null) {
                total += approxTokens(toolCalls.toString());
            }
        }
        return total;
    }

    /**
     * CJK-aware 粗略估算 token 数。
     */
    public static int approxTokens(String text) {
        if (text == null || text.isEmpty()) return 0;

        int cjk = 0;
        int asciiLike = 0;
        int other = 0;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            int code = (int) ch;

            if ((code >= 0x4E00 && code <= 0x9FFF) ||
                (code >= 0x3400 && code <= 0x4DBF) ||
                (code >= 0x3040 && code <= 0x30FF) ||
                (code >= 0xAC00 && code <= 0xD7AF)) {
                cjk++;
            } else if (code < 128) {
                asciiLike++;
            } else {
                other++;
            }
        }

        return Math.max(1, (int) (asciiLike / 4.0 + cjk * 1.2 + other / 2.0));
    }

    /**
     * 第 1 层：将超过 1500 字符的工具结果截断为首尾各几行。
     */
    private boolean snipToolOutputs(List<Map<String, Object>> messages) {
        boolean changed = false;
        for (Map<String, Object> m : messages) {
            if (!"tool".equals(m.get("role"))) continue;

            String content = (String) m.get("content");
            if (content == null || content.length() <= 1500) continue;

            String[] lines = content.split("\\n", -1);
            if (lines.length <= 6) continue;

            // Keep first 3 + last 3 lines
            StringBuilder snipped = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                snipped.append(lines[i]).append("\n");
            }
            snipped.append(String.format("... （共 %d 行，已截断以节省上下文）...\n", lines.length));
            for (int i = lines.length - 3; i < lines.length; i++) {
                snipped.append(lines[i]);
                if (i < lines.length - 1) snipped.append("\n");
            }

            m.put("content", snipped.toString());
            changed = true;
        }
        return changed;
    }

    /**
     * 只压缩旧工具结果，避免破坏刚产生的工具上下文。
     */
    private boolean microcompactOldToolOutputs(List<Map<String, Object>> messages) {
        int keepRecent = 6;
        if (messages.size() <= keepRecent) return false;

        boolean changed = false;
        int cutoff = Math.max(0, messages.size() - keepRecent);

        for (int i = 0; i < cutoff; i++) {
            Map<String, Object> m = messages.get(i);
            if (!"tool".equals(m.get("role"))) continue;

            String content = (String) m.get("content");
            if (content == null || content.length() <= OLD_TOOL_RESULT_CHARS) continue;
            if (content.contains("[旧工具结果已压缩]") || content.contains("[工具输出已保存到磁盘]")) continue;

            String[] lines = content.split("\\n", -1);
            String head = lines.length > 0 ?
                String.join("\n", Arrays.copyOfRange(lines, 0, Math.min(2, lines.length))) :
                content.substring(0, Math.min(300, content.length()));
            String tail = lines.length > 2 ?
                String.join("\n", Arrays.copyOfRange(lines, lines.length - 2, lines.length)) : "";

            StringBuilder compacted = new StringBuilder();
            compacted.append(String.format("[旧工具结果已压缩：原始 %d 字符]\n", content.length()));
            compacted.append("[开头]\n").append(head);
            if (!tail.isEmpty()) {
                compacted.append("\n[结尾]\n").append(tail);
            }

            m.put("content", compacted.toString());
            changed = true;
        }
        return changed;
    }

    /**
     * 第 2 层：对旧对话进行摘要。
     */
    private boolean summarizeOld(List<Map<String, Object>> messages, LLM llm, int keepRecent) {
        if (messages.size() <= keepRecent) return false;

        int splitAt = safeTailStart(messages, keepRecent);
        if (splitAt <= 0) return false;

        List<Map<String, Object>> old = new ArrayList<>(messages.subList(0, splitAt));
        List<Map<String, Object>> tail = new ArrayList<>(messages.subList(splitAt, messages.size()));

        String summary = getSummary(old, llm);
        this.summaryText = summary;

        messages.clear();
        messages.add(Map.of("role", "user", "content", "[上下文已压缩 - 对话摘要]\n" + summary));
        messages.add(Map.of("role", "assistant", "content", "收到，我已获取之前对话的上下文。"));
        messages.addAll(tail);
        return true;
    }

    /**
     * 第 3 层：紧急压缩。
     */
    private void hardCollapse(List<Map<String, Object>> messages, LLM llm) {
        int keepRecent = Math.min(HARD_KEEP_RECENT, messages.size() > HARD_KEEP_RECENT ? HARD_KEEP_RECENT : 2);
        int splitAt = safeTailStart(messages, keepRecent);
        if (splitAt <= 0) {
            splitAt = Math.max(0, messages.size() - keepRecent);
        }

        List<Map<String, Object>> tail = new ArrayList<>(messages.subList(splitAt, messages.size()));
        String summary = getSummary(new ArrayList<>(messages.subList(0, splitAt)), llm);
        this.summaryText = summary;

        messages.clear();
        messages.add(Map.of("role", "user", "content", "[硬性上下文重置]\n" + summary));
        messages.add(Map.of("role", "assistant", "content", "上下文已恢复，继续之前的工作。"));
        messages.addAll(tail);
    }

    /**
     * 通过大模型生成摘要。
     */
    private String getSummary(List<Map<String, Object>> messages, LLM llm) {
        String flat = flatten(messages);

        if (llm != null) {
            try {
                List<Map<String, Object>> summaryMessages = List.of(
                    Map.of("role", "system", "content",
                        "将这段对话压缩为简短摘要。保留：编辑过的文件路径、关键决策、遇到的错误、当前任务状态。删除：冗长的命令输出、代码列表、重复的来回对话。"),
                    Map.of("role", "user", "content", flat.substring(0, Math.min(15000, flat.length())))
                );
                LLMResponse resp = llm.chat(summaryMessages, null, null);
                return resp.getContent();
            } catch (Exception e) {
                // Fall through to rule-based extraction
            }
        }

        // Fallback: rule-based extraction
        return extractKeyInfo(messages);
    }

    /**
     * 根据最近增长速度预测两轮后的上下文压力。
     */
    private int predictTokens(int current) {
        tokenHistory.add(current);
        if (tokenHistory.size() > 6) {
            tokenHistory.remove(0);
        }

        if (tokenHistory.size() < 2) {
            predictedTokens = current;
        } else {
            List<Integer> deltas = new ArrayList<>();
            for (int i = 1; i < tokenHistory.size(); i++) {
                int delta = tokenHistory.get(i) - tokenHistory.get(i - 1);
                if (delta > 0) deltas.add(delta);
            }

            double avgDelta = 0;
            if (!deltas.isEmpty()) {
                int start = Math.max(0, deltas.size() - 3);
                avgDelta = deltas.subList(start, deltas.size()).stream()
                    .mapToInt(Integer::intValue).average().orElse(0);
            }
            predictedTokens = (int) (current + avgDelta * 2);
        }

        predictedPressure = maxTokens > 0 ? (predictedTokens / (double) maxTokens * 100) : 0.0;
        return predictedTokens;
    }

    /**
     * 避免摘要边界切断 assistant tool_calls 与 tool result 配对。
     */
    private int safeTailStart(List<Map<String, Object>> messages, int keepRecent) {
        int start = Math.max(0, messages.size() - keepRecent);
        while (start > 0 && "tool".equals(messages.get(start).get("role"))) {
            start--;
        }
        if (start > 0) {
            Map<String, Object> prev = messages.get(start - 1);
            if ("assistant".equals(prev.get("role")) && prev.containsKey("tool_calls")) {
                start--;
            }
        }
        return start;
    }

    /**
     * 将消息列表展平为单个文本字符串。
     */
    private String flatten(List<Map<String, Object>> messages) {
        return messages.stream()
            .map(m -> {
                String role = (String) m.getOrDefault("role", "?");
                String text = (String) m.getOrDefault("content", "");
                if (text != null && !text.isEmpty()) {
                    return String.format("[%s] %s", role, text.substring(0, Math.min(400, text.length())));
                }
                return null;
            })
            .filter(Objects::nonNull)
            .collect(Collectors.joining("\n"));
    }

    /**
     * 回退方案：不用大模型，直接提取文件路径、错误和决策信息。
     */
    private String extractKeyInfo(List<Map<String, Object>> messages) {
        Set<String> filesSeen = new TreeSet<>();
        List<String> errors = new ArrayList<>();

        for (Map<String, Object> m : messages) {
            String text = (String) m.getOrDefault("content", "");
            if (text == null) continue;

            // Extract file paths
            for (String word : text.split("[\\s'\"]+")) {
                if (word.matches("[\\w./\\-]+\\.\\w{1,5}")) {
                    filesSeen.add(word);
                }
            }

            // Extract error lines
            for (String line : text.split("\\n")) {
                if (line.toLowerCase().contains("error") || line.contains("Error")) {
                    errors.add(line.trim().substring(0, Math.min(150, line.trim().length())));
                }
            }
        }

        StringBuilder parts = new StringBuilder();
        if (!filesSeen.isEmpty()) {
            parts.append("涉及的文件：").append(String.join(", ",
                filesSeen.stream().limit(20).collect(Collectors.toList())));
        }
        if (!errors.isEmpty()) {
            if (parts.length() > 0) parts.append("\n");
            parts.append("遇到的错误：").append(String.join("; ",
                errors.stream().limit(5).collect(Collectors.toList())));
        }

        return parts.length() > 0 ? parts.toString() : "（无可提取的上下文）";
    }

    private boolean looksLikeError(String text) {
        String lowered = text.toLowerCase();
        return text.startsWith("错误：") || lowered.startsWith("error:") || text.contains("运行时提示");
    }

    private String readCacheKey(Map<String, Object> arguments) {
        String filePath = String.valueOf(arguments.getOrDefault("file_path", ""));
        int offset = arguments.containsKey("offset") ? ((Number) arguments.get("offset")).intValue() : 1;
        int limit = arguments.containsKey("limit") ? ((Number) arguments.get("limit")).intValue() : 2000;
        return String.format("{\"file_path\":\"%s\",\"limit\":%d,\"offset\":%d}", filePath, limit, offset);
    }

    private boolean invalidateReadCache(Map<String, Object> arguments) {
        String rawPath = (String) arguments.get("file_path");
        if (rawPath == null) return false;

        int before = readCache.size();
        readCache.entrySet().removeIf(entry -> {
            try {
                String cachedPath = entry.getKey().split("\"file_path\":\"")[1].split("\"")[0];
                return cachedPath.equals(rawPath);
            } catch (Exception e) {
                return false;
            }
        });
        return readCache.size() != before;
    }

    private String readDedupStub(Map<String, Object> arguments, String result) {
        String filePath = String.valueOf(arguments.getOrDefault("file_path", ""));
        int lineCount = result.split("\\n").length;
        String digest = sha256(result).substring(0, 12);
        return String.format("[read_file 去重]\n文件：%s\n内容未变化，省略重复正文（上次读取 %d 行，sha256=%s）。",
            filePath, lineCount, digest);
    }

    private String persistToolResult(String toolName, Map<String, Object> arguments, String result) {
        try {
            java.nio.file.Files.createDirectories(TOOL_RESULT_STORE);
            String digest = sha256(result).substring(0, 12);
            String timestamp = java.time.Instant.now().toString().replace(":", "-").replace(".", "-");
            String safeTool = toolName.replaceAll("[^A-Za-z0-9_.-]+", "-");
            if (safeTool.length() > 40) safeTool = safeTool.substring(0, 40);
            if (safeTool.isEmpty()) safeTool = "tool";

            Path path = TOOL_RESULT_STORE.resolve(String.format("%s-%s-%s.txt", timestamp, safeTool, digest));
            java.nio.file.Files.writeString(path, result);

            String[] lines = result.split("\\n", -1);
            StringBuilder head = new StringBuilder();
            for (int i = 0; i < Math.min(TOOL_PREVIEW_LINES, lines.length); i++) {
                if (i > 0) head.append("\n");
                head.append(lines[i]);
            }

            StringBuilder tail = new StringBuilder();
            if (lines.length > TOOL_PREVIEW_LINES) {
                for (int i = Math.max(0, lines.length - TOOL_PREVIEW_LINES); i < lines.length; i++) {
                    if (tail.length() > 0) tail.append("\n");
                    tail.append(lines[i]);
                }
            }

            String argsPreview = arguments.toString();
            if (argsPreview.length() > 500) argsPreview = argsPreview.substring(0, 500);

            StringBuilder preview = new StringBuilder();
            preview.append("[工具输出已保存到磁盘]\n");
            preview.append("工具：").append(toolName).append("\n");
            preview.append("参数：").append(argsPreview).append("\n");
            preview.append("原始字符：").append(result.length()).append("\n");
            preview.append("路径：").append(path).append("\n");
            preview.append("[开头]\n").append(head);

            if (tail.length() > 0 && !tail.toString().equals(head.toString())) {
                preview.append("\n[结尾]\n").append(tail);
            }

            return preview.toString();
        } catch (Exception e) {
            return result; // Fall back to original on error
        }
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
}

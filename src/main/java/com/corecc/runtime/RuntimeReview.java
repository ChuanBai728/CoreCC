package com.corecc.runtime;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 运行时工具审查 —— 分类错误、压缩输出、添加恢复建议。
 *
 * 对应 Python 版的 corecc/runtime.py。
 */
public class RuntimeReview {
    /**
     * 错误模式匹配规则。
     */
    private static final List<ErrorPattern> ERROR_PATTERNS = List.of(
        new ErrorPattern("blocked", "已拦截", "blocked"),
        new ErrorPattern("timeout", "超时", "timeout", "timed out"),
        new ErrorPattern("not_found", "不存在", "not found", "no such file"),
        new ErrorPattern("permission", "permission denied", "access is denied", "权限"),
        new ErrorPattern("encoding", "utf-8", "二进制", "decode"),
        new ErrorPattern("too_large", "过大", "too large"),
        new ErrorPattern("invalid_input", "无效", "参数错误", "invalid", "bad arguments"),
        new ErrorPattern("command_failed", "退出码", "traceback", "exception", "failed")
    );

    /**
     * 恢复建议映射。
     */
    public static final Map<String, String> NUDGES = Map.ofEntries(
        Map.entry("blocked", "命令被安全策略拦截。请换成更具体、更小范围的命令，或先读取目标路径确认影响面。"),
        Map.entry("timeout", "操作超时。请缩小范围、增加过滤条件，或用更短的命令先确认问题位置。"),
        Map.entry("not_found", "目标不存在或定位不准。请先用 glob/grep/read_file 重新确认路径和上下文。"),
        Map.entry("permission", "权限不足。请改用当前用户可访问的路径，或先检查文件/目录权限。"),
        Map.entry("encoding", "目标不像 UTF-8 文本。请避免直接编辑二进制文件，必要时先用专用工具检查。"),
        Map.entry("too_large", "目标过大。请改用 offset/limit、grep include 或更具体的文件范围。"),
        Map.entry("invalid_input", "工具参数不合法。请重新读取相关文件或 schema，并用更精确的参数重试。"),
        Map.entry("command_failed", "命令执行失败。请先查看退出码和错误行，再运行更小的验证命令定位原因。"),
        Map.entry("unknown", "工具返回了错误。请读取最新上下文，缩小操作范围后重试。")
    );

    /**
     * 重要行匹配模式。
     */
    private static final Pattern IMPORTANT_LINE_RE = Pattern.compile(
        "(error|failed|failure|exception|traceback|warning|denied|timeout|not found|" +
        "错误|失败|异常|警告|拒绝|超时|不存在|未找到)",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * 分类错误，返回错误类别或 null。
     */
    public static String classifyError(String text) {
        String lowered = text.toLowerCase();
        for (ErrorPattern ep : ERROR_PATTERNS) {
            for (String needle : ep.needles) {
                if (lowered.contains(needle.toLowerCase())) {
                    return ep.category;
                }
            }
        }
        if (lowered.startsWith("error") || text.startsWith("错误：")) {
            return "unknown";
        }
        return null;
    }

    /**
     * 分类、压缩并注释工具结果。
     */
    public static ToolReview reviewToolResult(String toolName, String result) {
        String category = classifyError(result);
        CompactedOutput compacted = compactToolOutput(toolName, result);

        if (category == null) {
            return new ToolReview(compacted.text, false, null, null, compacted.savedChars);
        }

        String nudge = NUDGES.getOrDefault(category, NUDGES.get("unknown"));
        String annotated = compacted.text + "\n\n[运行时提示]\n错误类别：" + category + "\n恢复建议：" + nudge;

        return new ToolReview(annotated, true, category, nudge, compacted.savedChars);
    }

    /**
     * 智能压缩工具输出，保留有用错误和边界上下文。
     */
    private static CompactedOutput compactToolOutput(String toolName, String text, int maxChars) {
        if (text.length() <= maxChars) {
            return new CompactedOutput(text, 0);
        }

        String[] lines = text.split("\\n", -1);
        List<String> important = new ArrayList<>();
        for (String line : lines) {
            if (IMPORTANT_LINE_RE.matcher(line).find()) {
                important.add(line);
            }
        }

        List<String> head = Arrays.asList(Arrays.copyOfRange(lines, 0, Math.min(40, lines.length)));
        List<String> tail = lines.length > 30 ?
            Arrays.asList(Arrays.copyOfRange(lines, lines.length - 30, lines.length)) :
            Collections.emptyList();

        StringBuilder parts = new StringBuilder();
        parts.append(String.format("[工具输出已智能压缩：%s 原始 %d 字符]\n", toolName, text.length()));

        if (!important.isEmpty()) {
            parts.append("[关键行]\n");
            for (int i = 0; i < Math.min(40, important.size()); i++) {
                parts.append(important.get(i)).append("\n");
            }
        }

        parts.append("[开头]\n");
        for (String line : head) {
            parts.append(line).append("\n");
        }

        if (!tail.isEmpty()) {
            parts.append("[结尾]\n");
            for (String line : tail) {
                parts.append(line).append("\n");
            }
        }

        String compacted = parts.toString().trim();
        if (compacted.length() > maxChars) {
            compacted = compacted.substring(0, maxChars - 80) + "\n...（智能压缩结果仍过长，已再次截断）";
        }

        return new CompactedOutput(compacted, Math.max(0, text.length() - compacted.length()));
    }

    /**
     * 使用默认最大字符数压缩。
     */
    private static CompactedOutput compactToolOutput(String toolName, String text) {
        return compactToolOutput(toolName, text, 12_000);
    }

    private static class ErrorPattern {
        final String category;
        final String[] needles;

        ErrorPattern(String category, String... needles) {
            this.category = category;
            this.needles = needles;
        }
    }

    private static class CompactedOutput {
        final String text;
        final int savedChars;

        CompactedOutput(String text, int savedChars) {
            this.text = text;
            this.savedChars = savedChars;
        }
    }
}

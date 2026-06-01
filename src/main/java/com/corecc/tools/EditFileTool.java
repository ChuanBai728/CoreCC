package com.corecc.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 搜索替换式文件编辑 —— Claude Code 的核心创新。
 *
 * 对应 Python 版的 corecc/tools/edit.py。
 *
 * 为什么不用其他编辑方式？Claude Code 尝试过三种失败方案：
 * 1. 行号补丁：大模型行号准确率低，并发编辑会偏移所有行号
 * 2. 全文件重写：token 成本高，大模型复制长文本时频繁损坏格式
 * 3. 统一 diff 格式：大模型生成 diff 格式的正确率低
 *
 * 解决方案：搜索替换（search-replace）
 * 大模型提供 old_string（必须在文件中唯一）和 new_string
 */
public class EditFileTool implements Tool {
    // 跟踪本次会话修改过的文件（用于 /diff 命令）
    public static final Set<String> changedFiles = ConcurrentHashMap.newKeySet();
    private static final int MAX_EDIT_BYTES = 1_000_000;

    @Override
    public String getName() { return "edit_file"; }

    @Override
    public boolean isReadOnly() { return false; }

    @Override
    public String getDescription() {
        return "Edit a file by replacing an exact string match. " +
               "old_string must appear exactly once in the file for safety. " +
               "Include enough surrounding context to ensure uniqueness.";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "file_path", Map.of(
                    "type", "string",
                    "description", "要编辑的文件路径"
                ),
                "old_string", Map.of(
                    "type", "string",
                    "description", "要查找的精确文本（在文件中必须唯一）"
                ),
                "new_string", Map.of(
                    "type", "string",
                    "description", "替换后的文本"
                )
            ),
            "required", List.of("file_path", "old_string", "new_string")
        );
    }

    @Override
    public String execute(Map<String, Object> args) {
        String filePath = (String) args.get("file_path");
        String oldString = (String) args.get("old_string");
        String newString = (String) args.get("new_string");

        try {
            Path p = Paths.get(filePath).toAbsolutePath().normalize();

            if (!Files.exists(p)) {
                return "错误：" + filePath + " 不存在";
            }

            long size = Files.size(p);
            if (size > MAX_EDIT_BYTES) {
                return String.format("错误：%s 过大（%d bytes），请先缩小编辑范围。", filePath, size);
            }

            String content;
            try {
                content = Files.readString(p);
            } catch (IOException e) {
                if (e.getMessage().contains("UTF-8") || e.getMessage().contains("decode")) {
                    return "错误：" + filePath + " 不是 UTF-8 文本文件或包含二进制内容";
                }
                return "错误：" + e.getMessage();
            }

            // Count occurrences
            int occurrences = countOccurrences(content, oldString);

            if (occurrences == 0) {
                String preview = content.length() > 500 ?
                    content.substring(0, 500) + "..." : content;
                return String.format("错误：在 %s 中未找到 old_string。\n文件开头如下：\n%s", filePath, preview);
            }

            if (occurrences > 1) {
                return String.format("错误：old_string 在 %s 中出现了 %d 次。请添加更多上下文以确保唯一匹配。",
                    filePath, occurrences);
            }

            // Replace first occurrence
            String newContent = content.replace(oldString, newString);
            Files.writeString(p, newContent);
            changedFiles.add(p.toString());

            // Generate unified diff
            String diff = unifiedDiff(content, newContent, filePath);
            return String.format("已编辑 %s\n%s", filePath, diff);
        } catch (Exception e) {
            return "错误：" + e.getMessage();
        }
    }

    /**
     * Count occurrences of substring in string.
     */
    private int countOccurrences(String text, String search) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(search, idx)) != -1) {
            count++;
            idx += search.length();
        }
        return count;
    }

    /**
     * Generate unified diff between old and new content.
     */
    private String unifiedDiff(String oldContent, String newContent, String filename) {
        String[] oldLines = oldContent.split("\\n", -1);
        String[] newLines = newContent.split("\\n", -1);

        StringBuilder diff = new StringBuilder();
        diff.append(String.format("--- a/%s\n", filename));
        diff.append(String.format("+++ b/%s\n", filename));

        // Simple diff: find changed region
        int start = 0;
        while (start < oldLines.length && start < newLines.length &&
               oldLines[start].equals(newLines[start])) {
            start++;
        }

        int oldEnd = oldLines.length - 1;
        int newEnd = newLines.length - 1;
        while (oldEnd >= start && newEnd >= start &&
               oldLines[oldEnd].equals(newLines[newEnd])) {
            oldEnd--;
            newEnd--;
        }

        // Context
        int contextStart = Math.max(0, start - 3);
        int contextEnd = Math.min(oldLines.length, oldEnd + 4);

        diff.append(String.format("@@ -%d,%d +%d,%d @@\n",
            contextStart + 1, contextEnd - contextStart,
            contextStart + 1, contextEnd - contextStart + (newEnd - start)));

        // Old lines
        for (int i = contextStart; i <= Math.min(oldEnd, contextEnd - 1); i++) {
            diff.append("-").append(oldLines[i]).append("\n");
        }

        // New lines
        for (int i = contextStart; i <= Math.min(newEnd, contextEnd - 1); i++) {
            diff.append("+").append(newLines[i]).append("\n");
        }

        String result = diff.toString();
        // Truncate large diffs
        if (result.length() > 3000) {
            result = result.substring(0, 2500) + "\n... （差异已截断）\n";
        }
        return result;
    }
}

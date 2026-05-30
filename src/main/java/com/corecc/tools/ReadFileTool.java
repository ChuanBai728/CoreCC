package com.corecc.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 带行号的文件读取工具。
 *
 * 对应 Python 版的 corecc/tools/read.py。
 */
public class ReadFileTool implements Tool {
    private static final int MAX_READ_BYTES = 1_000_000;

    @Override
    public String getName() { return "read_file"; }

    @Override
    public boolean isReadOnly() { return true; }

    @Override
    public String getDescription() {
        return "Read a file's contents with line numbers. Always read a file before editing it.";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "file_path", Map.of(
                    "type", "string",
                    "description", "文件路径"
                ),
                "offset", Map.of(
                    "type", "integer",
                    "description", "起始行号（从 1 开始），默认 1"
                ),
                "limit", Map.of(
                    "type", "integer",
                    "description", "最大读取行数，默认 2000"
                )
            ),
            "required", List.of("file_path")
        );
    }

    @Override
    public String execute(Map<String, Object> args) {
        String filePath = (String) args.get("file_path");
        int offset = args.containsKey("offset") ? ((Number) args.get("offset")).intValue() : 1;
        int limit = args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : 2000;

        try {
            Path p = Paths.get(filePath).toAbsolutePath().normalize();

            if (!Files.exists(p)) {
                return "错误：" + filePath + " 不存在";
            }
            if (!Files.isRegularFile(p)) {
                return "错误：" + filePath + " 是目录，不是文件";
            }

            long size = Files.size(p);
            if (size > MAX_READ_BYTES) {
                return String.format("错误：%s 过大（%d bytes），请先使用更具体的范围或命令查看片段。", filePath, size);
            }

            String text;
            try {
                text = Files.readString(p);
            } catch (IOException e) {
                if (e.getMessage().contains("UTF-8") || e.getMessage().contains("decode")) {
                    return "错误：" + filePath + " 不是 UTF-8 文本文件或包含二进制内容";
                }
                return "错误：" + e.getMessage();
            }

            String[] lines = text.split("\\n", -1);
            int total = lines.length;

            int start = Math.max(0, offset - 1);
            int end = Math.min(start + limit, total);

            StringBuilder result = new StringBuilder();
            for (int i = start; i < end; i++) {
                result.append(i + 1).append("\t").append(lines[i]).append("\n");
            }

            if (result.length() > 0 && result.charAt(result.length() - 1) == '\n') {
                result.setLength(result.length() - 1);
            }

            if (total > end) {
                result.append(String.format("\n... （共 %d 行，显示第 %d-%d 行）", total, start + 1, end));
            }

            return result.length() > 0 ? result.toString() : "（空文件）";
        } catch (Exception e) {
            return "错误：" + e.getMessage();
        }
    }
}

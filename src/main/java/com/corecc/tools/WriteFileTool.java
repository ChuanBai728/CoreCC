package com.corecc.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件创建/覆盖工具。
 *
 * 对应 Python 版的 corecc/tools/write.py。
 */
public class WriteFileTool implements Tool {
    // 跟踪本次会话修改过的文件（用于 /diff 命令）
    public static final Set<String> changedFiles = ConcurrentHashMap.newKeySet();

    @Override
    public String getName() { return "write_file"; }

    @Override
    public boolean isReadOnly() { return false; }

    @Override
    public String getDescription() {
        return "Create a new file or completely overwrite an existing one. " +
               "For small edits to existing files, prefer edit_file instead.";
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
                "content", Map.of(
                    "type", "string",
                    "description", "要写入的完整文件内容"
                )
            ),
            "required", List.of("file_path", "content")
        );
    }

    @Override
    public String execute(Map<String, Object> args) {
        String filePath = (String) args.get("file_path");
        String content = (String) args.get("content");

        try {
            Path p = Paths.get(filePath).toAbsolutePath().normalize();

            // Create parent directories if needed
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }

            Files.writeString(p, content);
            changedFiles.add(p.toString());

            // Count lines
            long lineCount = content.chars().filter(c -> c == '\n').count() +
                (content.isEmpty() || content.endsWith("\n") ? 0 : 1);

            return String.format("已写入 %d 行到 %s", lineCount, filePath);
        } catch (Exception e) {
            return "错误：" + e.getMessage();
        }
    }
}

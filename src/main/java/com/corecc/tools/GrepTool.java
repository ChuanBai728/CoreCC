package com.corecc.tools;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * 支持正则表达式的内容搜索工具。
 *
 * 对应 Python 版的 corecc/tools/grep.py。
 * 对应 Claude Code 的 GrepTool，Claude Code 使用 ripgrep（Rust 编写）进行高效搜索。
 * CoreCC 简化为 Java NIO + regex。
 */
public class GrepTool implements Tool {
    private static final Set<String> SKIP_DIRS = Set.of(
        ".git", "node_modules", "__pycache__", ".venv", "venv", ".tox", "dist", "build"
    );
    private static final int MAX_GREP_FILE_BYTES = 1_000_000;
    private static final int MAX_MATCHES = 200;
    private static final int MAX_FILES = 5000;

    @Override
    public String getName() { return "grep"; }

    @Override
    public boolean isReadOnly() { return true; }

    @Override
    public String getDescription() {
        return "Search file contents with regex. " +
               "Returns matching lines with file path and line number.";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "pattern", Map.of(
                    "type", "string",
                    "description", "要搜索的正则表达式模式"
                ),
                "path", Map.of(
                    "type", "string",
                    "description", "搜索的文件或目录（默认：当前目录）"
                ),
                "include", Map.of(
                    "type", "string",
                    "description", "仅搜索匹配此模式的文件（如 '*.py'）"
                )
            ),
            "required", List.of("pattern")
        );
    }

    @Override
    public String execute(Map<String, Object> args) {
        String pattern = (String) args.get("pattern");
        String path = args.containsKey("path") ? (String) args.get("path") : ".";
        String include = args.containsKey("include") ? (String) args.get("include") : null;

        // Compile regex
        Pattern regex;
        try {
            regex = Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            return "无效的正则表达式：" + e.getMessage();
        }

        Path base = Paths.get(path).toAbsolutePath().normalize();
        if (!Files.exists(base)) {
            return "错误：" + path + " 不存在";
        }

        List<Path> files;
        boolean singleFile;
        if (Files.isRegularFile(base)) {
            files = List.of(base);
            singleFile = true;
        } else {
            files = walkDirectory(base, include);
            singleFile = false;
        }

        List<String> matches = new ArrayList<>();
        int skippedLarge = 0;
        int skippedBinary = 0;

        for (Path fp : files) {
            try {
                long size = Files.size(fp);
                if (size > MAX_GREP_FILE_BYTES) {
                    if (singleFile) {
                        return String.format("错误：%s 过大（%d bytes），请缩小搜索范围或使用更具体的命令。", path, size);
                    }
                    skippedLarge++;
                    continue;
                }

                String text;
                try {
                    text = Files.readString(fp);
                } catch (IOException e) {
                    if (singleFile) {
                        return "错误：" + path + " 不是 UTF-8 文本文件或包含二进制内容";
                    }
                    skippedBinary++;
                    continue;
                }

                String[] lines = text.split("\\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    if (regex.matcher(lines[i]).find()) {
                        matches.add(String.format("%s:%d: %s", fp, i + 1, lines[i].trim()));
                        if (matches.size() >= MAX_MATCHES) {
                            matches.add("... （已达到 200 条匹配上限）");
                            return String.join("\n", matches);
                        }
                    }
                }
            } catch (IOException e) {
                // Skip files we can't read
                continue;
            }
        }

        StringBuilder result = new StringBuilder();
        if (!matches.isEmpty()) {
            result.append(String.join("\n", matches));
        } else {
            result.append("未找到匹配项。");
        }

        if (skippedLarge > 0 || skippedBinary > 0) {
            result.append(String.format("\n（已跳过 %d 个过大文件，%d 个非 UTF-8/二进制文件）",
                skippedLarge, skippedBinary));
        }

        return result.toString();
    }

    /**
     * 遍历目录树，跳过不需要的目录。
     */
    private List<Path> walkDirectory(Path root, String include) {
        List<Path> results = new ArrayList<>();

        try {
            String globPattern = include != null ? include : "**";
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + globPattern);

            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName().toString();
                    if (SKIP_DIRS.contains(dirName) || dirName.startsWith(".")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (results.size() >= MAX_FILES) {
                        return FileVisitResult.TERMINATE;
                    }

                    // If include is specified, check if file matches
                    if (include != null) {
                        Path relativePath = root.relativize(file);
                        if (!matcher.matches(relativePath) && !matcher.matches(file.getFileName())) {
                            return FileVisitResult.CONTINUE;
                        }
                    }

                    if (Files.isRegularFile(file)) {
                        results.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // Return whatever we have
        }

        return results;
    }
}

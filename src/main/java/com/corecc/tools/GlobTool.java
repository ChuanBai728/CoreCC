package com.corecc.tools;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 文件模式匹配工具 —— 通过 glob 模式查找文件。
 *
 * 对应 Python 版的 corecc/tools/glob_tool.py。
 * 对应 Claude Code 的 GlobTool，Claude Code 使用 ripgrep（Rust）进行高效文件搜索。
 * CoreCC 简化为 Java NIO glob。
 */
public class GlobTool implements Tool {
    private static final int MAX_RESULTS = 100;

    @Override
    public String getName() { return "glob"; }

    @Override
    public boolean isReadOnly() { return true; }

    @Override
    public String getDescription() {
        return "Find files matching a glob pattern. " +
               "Supports ** for recursive matching (e.g. '**/*.py').";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "pattern", Map.of(
                    "type", "string",
                    "description", "Glob 模式，如 '**/*.py' 或 'src/**/*.ts'"
                ),
                "path", Map.of(
                    "type", "string",
                    "description", "搜索目录（默认：当前目录）"
                )
            ),
            "required", List.of("pattern")
        );
    }

    @Override
    public String execute(Map<String, Object> args) {
        String pattern = (String) args.get("pattern");
        String path = args.containsKey("path") ? (String) args.get("path") : ".";

        try {
            Path base = Paths.get(path).toAbsolutePath().normalize();

            if (!Files.isDirectory(base)) {
                return "错误：" + path + " 不是目录";
            }

            // Use Java NIO glob matching
            List<Path> hits = new ArrayList<>();

            // Convert glob pattern to work with Java NIO
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);

            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName().toString();
                    // Skip hidden and common ignore directories
                    if (dirName.startsWith(".") || dirName.equals("node_modules") ||
                        dirName.equals("__pycache__") || dirName.equals("venv") ||
                        dirName.equals(".venv") || dirName.equals("dist") ||
                        dirName.equals("build") || dirName.equals("target")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (hits.size() >= MAX_RESULTS * 2) {
                        return FileVisitResult.TERMINATE;
                    }

                    // Check if file matches the glob pattern
                    Path relativePath = base.relativize(file);
                    if (matcher.matches(relativePath) || matcher.matches(file.getFileName())) {
                        hits.add(file);
                    }

                    return FileVisitResult.CONTINUE;
                }
            });

            // Sort by modification time (newest first)
            hits.sort((a, b) -> {
                try {
                    long timeA = Files.getLastModifiedTime(a).toMillis();
                    long timeB = Files.getLastModifiedTime(b).toMillis();
                    return Long.compare(timeB, timeA);
                } catch (IOException e) {
                    return 0;
                }
            });

            int total = hits.size();
            List<Path> shown = hits.subList(0, Math.min(MAX_RESULTS, total));

            StringBuilder result = new StringBuilder();
            for (Path p : shown) {
                result.append(p.toString()).append("\n");
            }

            if (result.length() > 0 && result.charAt(result.length() - 1) == '\n') {
                result.setLength(result.length() - 1);
            }

            if (total > MAX_RESULTS) {
                result.append(String.format("\n... （共 %d 个匹配，显示前 %d 个）", total, MAX_RESULTS));
            }

            return result.length() > 0 ? result.toString() : "未找到匹配的文件。";
        } catch (Exception e) {
            return "错误：" + e.getMessage();
        }
    }
}

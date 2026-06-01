package com.corecc.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Map;

public class WriteBytesBase64Tool implements Tool {
    private static final int MAX_DECODED_BYTES = 20_000_000;

    @Override
    public String getName() { return "write_bytes_base64"; }

    @Override
    public boolean isReadOnly() { return false; }

    @Override
    public String getDescription() {
        return "Write binary file contents from a base64 string. Use for compressed files, images, archives, model artifacts, or any non-UTF-8 output.";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "file_path", Map.of(
                    "type", "string",
                    "description", "Path to write"
                ),
                "base64_content", Map.of(
                    "type", "string",
                    "description", "Base64-encoded bytes to write"
                )
            ),
            "required", List.of("file_path", "base64_content")
        );
    }

    @Override
    public String execute(Map<String, Object> args) {
        String filePath = (String) args.get("file_path");
        String base64Content = (String) args.get("base64_content");

        try {
            byte[] bytes = Base64.getMimeDecoder().decode(base64Content);
            if (bytes.length > MAX_DECODED_BYTES) {
                return String.format("错误：解码后文件过大（%d bytes），上限为 %d bytes", bytes.length, MAX_DECODED_BYTES);
            }

            Path p = Paths.get(filePath).toAbsolutePath().normalize();
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }

            Files.write(p, bytes);
            WriteFileTool.changedFiles.add(p.toString());
            return String.format("已写入 %d bytes 到 %s", bytes.length, filePath);
        } catch (IllegalArgumentException e) {
            return "错误：base64_content 不是有效的 base64 数据";
        } catch (Exception e) {
            return "错误：" + e.getMessage();
        }
    }
}

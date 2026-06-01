package com.corecc.capabilities;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Optional dynamic capabilities loaded from environment variables.
 */
public class CapabilityConfig {
    private final List<Path> skillPaths;
    private final Path mcpConfigPath;
    private final int mcpTimeoutSec;

    public CapabilityConfig(List<Path> skillPaths, Path mcpConfigPath, int mcpTimeoutSec) {
        this.skillPaths = skillPaths != null ? List.copyOf(skillPaths) : List.of();
        this.mcpConfigPath = mcpConfigPath;
        this.mcpTimeoutSec = mcpTimeoutSec;
    }

    public static CapabilityConfig fromEnv() {
        String skills = System.getenv("CORECC_SKILLS");
        String mcpConfig = System.getenv("CORECC_MCP_CONFIG");
        int timeoutSec = parseInt(System.getenv("CORECC_MCP_TIMEOUT_SEC"), 30);

        Path configPath = (mcpConfig == null || mcpConfig.isBlank()) ? null : Path.of(mcpConfig.trim());
        return new CapabilityConfig(parsePathList(skills), configPath, timeoutSec);
    }

    public boolean hasSkills() {
        return !skillPaths.isEmpty();
    }

    public boolean hasMcpConfig() {
        return mcpConfigPath != null;
    }

    public List<Path> getSkillPaths() {
        return Collections.unmodifiableList(skillPaths);
    }

    public Path getMcpConfigPath() {
        return mcpConfigPath;
    }

    public int getMcpTimeoutSec() {
        return mcpTimeoutSec;
    }

    private static List<Path> parsePathList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        String separator = raw.contains(";") ? ";" : File.pathSeparator;
        String[] parts = raw.split(java.util.regex.Pattern.quote(separator));
        List<Path> paths = new ArrayList<>();
        for (String part : parts) {
            String value = part.trim();
            if (!value.isEmpty()) {
                paths.add(Path.of(value));
            }
        }
        return paths;
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(1, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}

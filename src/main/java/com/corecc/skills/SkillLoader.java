package com.corecc.skills;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Loads local skills from directories containing SKILL.md.
 */
public class SkillLoader {
    private static final Pattern FRONT_MATTER_LINE =
        Pattern.compile("^([A-Za-z0-9_.-]+)\\s*:\\s*\"?(.*?)\"?\\s*$");
    private static final Pattern HEADING = Pattern.compile("^#\\s+(.+)$", Pattern.MULTILINE);

    public static List<Skill> load(List<Path> configuredPaths) {
        if (configuredPaths == null || configuredPaths.isEmpty()) {
            return List.of();
        }

        Map<String, Skill> skills = new LinkedHashMap<>();
        for (Path configuredPath : configuredPaths) {
            for (Path skillDir : discoverSkillDirs(configuredPath)) {
                try {
                    Skill skill = loadOne(skillDir);
                    skills.putIfAbsent(skill.getName(), skill);
                } catch (IOException ignored) {
                    // Invalid skill directories are skipped; callers surface only successful skills.
                }
            }
        }
        return new ArrayList<>(skills.values());
    }

    public static Skill loadOne(Path directory) throws IOException {
        Path skillPath = directory.resolve("SKILL.md");
        String markdown = Files.readString(skillPath, StandardCharsets.UTF_8);
        Map<String, String> frontMatter = parseFrontMatter(markdown);

        String fallbackName = directory.getFileName() != null
            ? directory.getFileName().toString()
            : directory.toString();
        String name = cleanName(frontMatter.getOrDefault("name", fallbackName));
        String description = frontMatter.get("description");
        if (description == null || description.isBlank()) {
            description = firstHeading(markdown);
        }
        if (description == null || description.isBlank()) {
            description = "Local skill from " + directory.toAbsolutePath().normalize();
        }

        return new Skill(name, description.trim(),
            directory.toAbsolutePath().normalize(),
            skillPath.toAbsolutePath().normalize(),
            markdown);
    }

    private static List<Path> discoverSkillDirs(Path configuredPath) {
        Path path = configuredPath.toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            return List.of();
        }
        if (Files.isRegularFile(path.resolve("SKILL.md"))) {
            return List.of(path);
        }

        try (Stream<Path> children = Files.list(path)) {
            return children
                .filter(Files::isDirectory)
                .filter(child -> Files.isRegularFile(child.resolve("SKILL.md")))
                .sorted(Comparator.comparing(Path::toString))
                .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static Map<String, String> parseFrontMatter(String markdown) {
        Map<String, String> result = new LinkedHashMap<>();
        String[] lines = markdown.split("\\R", -1);
        if (lines.length == 0 || !lines[0].trim().equals("---")) {
            return result;
        }

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.equals("---")) {
                break;
            }
            Matcher matcher = FRONT_MATTER_LINE.matcher(line);
            if (matcher.matches()) {
                result.put(matcher.group(1), matcher.group(2));
            }
        }
        return result;
    }

    private static String firstHeading(String markdown) {
        Matcher matcher = HEADING.matcher(markdown);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    private static String cleanName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            return "skill";
        }
        return name.replaceAll("[^A-Za-z0-9_.-]", "-");
    }
}

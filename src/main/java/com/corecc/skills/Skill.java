package com.corecc.skills;

import java.nio.file.Path;

/**
 * A local skill backed by a SKILL.md file.
 */
public class Skill {
    private final String name;
    private final String description;
    private final Path directory;
    private final Path markdownPath;
    private final String markdown;

    public Skill(String name, String description, Path directory, Path markdownPath, String markdown) {
        this.name = name;
        this.description = description;
        this.directory = directory;
        this.markdownPath = markdownPath;
        this.markdown = markdown;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Path getDirectory() {
        return directory;
    }

    public Path getMarkdownPath() {
        return markdownPath;
    }

    public String getMarkdown() {
        return markdown;
    }
}

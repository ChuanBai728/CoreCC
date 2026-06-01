package com.corecc.skills;

import com.corecc.tools.Tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Read-only tool for listing and reading configured local skills.
 */
public class SkillTool implements Tool {
    private final Map<String, Skill> skillsByName;

    public SkillTool(List<Skill> skills) {
        this.skillsByName = new LinkedHashMap<>();
        if (skills != null) {
            for (Skill skill : skills) {
                this.skillsByName.putIfAbsent(skill.getName(), skill);
            }
        }
    }

    @Override
    public String getName() {
        return "skill";
    }

    @Override
    public String getDescription() {
        return "List or read configured local skills from SKILL.md files. " +
               "Use this before applying a skill-specific workflow.";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "action", Map.of(
                    "type", "string",
                    "enum", List.of("list", "read"),
                    "description", "Use list to show skill names, or read to return one SKILL.md"
                ),
                "name", Map.of(
                    "type", "string",
                    "description", "Skill name to read when action is read"
                )
            ),
            "required", List.of("action")
        );
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String execute(Map<String, Object> args) {
        String action = String.valueOf(args.getOrDefault("action", "list")).trim().toLowerCase();
        if ("read".equals(action)) {
            String name = String.valueOf(args.getOrDefault("name", "")).trim();
            if (name.isEmpty()) {
                return "Error: name is required when action=read.";
            }
            Skill skill = skillsByName.get(name);
            if (skill == null) {
                return "Error: unknown skill '" + name + "'. Available skills:\n" + listSkills();
            }
            return "# Skill: " + skill.getName() + "\n"
                + "Path: " + skill.getMarkdownPath() + "\n"
                + "Description: " + skill.getDescription() + "\n\n"
                + skill.getMarkdown();
        }

        return listSkills();
    }

    private String listSkills() {
        if (skillsByName.isEmpty()) {
            return "No skills are configured.";
        }
        return skillsByName.values().stream()
            .map(skill -> "- " + skill.getName() + ": " + skill.getDescription() +
                " (" + skill.getMarkdownPath() + ")")
            .collect(Collectors.joining("\n"));
    }
}

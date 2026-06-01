package com.corecc.skills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsDirectSkillAndChildSkills() throws Exception {
        Path direct = tempDir.resolve("direct");
        Files.createDirectories(direct);
        Files.writeString(direct.resolve("SKILL.md"), """
            ---
            name: "direct-skill"
            description: "Direct skill description."
            ---

            # Direct Skill
            """);

        Path root = tempDir.resolve("root");
        Path child = root.resolve("child");
        Files.createDirectories(child);
        Files.writeString(child.resolve("SKILL.md"), """
            # Child Skill

            Body.
            """);

        List<Skill> skills = SkillLoader.load(List.of(direct, root));
        Map<String, Skill> byName = skills.stream()
            .collect(Collectors.toMap(Skill::getName, skill -> skill));

        assertEquals(2, skills.size());
        assertEquals("Direct skill description.", byName.get("direct-skill").getDescription());
        assertTrue(byName.containsKey("child"));
        assertEquals("Child Skill", byName.get("child").getDescription());
    }

    @Test
    void skillToolListsAndReadsConfiguredSkills() throws Exception {
        Path skillDir = tempDir.resolve("tool-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: "tool-skill"
            description: "Tool skill description."
            ---

            # Tool Skill
            Use carefully.
            """);

        SkillTool tool = new SkillTool(SkillLoader.load(List.of(skillDir)));

        String list = tool.execute(Map.of("action", "list"));
        assertTrue(list.contains("tool-skill"));

        String read = tool.execute(Map.of("action", "read", "name", "tool-skill"));
        assertTrue(read.contains("Use carefully."));
    }
}

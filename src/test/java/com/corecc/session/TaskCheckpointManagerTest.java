package com.corecc.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskCheckpointManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void savesLoadsAndExposesCheckpointAsSession() {
        String previousDir = System.getProperty("corecc.checkpointsDir");
        System.setProperty("corecc.checkpointsDir", tempDir.toString());
        try {
            List<Map<String, Object>> messages = List.of(
                message("user", "Create /app/result.txt"),
                message("assistant", "I will inspect the files.")
            );
            String id = TaskCheckpointManager.saveCheckpoint(
                "trial/checkpoint.json",
                "gpt-test",
                messages,
                "running",
                "after_tools",
                3,
                "Create /app/result.txt",
                Map.of("context_tokens", 1234, "requested_output_paths", List.of("/app/result.txt"))
            );

            assertEquals("checkpoint", id);
            assertTrue(Files.exists(tempDir.resolve("checkpoint.json")));

            TaskCheckpointManager.TaskCheckpoint checkpoint = TaskCheckpointManager.loadCheckpoint(id);
            assertNotNull(checkpoint);
            assertEquals("gpt-test", checkpoint.model());
            assertEquals("running", checkpoint.status());
            assertEquals("after_tools", checkpoint.phase());
            assertEquals(3, checkpoint.round());
            assertEquals(2, checkpoint.messages().size());

            SessionManager.SessionData session = TaskCheckpointManager.loadAsSession(id);
            assertNotNull(session);
            assertEquals("gpt-test", session.model);
            assertEquals(2, session.messages.size());
        } finally {
            restoreProperty("corecc.checkpointsDir", previousDir);
        }
    }

    @Test
    void listCheckpointsSkipsNonCheckpointFiles() throws Exception {
        String previousDir = System.getProperty("corecc.checkpointsDir");
        System.setProperty("corecc.checkpointsDir", tempDir.toString());
        try {
            Files.writeString(tempDir.resolve("noise.json"), "{\"type\":\"session\"}");
            TaskCheckpointManager.saveCheckpoint(
                "task-a",
                "gpt-test",
                List.of(message("user", "hello")),
                "completed",
                "completed",
                0,
                "hello",
                Map.of()
            );

            List<TaskCheckpointManager.TaskCheckpointInfo> checkpoints =
                TaskCheckpointManager.listCheckpoints();

            assertEquals(1, checkpoints.size());
            assertEquals("task-a", checkpoints.get(0).id());
            assertEquals("completed", checkpoints.get(0).status());
        } finally {
            restoreProperty("corecc.checkpointsDir", previousDir);
        }
    }

    private static Map<String, Object> message(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}

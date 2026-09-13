package com.corecc.agent;

import com.corecc.llm.LLM;
import com.corecc.llm.LLMResponse;
import com.corecc.session.TaskCheckpointManager;
import com.corecc.tools.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AgentCheckpointTest {
    @TempDir
    Path tempDir;

    @Test
    void chatWritesCompletedCheckpoint() {
        String previousDir = System.getProperty("corecc.checkpointsDir");
        System.setProperty("corecc.checkpointsDir", tempDir.toString());
        try {
            Agent agent = new Agent(new StubLLM(), List.<Tool>of(), 8192, 3, null, false);
            agent.configureTaskCheckpoint(true, "agent-checkpoint", "gpt-test");

            String response = agent.chat("Say hello", null, null);

            assertEquals("done", response);
            TaskCheckpointManager.TaskCheckpoint checkpoint =
                TaskCheckpointManager.loadCheckpoint("agent-checkpoint");
            assertNotNull(checkpoint);
            assertEquals("completed", checkpoint.status());
            assertEquals("completed", checkpoint.phase());
            assertEquals(2, checkpoint.messages().size());
            assertEquals("Say hello", checkpoint.taskInput());
        } finally {
            restoreProperty("corecc.checkpointsDir", previousDir);
        }
    }

    static class StubLLM extends LLM {
        StubLLM() {
            super("gpt-test", "test-key", "http://localhost", Map.of("max_tokens", 128));
        }

        @Override
        public LLMResponse chat(List<Map<String, Object>> messages,
                                List<Map<String, Object>> tools,
                                Consumer<String> onToken) {
            return new LLMResponse("done", List.of(), "", 10, 1);
        }
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}

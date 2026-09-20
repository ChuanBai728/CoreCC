package com.corecc.agent;

import com.corecc.llm.LLM;
import com.corecc.llm.LLMResponse;
import com.corecc.llm.ToolCall;
import com.corecc.tools.Tool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentPermissionTest {
    @Test
    void planModeRefusesMutatingToolWithoutExecutingIt() {
        RecordingTool tool = new RecordingTool();
        Agent agent = new Agent(new ToolThenFinalLLM(), List.of(tool), 8_192, 3, null, false);
        agent.setPlanMode(true);

        assertEquals("planned", agent.chat("make a plan", null, null));
        assertFalse(tool.executed);
        assertTrue(agent.getMessages().stream()
            .anyMatch(message -> "tool".equals(message.get("role")) &&
                String.valueOf(message.get("content")).contains("Plan mode")));
    }

    private static final class RecordingTool implements Tool {
        private boolean executed;
        public String getName() { return "write_file"; }
        public String getDescription() { return "write"; }
        public Map<String, Object> getParameters() { return Map.of(); }
        public boolean isReadOnly() { return false; }
        public String execute(Map<String, Object> args) { executed = true; return "written"; }
    }

    private static final class ToolThenFinalLLM extends LLM {
        private int calls;
        private ToolThenFinalLLM() { super("test", "test", "http://localhost", Map.of()); }

        @Override
        public LLMResponse chat(List<Map<String, Object>> messages,
                                List<Map<String, Object>> tools,
                                java.util.function.Consumer<String> onToken) {
            if (calls++ == 0) {
                return new LLMResponse("", List.of(
                    new ToolCall("call-1", "write_file", Map.of())), "", 0, 0);
            }
            return new LLMResponse("planned", List.of(), "", 0, 0);
        }
    }
}

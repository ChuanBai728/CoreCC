package com.corecc.tools;

import com.corecc.agent.Agent;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 子智能体生成 —— 多智能体协作。
 *
 * 对应 Python 版的 corecc/tools/agent.py。
 * 对应 Claude Code 的 AgentTool（1397 行）。
 *
 * 为什么需要多智能体？
 * 单智能体模式下，所有子任务共享一个 128K 上下文窗口。
 * 多智能体为每个子智能体提供独立的 128K 上下文，三个子任务总容量 384K。
 *
 * 重要限制：子智能体不能创建子子智能体（防止递归风险）
 */
public class AgentTool implements Tool {
    // 由 Agent 构造后注入父智能体引用
    private Agent parentAgent;

    @Override
    public String getName() { return "agent"; }

    @Override
    public boolean isReadOnly() { return false; }

    @Override
    public String getDescription() {
        return "Spawn a sub-agent to handle a complex sub-task independently. " +
               "The sub-agent has its own context and tool access. Use this for: " +
               "researching a codebase, implementing a multi-step change in isolation, " +
               "or any task that would benefit from a fresh context window.";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "task", Map.of(
                    "type", "string",
                    "description", "子智能体需要完成的任务描述"
                )
            ),
            "required", List.of("task")
        );
    }

    /**
     * 设置父智能体引用。
     */
    public void setParentAgent(Agent parentAgent) {
        this.parentAgent = parentAgent;
    }

    @Override
    public String execute(Map<String, Object> args) {
        String task = (String) args.get("task");

        if (parentAgent == null) {
            return "错误：智能体工具未初始化（没有父智能体）";
        }

        try {
            // Create sub-agent with filtered tool list (no agent tool to prevent recursion)
            List<Tool> subTools = parentAgent.getTools().stream()
                .filter(t -> !t.getName().equals("agent"))
                .collect(Collectors.toList());

            Agent subAgent = new Agent(
                parentAgent.getLlm(),
                subTools,
                parentAgent.getContext().getMaxTokens(),
                20,  // max rounds for sub-agent
                null,  // no memory for sub-agent
                false,  // disable memory
                parentAgent.getCapabilityPromptBlock()
            );

            // Run sub-agent
            String result = subAgent.chat(task, null, null);

            // Truncate long results
            if (result.length() > 5000) {
                result = result.substring(0, 4500) + "\n... （子智能体输出已截断）";
            }

            return "[子智能体已完成]\n" + result;
        } catch (Exception e) {
            return "子智能体错误：" + e.getMessage();
        }
    }
}

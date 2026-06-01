package com.corecc.agent;

import com.corecc.context.CompressionReport;
import com.corecc.context.ContextManager;
import com.corecc.llm.LLM;
import com.corecc.llm.LLMResponse;
import com.corecc.llm.ToolCall;
import com.corecc.memory.MemoryEntry;
import com.corecc.memory.MemoryStore;
import com.corecc.prompt.PromptBuilder;
import com.corecc.runtime.ArtifactVerifier;
import com.corecc.runtime.RuntimeReview;
import com.corecc.runtime.RuntimeStats;
import com.corecc.runtime.ToolReview;
import com.corecc.tools.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 核心智能体循环 —— CoreCC 的引擎层（Layer 2）。
 *
 * 对应 Python 版的 corecc/agent.py。
 *
 * 核心流程：
 * 用户消息 -> 大模型（带工具） -> 工具调用？ -> 执行 -> 循环
 *                                 -> 纯文本回复？ -> 返回给用户
 */
public class Agent {
    private final LLM llm;
    private final List<Tool> tools;
    private final List<Map<String, Object>> messages;
    private final ContextManager context;
    private final MemoryStore memory;
    private final boolean enableMemory;
    private List<MemoryEntry> activeMemories;
    private final RuntimeStats stats;
    private final int maxRounds;
    private final String systemPrompt;
    private final String capabilityPromptBlock;

    public Agent(LLM llm, List<Tool> tools, int maxContextTokens, int maxRounds,
                 MemoryStore memory, boolean enableMemory) {
        this(llm, tools, maxContextTokens, maxRounds, memory, enableMemory, "");
    }

    public Agent(LLM llm, List<Tool> tools, int maxContextTokens, int maxRounds,
                 MemoryStore memory, boolean enableMemory, String capabilityPromptBlock) {
        this.llm = llm;
        this.tools = tools != null ? tools : ToolRegistry.getAllTools();
        this.messages = new ArrayList<>();
        this.context = new ContextManager(maxContextTokens);
        this.memory = memory;
        this.enableMemory = enableMemory;
        this.activeMemories = new ArrayList<>();
        this.stats = new RuntimeStats();
        this.maxRounds = maxRounds;
        this.capabilityPromptBlock = capabilityPromptBlock != null ? capabilityPromptBlock : "";
        this.systemPrompt = PromptBuilder.systemPrompt(this.tools, this.capabilityPromptBlock);

        // Inject parent agent reference for AgentTool
        for (Tool t : this.tools) {
            if (t instanceof AgentTool) {
                ((AgentTool) t).setParentAgent(this);
            }
        }
    }

    /**
     * 构造函数重载，使用默认值。
     */
    public Agent(LLM llm, int maxContextTokens) {
        this(llm, ToolRegistry.getAllTools(), maxContextTokens, 50,
             MemoryStore.forWorkspace(null, null), true);
    }

    /**
     * 获取 LLM 实例。
     */
    public LLM getLlm() { return llm; }

    /**
     * 获取工具列表。
     */
    public List<Tool> getTools() { return tools; }

    /**
     * 获取消息历史。
     */
    public List<Map<String, Object>> getMessages() { return messages; }

    /**
     * 获取上下文管理器。
     */
    public ContextManager getContext() { return context; }

    /**
     * 获取运行时统计。
     */
    public RuntimeStats getStats() { return stats; }

    public String getCapabilityPromptBlock() { return capabilityPromptBlock; }

    /**
     * 处理一条用户消息，可能涉及多轮大模型/工具调用。
     */
    public String chat(String userInput, Consumer<String> onToken, BiConsumer<String, Map<String, Object>> onTool) {
        // Search memory if enabled
        if (memory != null && !MemoryStore.shouldIgnoreMemory(userInput)) {
            activeMemories = memory.search(userInput, 5, true);
        } else {
            activeMemories = new ArrayList<>();
        }

        messages.add(Map.of("role", "user", "content", userInput));
        CompressionReport report = context.maybeCompress(messages, llm, "auto", false);
        recordContextReport(report);

        // Artifact nudge tracking
        List<String> requestedOutputPaths = requestedOutputPaths(userInput);
        int artifactNudges = 0;
        int emptyFinalRetries = 0;
        boolean artifactVerified = false;
        int verificationAttempts = 0;
        int missingArtifactToolRounds = 0;
        int transientLlmRecoveries = 0;
        int maxTransientLlmRecoveries = maxTransientLlmRecoveries();

        // Token budget control: track per-round spending
        int tokenBudget = llm.getMaxTokens() > 0 ? llm.getMaxTokens() * 4 : 32768 * 4;

        for (int round = 0; round < maxRounds; round++) {
            LLMResponse resp;
            try {
                resp = callLlmWithReactiveCompact(onToken);
            } catch (ContextLengthRetryError e) {
                return e.getMessage();
            } catch (RuntimeException e) {
                if (isTransientLlmError(e)) {
                    List<String> missingOutputs = missingOutputPaths(requestedOutputPaths);
                    if (!missingOutputs.isEmpty() && transientLlmRecoveries < maxTransientLlmRecoveries) {
                        transientLlmRecoveries++;
                        recordContextReport(compactForTransientLlmRecovery());
                        messages.add(Map.of("role", "user", "content",
                            transientLlmRecoveryNudge(missingOutputs, rootMessage(e),
                                transientLlmRecoveries, maxTransientLlmRecoveries)));
                        continue;
                    }
                    return "LLM request failed after retries: " + rootMessage(e);
                }
                throw e;
            }

            // No tool calls -> LLM finished, return text
            if (resp.getToolCalls() == null || resp.getToolCalls().isEmpty()) {

                // Check for missing output artifacts (BEFORE empty content check)
                List<String> missingOutputs = missingOutputPaths(requestedOutputPaths);
                if (!missingOutputs.isEmpty() && artifactNudges < 3) {
                    messages.add(resp.toMessage());
                    artifactNudges++;
                    messages.add(Map.of("role", "user", "content", artifactNudge(missingOutputs)));
                    continue;
                }

                // Artifact verification: file exists but not yet verified
                if (missingOutputs.isEmpty() && !artifactVerified && !requestedOutputPaths.isEmpty()) {
                    List<String[]> checks = ArtifactVerifier.buildChecks(userInput, requestedOutputPaths);
                    if (!checks.isEmpty() && verificationAttempts < 3) {
                        List<String> checkResults = new ArrayList<>();
                        for (String[] check : checks) {
                            checkResults.add(runBashCommand(check[1]));
                        }
                        ArtifactVerifier.VerifyReport vReport = ArtifactVerifier.evaluate(checks, checkResults);
                        if (!vReport.allPassed()) {
                            messages.add(resp.toMessage());
                            verificationAttempts++;
                            messages.add(Map.of("role", "user", "content", vReport.getNudge()));
                            continue;
                        }
                        artifactVerified = true;
                    }
                }

                // Handle empty final response
                String content = resp.getContent();
                if (content == null || content.trim().isEmpty()) {
                    messages.add(resp.toMessage());
                    if (emptyFinalRetries < 2) {
                        emptyFinalRetries++;
                        // Probe hint: guide model to act instead of just thinking
                        if (!requestedOutputPaths.isEmpty()) {
                            messages.add(Map.of("role", "user", "content",
                                "[运行时提示]\n请立即开始执行：先用 bash 检查环境和文件，" +
                                "然后用 write_file 写出最小可运行版本，再逐步迭代。" +
                                "不要在思考中消耗过多 token。"));
                        } else {
                            messages.add(Map.of("role", "user", "content",
                                "[运行时提示]\n上一轮没有产生最终文本，也没有调用工具。" +
                                "如果任务要求创建、保存或修改文件，请立即使用 write_file、edit_file " +
                                "或 bash 完成并验证；如果已经完成，请给出简短最终说明。"));
                        }
                        continue;
                    }
                    return "（模型未返回最终内容或工具调用）";
                }

                messages.add(resp.toMessage());
                return content;
            }

            // Has tool calls -> execute tools
            messages.add(resp.toMessage());

            if (resp.getToolCalls().size() == 1) {
                ToolCall tc = resp.getToolCalls().get(0);
                if (onTool != null) {
                    onTool.accept(tc.getName(), tc.getArguments());
                }
                String result = execTool(tc);
                messages.add(Map.of(
                    "role", "tool",
                    "tool_call_id", tc.getId(),
                    "content", result
                ));
            } else {
                // Execute multiple tools (parallel for read-only, sequential for write)
                List<String> results = execTools(resp.getToolCalls(), onTool);
                for (int i = 0; i < resp.getToolCalls().size(); i++) {
                    ToolCall tc = resp.getToolCalls().get(i);
                    messages.add(Map.of(
                        "role", "tool",
                        "tool_call_id", tc.getId(),
                        "content", results.get(i)
                    ));
                }
            }

            // Compress context after tool execution
            report = context.maybeCompress(messages, llm, "auto", false);
            recordContextReport(report);

            if (!requestedOutputPaths.isEmpty()) {
                List<String> missingOutputs = missingOutputPaths(requestedOutputPaths);
                if (!missingOutputs.isEmpty()) {
                    missingArtifactToolRounds++;
                    if (missingArtifactToolRounds == 6 || round >= maxRounds - 8) {
                        messages.add(Map.of("role", "user", "content",
                            "Runtime benchmark warning: requested output files are still missing: " +
                            String.join(", ", missingOutputs) + ". Stop exploring and create the required artifact now. " +
                            "Use write_file for text or write_bytes_base64 for binary data, then run a focused verification command."));
                    }
                }
            }
        }

        if (!requestedOutputPaths.isEmpty()) {
            List<String> missingOutputs = missingOutputPaths(requestedOutputPaths);
            if (!missingOutputs.isEmpty()) {
                return "Reached maximum tool rounds; missing requested output files: " + String.join(", ", missingOutputs);
            }
        }

        return "(已达到最大工具调用轮数)";
    }

    /**
     * 调用 LLM；遇到上下文过长时强制硬压缩并重试一次。
     */
    private LLMResponse callLlmWithReactiveCompact(Consumer<String> onToken) {
        try {
            return llm.chat(fullMessages(), toolSchemas(), onToken);
        } catch (Exception e) {
            if (!isContextLengthError(e)) {
                throw new RuntimeException(e);
            }

            CompressionReport report = context.maybeCompress(messages, llm, "reactive", true);
            recordContextReport(report);

            try {
                return llm.chat(fullMessages(), toolSchemas(), onToken);
            } catch (Exception retryError) {
                if (isContextLengthError(retryError)) {
                    throw new ContextLengthRetryError("错误：上下文过长，已尝试压缩后仍无法发送请求。");
                }
                throw new RuntimeException(retryError);
            }
        }
    }

    /**
     * 构建包含系统提示的完整消息列表。
     */
    private List<Map<String, Object>> fullMessages() {
        String system = systemPrompt;
        String memoryBlock = MemoryStore.formatMemoryBlock(activeMemories, 1800);
        if (!memoryBlock.isEmpty()) {
            system = system + "\n\n" + memoryBlock;
        }

        List<Map<String, Object>> full = new ArrayList<>();
        full.add(Map.of("role", "system", "content", system));
        full.addAll(messages);
        return full;
    }

    /**
     * 获取所有工具的 OpenAI function-calling schema。
     */
    private List<Map<String, Object>> toolSchemas() {
        return tools.stream()
            .map(Tool::toSchema)
            .collect(Collectors.toList());
    }

    /**
     * 按名称查找工具。
     */
    private Tool getTool(String name) {
        return tools.stream()
            .filter(t -> t.getName().equals(name))
            .findFirst()
            .orElse(null);
    }

    /**
     * 执行单个工具调用。
     */
    private String execTool(ToolCall tc) {
        Tool tool = getTool(tc.getName());
        if (tool == null) {
            return finalizeToolResult(tc.getName(), "错误：未知工具 '" + tc.getName() + "'");
        }

        String result;
        try {
            result = tool.execute(tc.getArguments() != null ? tc.getArguments() : Map.of());
        } catch (IllegalArgumentException e) {
            result = "错误：" + tc.getName() + " 参数错误: " + e.getMessage();
        } catch (Exception e) {
            result = "执行 " + tc.getName() + " 时出错: " + e.getMessage();
        }

        result = context.optimizeToolResult(tc.getName(), tc.getArguments(), result);
        recordContextReport(context.getLastReport());
        return finalizeToolResult(tc.getName(), result);
    }

    /**
     * 压缩工具输出、分类错误，并记录运行时统计。
     */
    private String finalizeToolResult(String toolName, String result) {
        ToolReview review = RuntimeReview.reviewToolResult(toolName, result);
        stats.recordTool(toolName, review);
        return review.getContent();
    }

    /**
     * 执行多个工具调用。
     */
    private List<String> execTools(List<ToolCall> toolCalls, BiConsumer<String, Map<String, Object>> onTool) {
        List<String> results = new ArrayList<>(Collections.nCopies(toolCalls.size(), ""));
        int i = 0;

        while (i < toolCalls.size()) {
            ToolCall tc = toolCalls.get(i);
            Tool tool = getTool(tc.getName());
            boolean isReadOnly = tool != null && tool.isReadOnly();

            if (!isReadOnly) {
                if (onTool != null) onTool.accept(tc.getName(), tc.getArguments());
                results.set(i, execTool(tc));
                i++;
                continue;
            }

            // Batch read-only tools for parallel execution
            List<int[]> batch = new ArrayList<>();
            while (i < toolCalls.size()) {
                ToolCall nextTc = toolCalls.get(i);
                Tool nextTool = getTool(nextTc.getName());
                if (!(nextTool != null && nextTool.isReadOnly())) break;

                if (onTool != null) onTool.accept(nextTc.getName(), nextTc.getArguments());
                batch.add(new int[]{i});
                i++;
            }

            if (batch.size() == 1) {
                results.set(batch.get(0)[0], execTool(toolCalls.get(batch.get(0)[0])));
            } else {
                // Parallel execution using thread pool
                ExecutorService pool = Executors.newFixedThreadPool(Math.min(8, batch.size()));
                List<Future<String>> futures = new ArrayList<>();

                for (int[] idx : batch) {
                    final int index = idx[0];
                    futures.add(pool.submit(() -> execTool(toolCalls.get(index))));
                }

                for (int j = 0; j < batch.size(); j++) {
                    try {
                        results.set(batch.get(j)[0], futures.get(j).get(30, TimeUnit.SECONDS));
                    } catch (Exception e) {
                        results.set(batch.get(j)[0], "执行超时：" + e.getMessage());
                    }
                }

                pool.shutdown();
            }
        }

        return results;
    }

    /**
     * 清空对话历史。
     */
    public void reset() {
        messages.clear();
        activeMemories.clear();
        // Note: We don't reset stats here to keep session-level metrics
    }

    /**
     * 将上下文优化报告写入运行时统计。
     */
    private void recordContextReport(CompressionReport report) {
        if (report != null && report.isChanged()) {
            stats.recordCompression(report);
        }
    }

    /**
     * 识别 OpenAI API 的上下文过长错误。
     */
    private boolean isContextLengthError(Exception e) {
        String text = e.getMessage().toLowerCase();
        return text.contains("context length") ||
               text.contains("maximum context") ||
               text.contains("context_length_exceeded") ||
               text.contains("too many tokens") ||
               text.contains("token limit") ||
               text.contains("request too large") ||
               text.contains("prompt is too long") ||
               text.contains("413") ||
               text.contains("上下文") ||
               text.contains("请求过大");
    }

    private boolean isTransientLlmError(Exception e) {
        String text = rootMessage(e).toLowerCase();
        return text.contains("llm stream timed out") ||
               text.contains("timeout") ||
               text.contains("connection refused") ||
               text.contains("connectexception") ||
               text.contains("failed to connect") ||
               text.contains("couldn't connect") ||
               text.contains("network is unreachable") ||
               text.contains("http 429") ||
               text.contains("too many requests") ||
               text.contains("rate limit") ||
               text.contains("http 500") ||
               text.contains("http 502") ||
               text.contains("http 503") ||
               text.contains("http 504");
    }

    private String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null) {
            cur = cur.getCause();
        }
        return cur.getMessage() != null ? cur.getMessage() : cur.toString();
    }

    private int maxTransientLlmRecoveries() {
        try {
            return Math.max(0, Integer.parseInt(
                System.getenv().getOrDefault("CORECC_TRANSIENT_LLM_RECOVERY_ROUNDS", "2")));
        } catch (NumberFormatException e) {
            return 2;
        }
    }

    private CompressionReport compactForTransientLlmRecovery() {
        int before = ContextManager.estimateTokens(messages);
        boolean changed = false;
        int recentStart = Math.max(0, messages.size() - 4);

        for (int i = 0; i < messages.size(); i++) {
            Map<String, Object> message = messages.get(i);
            if (!"tool".equals(message.get("role"))) continue;

            Object rawContent = message.get("content");
            if (!(rawContent instanceof String content)) continue;

            int limit = i >= recentStart ? 3_000 : 1_000;
            if (content.length() <= limit) continue;

            Map<String, Object> copy = new LinkedHashMap<>(message);
            copy.put("content", compactTextForRecovery(content, limit));
            messages.set(i, copy);
            changed = true;
        }

        int after = ContextManager.estimateTokens(messages);
        return new CompressionReport(
            changed,
            before,
            after,
            Math.max(0, before - after),
            changed ? List.of("transient_llm_tool_snip") : List.of(),
            "transient_llm_recovery"
        );
    }

    private String compactTextForRecovery(String text, int limit) {
        int head = Math.max(200, limit / 2);
        int tail = Math.max(200, limit - head);
        return text.substring(0, Math.min(head, text.length())) +
            String.format("\n\n[tool output compacted after transient LLM error: original %d chars]\n\n", text.length()) +
            text.substring(Math.max(0, text.length() - tail));
    }

    private String transientLlmRecoveryNudge(List<String> missingOutputs, String error,
                                             int attempt, int maxAttempts) {
        String listed = String.join(", ", missingOutputs);
        return String.format("""
            [Runtime recovery after transient LLM failure]
            The previous LLM request failed with: %s
            Required output files are still missing: %s
            Recovery attempt %d/%d has compacted older tool output to reduce context pressure.

            Continue; do not stop or answer in prose only.
            Immediately create the missing artifact(s). Prefer local bash/python commands over long reasoning:
            1. Use files already in /app and focused commands to compute/generate the artifact.
            2. Use write_file for text artifacts, write_bytes_base64 or bash for binary artifacts.
            3. Verify with the task's exact command or test script before finalizing.
            4. Avoid re-reading huge files unless strictly needed; use scripts to process them in-place.
            """, error, listed, attempt, maxAttempts);
    }

    /**
     * 提取任务中要求输出的文件路径。
     */
    private List<String> requestedOutputPaths(String text) {
        Pattern outputIntent = Pattern.compile(
            "(save|write|create|output|generate|保存|写入|创建|生成|输出|存到|落盘)",
            Pattern.CASE_INSENSITIVE
        );
        // Match absolute paths AND relative filenames with extensions
        Pattern pathRe = Pattern.compile(
            "(?:[A-Za-z]:\\\\[^\\s'\"`<>|]+|/(?:[A-Za-z0-9._-]+/)*[A-Za-z0-9._-]+|[A-Za-z0-9_-]+\\.[A-Za-z]{1,5})"
        );

        if (!outputIntent.matcher(text).find()) {
            return Collections.emptyList();
        }

        List<String> paths = new ArrayList<>();
        Matcher matcher = pathRe.matcher(text);
        while (matcher.find() && paths.size() < 5) {
            String raw = matcher.group().replaceAll("[.,;:)]+$", "");
            if (!paths.contains(raw) && raw.contains(".") && raw.length() > 3) {
                paths.add(raw);
            }
        }
        return paths;
    }

    /**
     * 检查哪些输出路径仍然不存在。
     */
    private List<String> missingOutputPaths(List<String> paths) {
        return paths.stream()
            .filter(raw -> {
                try {
                    return !java.nio.file.Path.of(raw).toAbsolutePath().normalize().toFile().exists();
                } catch (Exception e) {
                    return true;
                }
            })
            .collect(Collectors.toList());
    }

    /**
     * 生成文件产物提示。
     */
    private String artifactNudge(List<String> paths) {
        String primary = paths.get(0);
        String listed = String.join(", ", paths);
        return String.format("""
            [运行时提示]
            任务要求生成文件产物，但以下路径仍不存在：%s
            不要只给出文本答案。下一轮必须调用 write_file 或 edit_file 写入所需文件，优先使用 write_file(file_path="%s", content=...)；写入后用 bash 或 read_file 验证文件存在。""",
            listed, primary);
    }

    /**
     * 执行 bash 命令并返回输出（用于验证检查）。
     */
    private String runBashCommand(String command) {
        Tool bashTool = getTool("bash");
        if (bashTool == null) return "ERROR: bash tool not found";
        try {
            return bashTool.execute(Map.of("command", command, "timeout", 30));
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * 上下文过长重试错误。
     */
    private static class ContextLengthRetryError extends RuntimeException {
        ContextLengthRetryError(String message) {
            super(message);
        }
    }
}

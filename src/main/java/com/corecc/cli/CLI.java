package com.corecc.cli;

import com.corecc.agent.Agent;
import com.corecc.capabilities.CapabilityConfig;
import com.corecc.capabilities.LoadedCapabilities;
import com.corecc.config.Config;
import com.corecc.context.ContextManager;
import com.corecc.llm.LLM;
import com.corecc.memory.MemoryEntry;
import com.corecc.memory.MemoryStore;
import com.corecc.permissions.PermissionPolicy;
import com.corecc.runtime.RuntimeStats;
import com.corecc.session.SessionManager;
import com.corecc.session.TaskCheckpointManager;
import com.corecc.tools.EditFileTool;
import com.corecc.tools.Tool;
import com.corecc.tools.ToolRegistry;
import com.corecc.tools.WriteFileTool;

import org.jline.reader.*;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 交互式 REPL —— 面向用户的终端界面（Layer 1）。
 *
 * 使用 JLine3 实现类似 prompt_toolkit 的功能。
 */
public class CLI {
    private final Config config;
    private final Agent agent;
    private final Terminal terminal;
    private final MemoryStore memoryStore;
    private LineReader reader;

    public Agent getAgent() { return agent; }

    public CLI(Config config) throws IOException {
        this(config, true, false);
    }

    public CLI(Config config, boolean allowAll, boolean interactive) throws IOException {
        this.config = config;
        this.terminal = TerminalBuilder.builder()
            .system(true)
            .build();

        // Create LLM
        Map<String, Object> extra = new HashMap<>();
        extra.put("temperature", config.getTemperature());
        extra.put("max_tokens", config.getMaxTokens());

        LLM llm = new LLM(
            config.getModel(),
            config.getApiKey(),
            config.getBaseUrl(),
            extra
        );

        // Create Agent with optional dynamic capabilities
        LoadedCapabilities capabilities = ToolRegistry.loadConfiguredTools(CapabilityConfig.fromEnv());
        for (String warning : capabilities.getWarnings()) {
            System.err.println("[CoreCC capability warning] " + warning);
        }
        this.memoryStore = MemoryStore.forWorkspace(null, null);
        this.agent = new Agent(
            llm,
            capabilities.getTools(),
            config.getMaxContextTokens(),
            50,
            memoryStore,
            true,
            capabilities.promptBlock()
        );
        this.agent.configureTaskCheckpoint(
            config.isTaskCheckpointEnabled(),
            config.getCheckpointId(),
            config.getModel()
        );
        this.agent.setPermissionPolicy(new PermissionPolicy(interactive ? this::askPermission : null, allowAll));
    }

    /**
     * 运行单次任务模式。
     */
    public void runOnce(String prompt) {
        System.out.print("> " + prompt + "\n");

        String response = agent.chat(prompt,
            token -> System.out.print(token),
            (name, args) -> System.out.printf("\n> %s(%s)%n", name, brief(args))
        );
        if (response != null && !response.isEmpty()) {
            System.out.println(response);
        }
        System.out.println();
    }

    /**
     * 运行交互式 REPL。
     */
    public void repl() throws IOException {
        System.out.printf("""
            ┌────────────────────────────────────────┐
            │  CoreCC v0.3.0                         │
            │  模型: %-32s │
            │  输入 /help 查看命令，Ctrl+C 取消       │
            └────────────────────────────────────────┘
            """, config.getModel());

        // Setup JLine reader
        DefaultParser parser = new DefaultParser();
        parser.setEofOnUnclosedBracket(DefaultParser.Bracket.CURLY, DefaultParser.Bracket.ROUND, DefaultParser.Bracket.SQUARE);

        Path historyPath = Path.of(System.getProperty("user.home"), ".corecc_history");
        History history = new DefaultHistory();

        reader = LineReaderBuilder.builder()
            .terminal(terminal)
            .parser(parser)
            .history(history)
            .variable(LineReader.HISTORY_FILE, historyPath)
            .build();

        while (true) {
            String userInput;
            try {
                userInput = reader.readLine("You > ").trim();
            } catch (UserInterruptException e) {
                System.out.println("\n再见！");
                break;
            } catch (EndOfFileException e) {
                System.out.println("\n再见！");
                break;
            }

            if (userInput.isEmpty()) continue;

            // Built-in commands
            switch (userInput.toLowerCase()) {
                case "quit", "exit", "/quit", "/exit" -> {
                    return;
                }
                case "/help" -> {
                    showHelp();
                    continue;
                }
                case "/reset" -> {
                    agent.reset();
                    System.out.println("对话已重置。");
                    continue;
                }
                case "/tokens" -> {
                    System.out.println(tokenUsageLine());
                    continue;
                }
                case "/status" -> {
                    System.out.println(statusPanel());
                    continue;
                }
            }

            if (userInput.equals("/model") || userInput.startsWith("/model ")) {
                String newModel = userInput.startsWith("/model ") ?
                    userInput.substring(7).trim() : "";
                if (!newModel.isEmpty()) {
                    agent.getLlm().setModel(newModel);
                    config.setModel(newModel);
                    System.out.println("已切换到 " + newModel);
                } else {
                    System.out.println("当前模型：" + config.getModel());
                }
                continue;
            }

            if (userInput.equals("/plan")) {
                agent.setPlanMode(!agent.isPlanMode());
                System.out.println(agent.isPlanMode()
                    ? "Plan 模式已开启：只允许只读工具。输入 approve 执行计划，或再次输入 /plan 退出。"
                    : "Plan 模式已关闭。");
                continue;
            }

            if (agent.isPlanMode() && (userInput.equalsIgnoreCase("approve") || userInput.equalsIgnoreCase("/approve"))) {
                agent.setPlanMode(false);
                userInput = "approve";
                System.out.println("Plan 模式已关闭，开始执行。 ");
            }

            if (userInput.equals("/compact")) {
                int before = ContextManager.estimateTokens(agent.getMessages());
                var compressed = agent.getContext().maybeCompress(
                    agent.getMessages(), agent.getLlm(), "manual", true);
                if (compressed.isChanged()) {
                    agent.getStats().recordCompression(compressed);
                }
                int after = ContextManager.estimateTokens(agent.getMessages());
                if (compressed.isChanged()) {
                    String actions = compressed.getActions().isEmpty() ?
                        "none" : String.join(", ", compressed.getActions());
                    System.out.printf("已压缩：%d -> %d token，节省 %d token（%d 条消息；%s）%n",
                        before, after, compressed.getTokensSaved(),
                        agent.getMessages().size(), actions);
                } else {
                    System.out.printf("无需压缩（%d token，%d 条消息）%n",
                        before, agent.getMessages().size());
                }
                continue;
            }

            if (userInput.equals("/memory") || userInput.startsWith("/memory ")) {
                String query = userInput.startsWith("/memory ") ?
                    userInput.substring(8).trim() : "";
                List<MemoryEntry> entries = query.isEmpty()
                    ? memoryStore.listRecent(10)
                    : memoryStore.search(query, 10, false);
                if (entries.isEmpty()) {
                    System.out.println(query.isEmpty() ? "暂无长期记忆。" : "没有匹配的长期记忆。");
                } else {
                    entries.forEach(entry -> System.out.printf("  %s [%s/%s] %s — %s%n",
                        entry.getId(), entry.getType(), entry.getScope(),
                        entry.getName(), compact(entry.getContent(), 100)));
                }
                continue;
            }

            if (userInput.startsWith("/remember ")) {
                String rawMemory = userInput.substring(10).trim();
                if (rawMemory.isEmpty()) {
                    System.out.println("用法：/remember [user|feedback|project|reference] <内容>");
                    continue;
                }
                String type = null;
                String content = rawMemory;
                int separator = rawMemory.indexOf(' ');
                if (separator > 0 && MemoryStore.MEMORY_TYPES.contains(rawMemory.substring(0, separator))) {
                    type = rawMemory.substring(0, separator);
                    content = rawMemory.substring(separator + 1).trim();
                }
                try {
                    MemoryEntry entry = memoryStore.add(content, List.of(), null, null,
                        type != null ? type : MemoryStore.inferMemoryType(content), null);
                    System.out.printf("已记住：%s（%s）%n", entry.getId(), entry.getName());
                } catch (IllegalArgumentException e) {
                    System.out.println("无法保存记忆：" + e.getMessage());
                }
                continue;
            }

            if (userInput.startsWith("/forget ")) {
                String entryId = userInput.substring(8).trim();
                System.out.println(memoryStore.delete(entryId)
                    ? "已删除记忆：" + entryId
                    : "未找到记忆：" + entryId);
                continue;
            }

            if (userInput.equals("/save")) {
                String sid = SessionManager.saveSession(
                    agent.getMessages(), config.getModel(), null);
                System.out.println("会话已保存：" + sid);
                System.out.println("恢复命令：corecc -r " + sid);
                continue;
            }

            if (userInput.equals("/diff")) {
                Set<String> changedFiles = new HashSet<>();
                changedFiles.addAll(WriteFileTool.changedFiles);
                changedFiles.addAll(EditFileTool.changedFiles);

                if (changedFiles.isEmpty()) {
                    System.out.println("本次会话未修改任何文件。");
                } else {
                    System.out.printf("本次会话修改的文件（%d 个）：%n", changedFiles.size());
                    changedFiles.stream().sorted().forEach(f -> System.out.println("  " + f));
                }
                continue;
            }

            if (userInput.equals("/sessions")) {
                List<SessionManager.SessionInfo> sessions = SessionManager.listSessions();
                if (sessions.isEmpty()) {
                    System.out.println("没有已保存的会话。");
                } else {
                    for (SessionManager.SessionInfo s : sessions) {
                        System.out.printf("  %s (%s, %s) %s%n",
                            s.id, s.model, s.savedAt, s.preview);
                    }
                }
                continue;
            }

            if (userInput.equals("/checkpoints")) {
                List<TaskCheckpointManager.TaskCheckpointInfo> checkpoints =
                    TaskCheckpointManager.listCheckpoints();
                if (checkpoints.isEmpty()) {
                    System.out.println("No saved task checkpoints.");
                } else {
                    for (TaskCheckpointManager.TaskCheckpointInfo checkpoint : checkpoints) {
                        System.out.printf("  %s (%s/%s, %s, %s) %s%n",
                            checkpoint.id(),
                            checkpoint.status(),
                            checkpoint.phase(),
                            checkpoint.model(),
                            checkpoint.updatedAt(),
                            checkpoint.preview());
                    }
                }
                continue;
            }

            if (userInput.startsWith("/")) {
                System.out.println("未知命令：" + userInput.split("\\s+", 2)[0] + "（输入 /help 查看帮助）");
                continue;
            }

            // Call agent to process user input
            StringBuilder streamed = new StringBuilder();

            try {
                String response = agent.chat(
                    userInput,
                    token -> {
                        streamed.append(token);
                        System.out.print(token);
                    },
                    (name, args) -> System.out.printf("\n> %s(%s)%n", name, brief(args))
                );

                if (streamed.length() > 0) {
                    System.out.println(); // Newline after streaming
                } else {
                    System.out.println(response);
                }
            } catch (Exception e) {
                System.out.printf("\n错误：%s%n", e.getMessage());
            }
        }
    }

    private PermissionPolicy.Decision askPermission(String toolName, Map<String, Object> args) {
        if (reader == null) return PermissionPolicy.Decision.DENY;
        try {
            String answer = reader.readLine(String.format(
                "允许 %s(%s)？[y] 一次 [a] 本会话 [n] 拒绝 > ", toolName, brief(args))).trim().toLowerCase();
            if (answer.equals("a") || answer.equals("always")) return PermissionPolicy.Decision.ALLOW_ALWAYS;
            if (answer.equals("y") || answer.equals("yes")) return PermissionPolicy.Decision.ALLOW_ONCE;
        } catch (UserInterruptException | EndOfFileException ignored) {
            // Interruption at a consent prompt is a denial, not an application exit.
        }
        return PermissionPolicy.Decision.DENY;
    }

    private void showHelp() {
        System.out.println("""
            命令：
              /help          显示此帮助
              /reset         清空对话历史
              /model         显示当前模型
              /model <名称>  对话中切换模型
              /tokens        显示 Token 用量
              /status        显示运行时状态
              /compact       压缩对话上下文
              /plan          切换只读规划模式
              /remember <内容> 保存长期记忆
              /memory [关键词] 查看或搜索长期记忆
              /forget <ID>    删除长期记忆
              /diff          显示本次会话修改的文件
              /save          保存会话到磁盘
              /sessions      列出已保存的会话
              /checkpoints   列出任务检查点
              quit           退出 CoreCC
            """);
    }

    private String tokenUsageLine() {
        long promptTokens = agent.getLlm().totalPromptTokens;
        long completionTokens = agent.getLlm().totalCompletionTokens;
        long total = promptTokens + completionTokens;
        return String.format("Token 用量：%d 提示词 + %d 补全 = %d 总计",
            promptTokens, completionTokens, total);
    }

    private String statusPanel() {
        RuntimeStats stats = agent.getStats();
        int contextTokens = ContextManager.estimateTokens(agent.getMessages());
        int maxTokens = agent.getContext().getMaxTokens();
        double pressure = maxTokens > 0 ? (contextTokens / (double) maxTokens * 100) : 0;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("上下文：%d / %d tokens（%.1f%%）%n", contextTokens, maxTokens, pressure));
        sb.append(String.format("工具：%d 次（成功 %d，失败 %d）%n",
            stats.getToolCalls(), stats.getToolSuccesses(), stats.getToolFailures()));
        sb.append(String.format("压缩：%d 次；节省：%d token；工具输出节省：%d 字符%n",
            stats.getCompressions(), stats.getTokensSaved(), stats.getCompactedChars()));

        if (!stats.getErrorCategories().isEmpty()) {
            sb.append("错误类别：").append(stats.getErrorCategories()).append("\n");
        }
        if (stats.getLastErrorCategory() != null && !stats.getLastErrorCategory().isEmpty()) {
            sb.append(String.format("最近错误：%s / %s%n", stats.getLastTool(), stats.getLastErrorCategory()));
            sb.append("恢复建议：").append(stats.getLastNudge()).append("\n");
        }

        return sb.toString();
    }

    private String brief(Map<String, Object> args, int maxlen) {
        if (args == null) return "";
        String s = args.entrySet().stream()
            .map(e -> String.format("%s=%s", e.getKey(),
                String.valueOf(e.getValue()).substring(0, Math.min(40, String.valueOf(e.getValue()).length()))))
            .collect(Collectors.joining(", "));
        return s.length() > maxlen ? s.substring(0, maxlen) + "..." : s;
    }

    private String compact(String text, int maxLength) {
        String value = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 3) + "...";
    }

    private String brief(Map<String, Object> args) {
        return brief(args, 80);
    }
}

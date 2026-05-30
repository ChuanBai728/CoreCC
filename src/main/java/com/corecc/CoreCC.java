package com.corecc;

import com.corecc.cli.CLI;
import com.corecc.config.Config;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * CoreCC - 轻量级 AI 编程助手 Java 版本。
 *
 * 对应 Python 版的 corecc/__main__.py 和 corecc/cli.py。
 */
@Command(
    name = "corecc",
    description = "CoreCC - lightweight local AI coding agent for OpenAI APIs.",
    version = "0.3.0",
    mixinStandardHelpOptions = true
)
public class CoreCC implements Runnable {

    @Option(names = {"-m", "--model"}, description = "模型名称（默认：$CORECC_MODEL 或 gpt-4o）")
    private String model;

    @Option(names = {"--base-url"}, description = "API 基础 URL（默认：$OPENAI_BASE_URL）")
    private String baseUrl;

    @Option(names = {"--api-key"}, description = "API 密钥（默认：$OPENAI_API_KEY）")
    private String apiKey;

    @Option(names = {"-p", "--prompt"}, description = "单次提示（非交互模式）")
    private String prompt;

    @Option(names = {"-r", "--resume"}, description = "恢复已保存的会话")
    private String resume;

    @Option(names = {"-v", "--version"}, versionHelp = true, description = "显示版本信息")
    private boolean versionRequested;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new CoreCC()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        try {
            // Load config from environment
            Config config = Config.fromEnv();

            // CLI arguments override environment variables
            if (model != null) config.setModel(model);
            if (baseUrl != null) config.setBaseUrl(baseUrl);
            if (apiKey != null) config.setApiKey(apiKey);

            // Check for API key
            if (config.getApiKey() == null || config.getApiKey().isEmpty()) {
                System.err.println("未找到 API 密钥。");
                System.err.println("请设置以下任一环境变量：OPENAI_API_KEY、DEEPSEEK_API_KEY 或 CORECC_API_KEY");
                System.err.println();
                System.err.println("示例：");
                System.err.println("  # OpenAI");
                System.err.println("  export OPENAI_API_KEY=your-api-key");
                System.err.println();
                System.err.println("  # DeepSeek");
                System.err.println("  export OPENAI_API_KEY=your-api-key OPENAI_BASE_URL=https://api.deepseek.com");
                System.err.println();
                System.err.println("  # Ollama（本地模型）");
                System.err.println("  export OPENAI_API_KEY=ollama OPENAI_BASE_URL=http://localhost:11434/v1 CORECC_MODEL=qwen2.5-coder");
                System.exit(1);
            }

            // Create CLI instance
            CLI cli = new CLI(config);

            // Resume session if specified
            if (resume != null) {
                var sessionData = com.corecc.session.SessionManager.loadSession(resume);
                if (sessionData != null) {
                    cli.getAgent().getMessages().addAll(sessionData.messages);
                    System.out.printf("已恢复会话：%s（模型：%s）%n", resume, sessionData.model);
                } else {
                    System.err.printf("未找到会话 '%s'。%n", resume);
                    System.exit(1);
                }
            }

            // Run in prompt mode or REPL mode
            if (prompt != null && !prompt.isEmpty()) {
                cli.runOnce(prompt);
            } else {
                cli.repl();
            }

        } catch (Exception e) {
            System.err.printf("错误：%s%n", e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}

package com.corecc.tools;

import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Shell 命令执行 —— Claude Code 最复杂的单个工具。
 *
 * 对应 Python 版的 corecc/tools/bash.py。
 *
 * CoreCC 精简为核心安全功能：
 * - 输出捕获与截断（保留头尾部分）
 * - 超时支持
 * - 危险命令检测（rm -rf /, mkfs, fork bomb 等）
 * - 工作目录跟踪（感知 cd 命令）
 */
public class BashTool implements Tool {
    // 跨命令跟踪工作目录
    private static String trackedCwd = null;

    // 危险命令模式
    private static final List<DangerousPattern> DANGEROUS_PATTERNS = List.of(
        new DangerousPattern("\\brm\\s+(-\\w*)?-r\\w*\\s+(/|~|\\$HOME)", "对用户主目录/根目录的递归删除"),
        new DangerousPattern("\\brm\\s+(-\\w*)?-rf\\s", "强制递归删除"),
        new DangerousPattern("\\bmkfs\\b", "格式化文件系统"),
        new DangerousPattern("\\bdd\\s+.*of=/dev/", "直接写入磁盘"),
        new DangerousPattern(">/dev/sd[a-z]", "覆写块设备"),
        new DangerousPattern("\\bchmod\\s+(-R\\s+)?777\\s+/", "对根目录执行 chmod 777"),
        new DangerousPattern(":\\(\\)\\s*\\{.*:\\|:.*\\}", "fork 炸弹"),
        new DangerousPattern("\\bcurl\\b.*\\|\\s*(sudo\\s+)?bash", "通过管道将 curl 输出传递给 bash"),
        new DangerousPattern("\\bwget\\b.*\\|\\s*(sudo\\s+)?bash", "通过管道将 wget 输出传递给 bash"),
        new DangerousPattern("\\bRemove-Item\\b.*\\s-(Recurse|r)\\b.*\\s-(Force|f)\\b", "PowerShell 强制递归删除"),
        new DangerousPattern("\\bRemove-Item\\b.*(?:C:\\\\|/|~|\\$HOME|%USERPROFILE%)", "PowerShell 删除高风险目录"),
        new DangerousPattern("\\brd\\s+/s\\s+/q\\s+(?:C:\\\\|%USERPROFILE%|\\\\)", "Windows 递归删除高风险目录"),
        new DangerousPattern("\\brmdir\\s+/s\\s+/q\\s+(?:C:\\\\|%USERPROFILE%|\\\\)", "Windows 递归删除高风险目录"),
        new DangerousPattern("\\bdel\\s+/[a-z]*[fsq][a-z]*\\s+(?:C:\\\\|%USERPROFILE%|\\\\)", "Windows 强制删除高风险路径"),
        new DangerousPattern("\\bformat\\s+[A-Z]:", "格式化 Windows 磁盘"),
        new DangerousPattern("\\b(iwr|irm|Invoke-WebRequest|Invoke-RestMethod)\\b.*\\|\\s*(iex|Invoke-Expression)", "下载脚本后立即执行"),
        new DangerousPattern("\\bSet-ExecutionPolicy\\b.*\\bUnrestricted\\b", "放宽 PowerShell 执行策略")
    );

    @Override
    public String getName() { return "bash"; }

    @Override
    public boolean isReadOnly() { return false; }

    @Override
    public String getDescription() {
        return "Execute a shell command. Returns stdout, stderr, and exit code. " +
               "Use this for running tests, installing packages, git operations, etc.";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "command", Map.of(
                    "type", "string",
                    "description", "要执行的 shell 命令"
                ),
                "timeout", Map.of(
                    "type", "integer",
                    "description", "超时时间，单位秒（默认 120）"
                )
            ),
            "required", List.of("command")
        );
    }

    @Override
    public String execute(Map<String, Object> args) {
        String command = (String) args.get("command");
        int timeout = args.containsKey("timeout") ? ((Number) args.get("timeout")).intValue() : estimateTimeout(command);

        // Safety check
        String warning = checkDangerous(command);
        if (warning != null) {
            return String.format("已拦截：%s\n命令：%s\n如确需执行，请修改命令使其更具体。", warning, command);
        }

        // Use tracked working directory
        String cwd = trackedCwd != null ? trackedCwd : System.getProperty("user.dir");

        try {
            // Determine shell based on OS
            String os = System.getProperty("os.name").toLowerCase();
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = new ProcessBuilder("cmd", "/c", command);
            } else {
                pb = new ProcessBuilder("sh", "-c", command);
            }

            pb.directory(new File(cwd));
            pb.redirectErrorStream(false);

            Process proc = pb.start();

            // Read stdout and stderr in separate threads
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();

            Thread stdoutThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdout.append(line).append("\n");
                    }
                } catch (IOException e) {
                    // Ignore
                }
            });

            Thread stderrThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stderr.append(line).append("\n");
                    }
                } catch (IOException e) {
                    // Ignore
                }
            });

            stdoutThread.start();
            stderrThread.start();

            boolean finished = proc.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return String.format("错误：命令执行超时（%d秒）", timeout);
            }

            stdoutThread.join(1000);
            stderrThread.join(1000);

            // Track cd command
            if (proc.exitValue() == 0) {
                updateCwd(command, cwd);
            }

            String out = stdout.toString().trim();
            String err = stderr.toString().trim();

            if (!err.isEmpty()) {
                out += "\n[标准错误]\n" + err;
            }
            if (proc.exitValue() != 0) {
                out += String.format("\n[退出码：%d]", proc.exitValue());
            }

            // Truncate long output
            if (out.length() > 15_000) {
                out = out.substring(0, 6000) +
                    String.format("\n\n... 已截断（共 %d 个字符）...\n\n", out.length()) +
                    out.substring(out.length() - 3000);
            }

            return out.isEmpty() ? "（无输出）" : out;
        } catch (Exception e) {
            return "执行命令出错：" + e.getMessage();
        }
    }

    /**
     * 根据命令类型智能估算超时时间。
     */
    private int estimateTimeout(String cmd) {
        String lower = cmd.toLowerCase().trim();
        // 编译任务
        if (lower.matches(".*(\\bmake\\b|\\bcmake\\b|\\bgradle\\b|\\bmvn\\b|\\bcargo build\\b|\\bgcc\\b|\\bg\\+\\b).*")) return 300;
        // 测试任务
        if (lower.matches(".*(\\bpytest\\b|\\btest\\b|\\bcargo test\\b|\\bgo test\\b|\\bjunit\\b).*")) return 300;
        // 依赖安装
        if (lower.matches(".*(\\bpip install\\b|\\bnpm install\\b|\\bapt-get\\b|\\byum\\b|\\bpip3 install\\b).*")) return 180;
        // 训练/长时间运行
        if (lower.matches(".*(\\btrain\\b|\\bfit\\b|\\bepoch\\b|\\bpython.*train).*")) return 600;
        // 网络操作
        if (lower.matches(".*(\\bgit clone\\b|\\bgit pull\\b|\\bcurl\\b|\\bwget\\b).*")) return 120;
        return 120; // 默认
    }

    /**
     * 检测命令是否危险。
     */
    private String checkDangerous(String cmd) {
        for (DangerousPattern dp : DANGEROUS_PATTERNS) {
            if (Pattern.compile(dp.pattern, Pattern.CASE_INSENSITIVE).matcher(cmd).find()) {
                if ((dp.pattern.equals("\\brm\\s+(-\\w*)?-rf\\s") ||
                     dp.pattern.equals("\\brm\\s+(-\\w*)?-r\\w*\\s+(/|~|\\$HOME)")) &&
                    isSafeLocalRecursiveRm(cmd)) {
                    continue;
                }
                return dp.reason;
            }
        }
        return null;
    }

    private boolean isSafeLocalRecursiveRm(String cmd) {
        String lower = cmd.toLowerCase(Locale.ROOT);
        if (!lower.matches(".*\\brm\\s+(-\\w*)?-r\\w*\\s+.*")) return false;
        if (lower.matches(".*\\brm\\s+(-\\w*)?-r\\w*\\s+(?:/|~|\\$home|/home|/root)(?:\\s|$).*")) return false;
        return lower.matches(".*\\brm\\s+(-\\w*)?-r\\w*\\s+((\\./)?[a-z0-9._-][^;&|]*|/app/[^;&|]+|/tmp/[^;&|]+).*");
    }

    /**
     * 跟踪 cd 命令引起的工作目录变化。
     */
    private void updateCwd(String command, String currentCwd) {
        String[] parts = command.split("&&");
        for (String part : parts) {
            part = part.trim();
            if (part.startsWith("cd ")) {
                String target = part.substring(3).trim().replaceAll("['\"]", "");
                if (!target.isEmpty()) {
                    File newDir = new File(currentCwd, target);
                    if (newDir.isDirectory()) {
                        trackedCwd = newDir.getAbsolutePath();
                    }
                }
            }
        }
    }

    private static class DangerousPattern {
        final String pattern;
        final String reason;

        DangerousPattern(String pattern, String reason) {
            this.pattern = pattern;
            this.reason = reason;
        }
    }
}

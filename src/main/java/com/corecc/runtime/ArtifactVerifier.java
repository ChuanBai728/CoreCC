package com.corecc.runtime;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 交付验证器 —— 在 agent 结束前强制检查产物是否满足约束。
 *
 * 从用户指令中提取验收标准，生成验证命令，判断结果，
 * 并在验证失败时生成 nudge 提示 agent 继续修复。
 */
public class ArtifactVerifier {

    // 文件大小约束：<5000 bytes、under 1MB、不超过 10KB
    private static final Pattern SIZE_LIMIT_RE = Pattern.compile(
        "(?:<|under|less than|不超过|不超过|no more than)\\s*(\\d+)\\s*(bytes|kb|mb|b)",
        Pattern.CASE_INSENSITIVE
    );

    // 可执行/编译约束
    private static final Pattern COMPILE_RE = Pattern.compile(
        "(?:compile|gcc|cc|g\\+\\+|make|cmake|javac|rustc)\\b",
        Pattern.CASE_INSENSITIVE
    );

    // 运行约束
    private static final Pattern RUN_RE = Pattern.compile(
        "(?:run it|execute|运行|执行|should output|should print)",
        Pattern.CASE_INSENSITIVE
    );

    // 必须包含内容约束
    private static final Pattern MUST_CONTAIN_RE = Pattern.compile(
        "(?:must contain|should include|需要包含|必须包含|must have)\\s+[\"']?([^\"']+)",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * 一条验证检查的结果。
     */
    public static class CheckResult {
        public final String description;
        public final String command;
        public final boolean passed;
        public final String output;

        public CheckResult(String description, String command, boolean passed, String output) {
            this.description = description;
            this.command = command;
            this.passed = passed;
            this.output = output;
        }
    }

    /**
     * 验证报告：所有检查的结果 + 是否全部通过 + nudge 提示。
     */
    public static class VerifyReport {
        private final List<CheckResult> results;
        private final String nudge;

        public VerifyReport(List<CheckResult> results, String nudge) {
            this.results = results;
            this.nudge = nudge;
        }

        public boolean allPassed() {
            return results.stream().allMatch(r -> r.passed);
        }

        public List<CheckResult> getResults() { return results; }
        public String getNudge() { return nudge; }
    }

    /**
     * 从用户指令中提取验证约束，返回需要执行的验证命令。
     *
     * @param instruction 用户指令
     * @param outputPaths 已识别的输出文件路径
     * @return 验证命令列表（description -> bash command）
     */
    public static List<String[]> buildChecks(String instruction, List<String> outputPaths) {
        List<String[]> checks = new ArrayList<>();
        String lower = instruction.toLowerCase();

        for (String path : outputPaths) {
            String quotedPath = shellQuote(path);
            // 1. 文件存在性
            checks.add(new String[]{
                "文件存在: " + path,
                String.format("test -f %s && echo 'EXISTS:%s' || echo 'MISSING:%s'", quotedPath, path, path)
            });

            // 2. 文件大小约束
            Long sizeLimit = extractSizeLimit(instruction);
            if (sizeLimit != null) {
                checks.add(new String[]{
                    String.format("文件大小 <%d bytes: %s", sizeLimit, path),
                    String.format("wc -c < %s | awk '{if($1>%d) print \"OVER:\"$1; else print \"OK:\"$1}'", quotedPath, sizeLimit)
                });
            }

            // 3. 可编译检查（仅对 .c/.cpp/.rs 文件）
            if (COMPILE_RE.matcher(lower).find() && isSourceFile(path)) {
                String compiler = guessCompiler(path);
                String outputBin = path.replaceAll("\\.[^.]+$", "");
                checks.add(new String[]{
                    "编译检查: " + path,
                    compileCommand(compiler, path, outputBin)
                });
            }

            // 4. 可运行检查
            if (RUN_RE.matcher(lower).find()) {
                checks.add(new String[]{
                    "可运行检查: " + path,
                    String.format("test -x %s && echo 'EXECUTABLE' || echo 'NOT_EXECUTABLE'", quotedPath)
                });
            }
        }

        return checks;
    }

    /**
     * 判断验证结果：解析 bash 输出，判断每条检查是否通过。
     *
     * @param checks 原始检查定义
     * @param results bash 执行结果（与 checks 一一对应）
     * @return 验证报告
     */
    public static VerifyReport evaluate(List<String[]> checks, List<String> results) {
        List<CheckResult> checkResults = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        for (int i = 0; i < checks.size(); i++) {
            String desc = checks.get(i)[0];
            String cmd = checks.get(i)[1];
            String output = i < results.size() ? results.get(i) : "(无结果)";

            boolean passed = false;
            if (output.contains("EXISTS:") || output.contains("OK:") ||
                output.contains("COMPILES") || output.contains("EXECUTABLE")) {
                passed = true;
            }

            checkResults.add(new CheckResult(desc, cmd, passed, output));
            if (!passed) {
                failures.add(desc + " -> " + output.trim());
            }
        }

        String nudge = failures.isEmpty() ? "" : buildNudge(failures);
        return new VerifyReport(checkResults, nudge);
    }

    /**
     * 判断验证是否全部通过（用于快速检查）。
     */
    public static boolean allPassed(String combinedOutput) {
        return !combinedOutput.contains("MISSING:") &&
               !combinedOutput.contains("OVER:") &&
               !combinedOutput.contains("FAIL") &&
               !combinedOutput.contains("NOT_EXECUTABLE");
    }

    /**
     * 从指令中提取文件大小上限。
     */
    private static Long extractSizeLimit(String instruction) {
        Matcher m = SIZE_LIMIT_RE.matcher(instruction);
        if (m.find()) {
            long num = Long.parseLong(m.group(1));
            String unit = m.group(2).toLowerCase();
            return switch (unit) {
                case "kb" -> num * 1024;
                case "mb" -> num * 1024 * 1024;
                default -> num; // bytes / b
            };
        }
        return null;
    }

    /**
     * 判断是否为源代码文件。
     */
    private static boolean isSourceFile(String path) {
        String lower = path.toLowerCase();
        return lower.endsWith(".c") || lower.endsWith(".cpp") || lower.endsWith(".cc") ||
               lower.endsWith(".rs") || lower.endsWith(".go") || lower.endsWith(".java");
    }

    /**
     * 根据文件扩展名猜测编译器。
     */
    private static String guessCompiler(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".c")) return "gcc -O3 -lm";
        if (lower.endsWith(".cpp") || lower.endsWith(".cc")) return "g++ -O3";
        if (lower.endsWith(".rs")) return "rustc";
        if (lower.endsWith(".go")) return "go build -o";
        if (lower.endsWith(".java")) return "javac";
        return "cc";
    }

    private static String compileCommand(String compiler, String path, String outputBin) {
        String quotedPath = shellQuote(path);
        String quotedOutput = shellQuote(outputBin);
        if ("go build -o".equals(compiler)) {
            return String.format("go build -o %s %s 2>&1 && echo 'COMPILES' || echo 'FAIL'", quotedOutput, quotedPath);
        }
        if ("javac".equals(compiler)) {
            return String.format("javac %s 2>&1 && echo 'COMPILES' || echo 'FAIL'", quotedPath);
        }
        return String.format("%s -o %s %s 2>&1 && echo 'COMPILES' || echo 'FAIL'", compiler, quotedOutput, quotedPath);
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    /**
     * 生成验证失败的 nudge 提示。
     */
    private static String buildNudge(List<String> failures) {
        StringBuilder sb = new StringBuilder();
        sb.append("[运行时提示]\n");
        sb.append("交付验证未通过，以下检查失败：\n");
        for (String f : failures) {
            sb.append("  - ").append(f).append("\n");
        }
        sb.append("\n请立即修复上述问题：\n");
        sb.append("1. 如果文件缺失，使用 write_file 创建\n");
        sb.append("2. 如果文件过大，精简代码或使用更紧凑的实现\n");
        sb.append("3. 如果编译失败，修复语法错误\n");
        sb.append("4. 修复后重新运行验证命令确认\n");
        return sb.toString();
    }
}

package com.corecc.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Verifies requested deliverables before the agent finishes. */
public final class ArtifactVerifier {
    private static final Pattern SIZE_LIMIT = Pattern.compile(
        "(?:<|under|less than|不超过|no more than)\\s*(\\d+)\\s*(bytes|kb|mb|b)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPILE_REQUEST = Pattern.compile(
        "(?:compile|gcc|cc|g\\+\\+|make|cmake|javac|rustc)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern RUN_REQUEST = Pattern.compile(
        "(?:run it|execute|运行|执行|should output|should print)", Pattern.CASE_INSENSITIVE);
    private static final Pattern REQUIRED_CONTENT = Pattern.compile(
        "(?:must contain|should include|需要包含|必须包含|must have)\\s+[\"']([^\"']+)[\"']",
        Pattern.CASE_INSENSITIVE);

    private ArtifactVerifier() {}

    public record CheckResult(String description, String command, boolean passed, String output) {}

    public static final class VerifyReport {
        private final List<CheckResult> results;

        private VerifyReport(List<CheckResult> results) {
            this.results = List.copyOf(results);
        }

        public boolean allPassed() { return results.stream().allMatch(CheckResult::passed); }
        public List<CheckResult> getResults() { return results; }
        public String getNudge() {
            if (allPassed()) return "";
            return buildNudge(results.stream()
                .filter(result -> !result.passed())
                .map(result -> result.description() + " -> " + result.output())
                .toList());
        }
    }

    /** Runs native file checks directly and delegates only compilation to the shell tool. */
    public static VerifyReport verify(String instruction, List<String> outputPaths,
                                      Function<String, String> commandRunner) {
        List<CheckResult> results = new ArrayList<>();
        Long sizeLimit = extractSizeLimit(instruction);
        String requiredContent = extractRequiredContent(instruction);
        boolean compileRequested = COMPILE_REQUEST.matcher(instruction).find();
        boolean runRequested = RUN_REQUEST.matcher(instruction).find();

        for (String outputPath : outputPaths) {
            Path path = Path.of(outputPath).toAbsolutePath().normalize();
            boolean exists = Files.isRegularFile(path);
            results.add(check("文件存在: " + outputPath, "java.nio.Files.isRegularFile",
                exists, exists ? "EXISTS" : "MISSING"));
            if (!exists) continue;

            if (sizeLimit != null) {
                try {
                    long size = Files.size(path);
                    results.add(check("文件大小 <= " + sizeLimit + " bytes: " + outputPath,
                        "java.nio.Files.size", size <= sizeLimit, size + " bytes"));
                } catch (IOException e) {
                    results.add(check("读取文件大小: " + outputPath, "java.nio.Files.size", false, e.getMessage()));
                }
            }

            if (requiredContent != null) {
                try {
                    boolean contains = Files.readString(path).contains(requiredContent);
                    results.add(check("包含指定内容: " + requiredContent, "java.nio.Files.readString",
                        contains, contains ? "FOUND" : "NOT_FOUND"));
                } catch (IOException e) {
                    results.add(check("读取文件内容: " + outputPath, "java.nio.Files.readString", false, e.getMessage()));
                }
            }

            if (compileRequested && isSourceFile(outputPath)) {
                String command = compileCommand(outputPath);
                String output = commandRunner.apply(command);
                results.add(check("编译检查: " + outputPath, command,
                    output.contains("COMPILES") && !output.contains("FAIL"), output.trim()));
            }

            if (runRequested) {
                boolean executable = Files.isExecutable(path);
                results.add(check("可运行检查: " + outputPath, "java.nio.Files.isExecutable",
                    executable, executable ? "EXECUTABLE" : "NOT_EXECUTABLE"));
            }
        }
        return new VerifyReport(results);
    }

    private static CheckResult check(String description, String command, boolean passed, String output) {
        return new CheckResult(description, command, passed, output == null ? "" : output);
    }

    private static Long extractSizeLimit(String instruction) {
        Matcher matcher = SIZE_LIMIT.matcher(instruction);
        if (!matcher.find()) return null;
        long value = Long.parseLong(matcher.group(1));
        return switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
            case "kb" -> value * 1024;
            case "mb" -> value * 1024 * 1024;
            default -> value;
        };
    }

    private static String extractRequiredContent(String instruction) {
        Matcher matcher = REQUIRED_CONTENT.matcher(instruction);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static boolean isSourceFile(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".c") || lower.endsWith(".cpp") || lower.endsWith(".cc") ||
            lower.endsWith(".rs") || lower.endsWith(".go") || lower.endsWith(".java");
    }

    private static String compileCommand(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        String input = shellQuote(Path.of(path).toAbsolutePath().normalize().toString());
        String output = shellQuote(Path.of(path.replaceAll("\\.[^.]+$", "")).toAbsolutePath().normalize().toString());
        String compile;
        if (lower.endsWith(".c")) compile = "gcc -O3 -o " + output + " " + input + " -lm";
        else if (lower.endsWith(".cpp") || lower.endsWith(".cc")) compile = "g++ -O3 -o " + output + " " + input;
        else if (lower.endsWith(".rs")) compile = "rustc -o " + output + " " + input;
        else if (lower.endsWith(".go")) compile = "go build -o " + output + " " + input;
        else compile = "javac " + input;
        return compile + " 2>&1 && echo COMPILES || echo FAIL";
    }

    private static String shellQuote(String value) {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String buildNudge(List<String> failures) {
        StringBuilder message = new StringBuilder("[运行时提示]\n交付验证未通过：\n");
        failures.forEach(failure -> message.append("  - ").append(failure).append('\n'));
        return message.append("请修复失败项并重新运行针对性验证后再结束任务。").toString();
    }
}

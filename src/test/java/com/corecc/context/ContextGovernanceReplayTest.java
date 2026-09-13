package com.corecc.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextGovernanceReplayTest {
    private static final Pattern CC_STATS = Pattern.compile("\\[CC]\\s+events=.*");
    private static final Pattern TOOL_CALL = Pattern.compile("^([A-Za-z_][\\w.-]*)\\((.*)\\)$", Pattern.DOTALL);
    private static final Pattern ABSOLUTE_PATH = Pattern.compile("(?<!\\w)/(?:[A-Za-z0-9._+@=-]+/)*[A-Za-z0-9._+@=-]+");
    private static final Pattern FILE_NAME = Pattern.compile("(?<![\\w./-])([A-Za-z0-9_+@=-][A-Za-z0-9._+@=-]*\\.[A-Za-z0-9]{1,8})(?![\\w./-])");
    private static final Pattern TOOL_CORE_VALUE = Pattern.compile("(?:file_path|command|path|cmd|name)=([^,\\n)]+)");
    private static final Set<String> COMMAND_WORDS = Set.of(
        "bash", "cat", "cc", "clang", "cmake", "coqc", "curl", "gcc", "git", "go",
        "javac", "java", "make", "node", "npm", "perl", "pip", "pytest", "python", "python3",
        "ruby", "rustc", "cargo", "sh", "tar", "unzip", "wget"
    );

    @TempDir
    Path tempDir;

    @Test
    void parserExtractsRequestAssistantTextAndToolCalls() throws Exception {
        Path log = tempDir.resolve("corecc.txt");
        Files.writeString(log, """
            WARNING: Unable to create a system terminal
            > Create /app/result.txt and run pytest.

            [CC] events=1 content=0 tools=1 reasoning=2

            > read_file(file_path=/app/input.txt)
            I will inspect the input first.[CC] events=2 content=31 tools=1 reasoning=3

            > bash(command=pytest -q)
            Done.[CC] events=3 content=5 tools=0 reasoning=1
            [CoreCC wrapper] java exit code: 0
            """, StandardCharsets.UTF_8);

        TaskTranscript transcript = ReplayParser.parse(log);

        assertEquals("Create /app/result.txt and run pytest.", transcript.request());
        assertTrue(transcript.messages().stream()
            .anyMatch(message -> "assistant".equals(message.get("role"))
                && String.valueOf(message.get("content")).contains("inspect the input")));
        assertEquals(List.of("read_file", "bash"), transcript.toolCalls().stream()
            .map(ToolInvocation::name)
            .collect(Collectors.toList()));
    }

    @Test
    void mustKeepIncludesSentinelPathsFilesAndRecentTools() {
        String request = """
            CORECC_REPLAY_TASK_ID=task-a
            CORECC_REPLAY_REQUEST_SHA=abc123

            Write /app/result.txt, produce data.comp, compile plus_comm.vo, then run coqc.
            """;
        List<ToolInvocation> tools = List.of(
            new ToolInvocation("read_file", "file_path=/app/input.txt"),
            new ToolInvocation("bash", "command=coqc plus_comm.v"),
            new ToolInvocation("write_file", "file_path=/app/result.txt")
        );

        List<String> mustKeep = MustKeep.extract(request, tools);

        assertTrue(mustKeep.contains("CORECC_REPLAY_TASK_ID=task-a"));
        assertTrue(mustKeep.contains("CORECC_REPLAY_REQUEST_SHA=abc123"));
        assertTrue(mustKeep.contains("/app/result.txt"));
        assertTrue(mustKeep.contains("data.comp"));
        assertTrue(mustKeep.contains("plus_comm.vo"));
        assertTrue(mustKeep.contains("coqc"));
        assertTrue(mustKeep.contains("read_file"));
        assertTrue(mustKeep.contains("write_file"));
    }

    @Test
    void rewardIndexReadsPassedFailedAndUnknownTasks() throws Exception {
        Path jobRoot = tempDir.resolve("job");
        Path passLog = jobRoot.resolve("task-pass").resolve("agent").resolve("corecc.txt");
        Path failLog = jobRoot.resolve("task-fail").resolve("agent").resolve("corecc.txt");
        Path unknownLog = jobRoot.resolve("task-unknown").resolve("agent").resolve("corecc.txt");
        Files.createDirectories(passLog.getParent());
        Files.createDirectories(failLog.getParent());
        Files.createDirectories(unknownLog.getParent());
        Files.writeString(jobRoot.resolve("result.json"), """
            {
              "stats": {
                "evals": {
                  "run": {
                    "reward_stats": {
                      "reward": {
                        "1.0": ["task-pass"],
                        "0.0": ["task-fail"]
                      }
                    }
                  }
                }
              }
            }
            """, StandardCharsets.UTF_8);

        assertEquals("passed", RewardIndex.lookup(passLog, "task-pass").status());
        assertEquals("failed", RewardIndex.lookup(failLog, "task-fail").status());
        assertEquals("unknown", RewardIndex.lookup(unknownLog, "task-unknown").status());
    }

    @Test
    void rewardFilterKeepsOnlyPassedNonSuperlargeCases() {
        List<ReplayCase> cases = List.of(
            replayCaseForTest("passed-small", 399_999, "1.0", "passed"),
            replayCaseForTest("passed-superlarge", 400_000, "1.0", "passed"),
            replayCaseForTest("failed-small", 120_000, "0.0", "failed"),
            replayCaseForTest("unknown-small", 100_000, "", "unknown")
        );

        List<ReplayCase> filtered = filterCases(cases, "passed", 400_000);

        assertEquals(1, filtered.size());
        assertEquals("passed-small", filtered.get(0).taskId());
    }

    @Test
    void replayBenchmarkFromJobs() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("corecc.contextReplay.enabled"),
            "Set -Dcorecc.contextReplay.enabled=true to run Terminal-Bench context replay.");

        Path jobsDir = Path.of(System.getProperty("corecc.contextReplay.jobsDir", "jobs"));
        int maxTokens = Integer.getInteger("corecc.contextReplay.maxTokens", 8192);
        int minTokens = Integer.getInteger("corecc.contextReplay.minTokens", (int) (maxTokens * 0.85));
        List<Integer> sizes = parseSizes(System.getProperty("corecc.contextReplay.sizes", "12,50,100"));
        String rewardFilter = parseRewardFilter(System.getProperty("corecc.contextReplay.rewardFilter", "all"));
        int maxBeforeTokens = Integer.getInteger("corecc.contextReplay.maxBeforeTokens", Integer.MAX_VALUE);
        Path outDir = Path.of(System.getProperty("corecc.contextReplay.outputDir", "target/context-replay"));

        List<TaskTranscript> transcripts = ReplayParser.loadTranscripts(jobsDir);
        Assumptions.assumeTrue(!transcripts.isEmpty(), "No usable CoreCC agent logs found under " + jobsDir);

        int largestSize = sizes.stream().mapToInt(Integer::intValue).max().orElse(0);
        int candidatePoolSize = Integer.getInteger("corecc.contextReplay.candidatePoolSize",
            Math.max(largestSize, 100));
        List<ReplayCase> allCases = ReplayCaseBuilder.build(transcripts, maxTokens, minTokens).stream()
            .map(replayCase -> replayCase.withReward(RewardIndex.lookup(replayCase.logPath(), replayCase.taskId())))
            .collect(Collectors.toList());
        List<ReplayCase> candidateCases = allCases.stream()
            .sorted(Comparator.comparingInt(ReplayCase::beforeTokens).reversed()
                .thenComparing(replayCase -> replayCase.logPath().toString()))
            .limit(candidatePoolSize)
            .collect(Collectors.toList());
        List<ReplayCase> cases = filterCases(candidateCases, rewardFilter, maxBeforeTokens);
        assertTrue(cases.size() >= largestSize,
            "Need at least " + largestSize + " replay cases, found " + cases.size());

        List<CaseResult> results = new ArrayList<>();
        for (ReplayCase replayCase : cases.subList(0, largestSize)) {
            results.add(evaluateCase(replayCase, maxTokens));
        }

        Map<String, Object> report = new LinkedHashMap<>();
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (int size : sizes) {
            List<CaseResult> slice = results.subList(0, size);
            Summary summary = Summary.from(size, slice);
            summaries.add(summary.toMap());
            System.out.printf(Locale.ROOT,
                "N=%d avg_before=%.2f avg_after=%.2f compression=%.2f%% must_keep=%.2f%% current_request=%.2f%%%n",
                size,
                summary.avgPromptBefore(),
                summary.avgPromptAfter(),
                summary.overallCompressionPct(),
                summary.mustKeepPreservationPct(),
                summary.currentRequestPreservedPct());

            assertEquals(size, summary.sampleCount());
            assertTrue(summary.avgPromptAfter() <= summary.avgPromptBefore());
            assertTrue(summary.mustKeepPreservationPct() >= 0 && summary.mustKeepPreservationPct() <= 100);
        }

        report.put("max_tokens", maxTokens);
        report.put("min_tokens", minTokens);
        report.put("jobs_dir", jobsDir.toString());
        report.put("reward_filter", rewardFilter);
        report.put("max_before_tokens", maxBeforeTokens);
        report.put("candidate_pool_size", candidatePoolSize);
        report.put("eligible_cases", cases.size());
        report.put("reward_status_counts", rewardStatusCounts(cases));
        report.put("summaries", summaries);
        report.put("cases", results.stream().map(CaseResult::toMap).collect(Collectors.toList()));

        Files.createDirectories(outDir);
        writeJson(outDir.resolve("report.json"), report);
        writeCsv(outDir.resolve("cases.csv"), results);
        writeMarkdown(outDir.resolve("summary.md"), summaries);

        assertTrue(Files.exists(outDir.resolve("report.json")));
        assertTrue(Files.exists(outDir.resolve("cases.csv")));
        assertTrue(Files.exists(outDir.resolve("summary.md")));
    }

    private static CaseResult evaluateCase(ReplayCase replayCase, int maxTokens) {
        List<Map<String, Object>> messages = deepCopyMessages(replayCase.messages());
        ContextManager manager = new ContextManager(maxTokens);
        CompressionReport compression = manager.maybeCompress(messages, null, "replay", false);
        int after = ContextManager.estimateTokens(messages);
        String flattened = flatten(messages);

        List<String> missing = replayCase.mustKeep().stream()
            .filter(item -> !containsPreserved(flattened, item))
            .collect(Collectors.toList());
        int preserved = replayCase.mustKeep().size() - missing.size();
        boolean currentRequestPreserved =
            containsPreserved(flattened, replayCase.taskSentinel())
                && containsPreserved(flattened, replayCase.requestSentinel());

        return new CaseResult(
            replayCase.taskId(),
            replayCase.logPath().toString(),
            replayCase.reward(),
            replayCase.rewardStatus(),
            replayCase.beforeTokens(),
            after,
            Math.max(0, replayCase.beforeTokens() - after),
            percent(replayCase.beforeTokens() - after, replayCase.beforeTokens()),
            compression.getActions(),
            replayCase.mustKeep().size(),
            preserved,
            missing,
            currentRequestPreserved
        );
    }

    private static List<Map<String, Object>> deepCopyMessages(List<Map<String, Object>> messages) {
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Map<String, Object> message : messages) {
            copy.add(new LinkedHashMap<>(message));
        }
        return copy;
    }

    private static boolean containsPreserved(String text, String item) {
        return text.toLowerCase(Locale.ROOT).contains(item.toLowerCase(Locale.ROOT));
    }

    private static String flatten(List<Map<String, Object>> messages) {
        StringBuilder builder = new StringBuilder();
        for (Map<String, Object> message : messages) {
            Object content = message.get("content");
            if (content != null) {
                builder.append(content).append('\n');
            }
            Object toolCalls = message.get("tool_calls");
            if (toolCalls != null) {
                builder.append(toolCalls).append('\n');
            }
        }
        return builder.toString();
    }

    private static double percent(double numerator, double denominator) {
        return denominator == 0 ? 0 : numerator / denominator * 100.0;
    }

    private static List<Integer> parseSizes(String value) {
        return Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(part -> !part.isEmpty())
            .map(Integer::parseInt)
            .sorted()
            .collect(Collectors.toList());
    }

    private static String parseRewardFilter(String value) {
        String normalized = value == null ? "all" : value.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("all", "passed", "failed").contains(normalized)) {
            throw new IllegalArgumentException("Unsupported corecc.contextReplay.rewardFilter=" + value
                + "; expected all, passed, or failed");
        }
        return normalized;
    }

    private static List<ReplayCase> filterCases(List<ReplayCase> cases, String rewardFilter, int maxBeforeTokens) {
        String normalized = parseRewardFilter(rewardFilter);
        return cases.stream()
            .filter(replayCase -> replayCase.beforeTokens() < maxBeforeTokens)
            .filter(replayCase -> "all".equals(normalized) || normalized.equals(replayCase.rewardStatus()))
            .sorted(Comparator.comparingInt(ReplayCase::beforeTokens).reversed()
                .thenComparing(replayCase -> replayCase.logPath().toString()))
            .collect(Collectors.toList());
    }

    private static Map<String, Integer> rewardStatusCounts(List<ReplayCase> cases) {
        Map<String, Integer> counts = new TreeMap<>();
        for (ReplayCase replayCase : cases) {
            counts.merge(replayCase.rewardStatus(), 1, Integer::sum);
        }
        return counts;
    }

    private static ReplayCase replayCaseForTest(String taskId, int beforeTokens, String reward, String rewardStatus) {
        return new ReplayCase(
            taskId,
            Path.of("jobs", "job", taskId, "agent", "corecc.txt"),
            List.of(ReplayParser.message("user", "CORECC_REPLAY_TASK_ID=" + taskId)),
            List.of("CORECC_REPLAY_TASK_ID=" + taskId),
            "CORECC_REPLAY_TASK_ID=" + taskId,
            "CORECC_REPLAY_REQUEST_SHA=test",
            beforeTokens
        ).withReward(new RewardInfo(reward, rewardStatus));
    }

    private static void writeJson(Path path, Map<String, Object> report) throws IOException {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(path.toFile(), report);
    }

    private static void writeCsv(Path path, List<CaseResult> results) throws IOException {
        StringBuilder csv = new StringBuilder();
        csv.append("task_id,log_path,reward,reward_status,before_tokens,after_tokens,tokens_saved,compression_pct,actions,must_keep_total,must_keep_preserved,must_keep_missing,current_request_preserved\n");
        for (CaseResult result : results) {
            csv.append(csv(result.taskId())).append(',')
                .append(csv(result.logPath())).append(',')
                .append(csv(result.reward())).append(',')
                .append(csv(result.rewardStatus())).append(',')
                .append(result.beforeTokens()).append(',')
                .append(result.afterTokens()).append(',')
                .append(result.tokensSaved()).append(',')
                .append(String.format(Locale.ROOT, "%.4f", result.compressionPct())).append(',')
                .append(csv(String.join("|", result.actions()))).append(',')
                .append(result.mustKeepTotal()).append(',')
                .append(result.mustKeepPreserved()).append(',')
                .append(csv(String.join("|", result.mustKeepMissing()))).append(',')
                .append(result.currentRequestPreserved()).append('\n');
        }
        Files.writeString(path, csv.toString(), StandardCharsets.UTF_8);
    }

    private static String csv(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static void writeMarkdown(Path path, List<Map<String, Object>> summaries) throws IOException {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# Context Replay Summary\n\n");
        markdown.append("| N | Avg Before | Avg After | Overall Compression | Must Keep | Current Request |\n");
        markdown.append("|---:|---:|---:|---:|---:|---:|\n");
        for (Map<String, Object> summary : summaries) {
            markdown.append("| ")
                .append(summary.get("sample_count")).append(" | ")
                .append(summary.get("avg_prompt_before")).append(" | ")
                .append(summary.get("avg_prompt_after")).append(" | ")
                .append(summary.get("overall_compression_pct")).append("% | ")
                .append(summary.get("must_keep_preservation_pct")).append("% | ")
                .append(summary.get("current_request_preserved_pct")).append("% |\n");
        }
        Files.writeString(path, markdown.toString(), StandardCharsets.UTF_8);
    }

    static final class RewardIndex {
        private static final ObjectMapper MAPPER = new ObjectMapper();
        private static final RewardInfo UNKNOWN = new RewardInfo("", "unknown");
        private static final Map<Path, Map<String, RewardInfo>> CACHE = new HashMap<>();

        private RewardIndex() {
        }

        static RewardInfo lookup(Path logPath, String taskId) {
            Path jobRoot = findJobRoot(logPath);
            if (jobRoot == null) {
                return UNKNOWN;
            }
            return CACHE.computeIfAbsent(jobRoot, RewardIndex::load).getOrDefault(taskId, UNKNOWN);
        }

        private static Path findJobRoot(Path logPath) {
            Path current = logPath.toAbsolutePath().getParent();
            while (current != null) {
                if (Files.exists(current.resolve("result.json"))) {
                    return current;
                }
                current = current.getParent();
            }
            return null;
        }

        private static Map<String, RewardInfo> load(Path jobRoot) {
            Path result = jobRoot.resolve("result.json");
            if (!Files.isRegularFile(result)) {
                return Map.of();
            }
            try {
                Map<String, RewardInfo> rewards = new HashMap<>();
                JsonNode root = MAPPER.readTree(result.toFile());
                RewardInfo directReward = directReward(root);
                if (!"unknown".equals(directReward.status())) {
                    for (String taskId : directRewardTaskIds(root, jobRoot)) {
                        rewards.put(taskId, directReward);
                    }
                }
                collectRewardBuckets(root, rewards);
                return rewards;
            } catch (Exception ignored) {
                return Map.of();
            }
        }

        private static RewardInfo directReward(JsonNode root) {
            JsonNode reward = root.path("verifier_result").path("rewards").path("reward");
            if (reward.isMissingNode() || reward.isNull()) {
                reward = root.path("reward_stats").path("reward");
            }
            if (!reward.isNumber()) {
                return UNKNOWN;
            }
            double value = reward.asDouble();
            String text = Double.toString(value);
            if (Double.compare(value, 1.0) == 0) {
                return new RewardInfo(text, "passed");
            }
            if (Double.compare(value, 0.0) == 0) {
                return new RewardInfo(text, "failed");
            }
            return UNKNOWN;
        }

        private static List<String> directRewardTaskIds(JsonNode root, Path jobRoot) {
            LinkedHashSet<String> taskIds = new LinkedHashSet<>();
            taskIds.add(jobRoot.getFileName().toString());
            addText(taskIds, root.path("trial_name"));
            addText(taskIds, root.path("task_name"));
            return new ArrayList<>(taskIds);
        }

        private static void addText(Set<String> values, JsonNode node) {
            if (node.isTextual() && !node.asText().isBlank()) {
                values.add(node.asText());
            }
        }

        private static void collectRewardBuckets(JsonNode node, Map<String, RewardInfo> rewards) {
            if (node == null || node.isMissingNode() || node.isNull()) {
                return;
            }
            if (node.isObject()) {
                addRewardBucket(node.get("1.0"), new RewardInfo("1.0", "passed"), rewards);
                addRewardBucket(node.get("1"), new RewardInfo("1", "passed"), rewards);
                addRewardBucket(node.get("0.0"), new RewardInfo("0.0", "failed"), rewards);
                addRewardBucket(node.get("0"), new RewardInfo("0", "failed"), rewards);
                node.fields().forEachRemaining(field -> collectRewardBuckets(field.getValue(), rewards));
            } else if (node.isArray()) {
                node.forEach(child -> collectRewardBuckets(child, rewards));
            }
        }

        private static void addRewardBucket(JsonNode bucket, RewardInfo rewardInfo,
                                            Map<String, RewardInfo> rewards) {
            if (bucket == null || !bucket.isArray()) {
                return;
            }
            bucket.forEach(task -> {
                if (task.isTextual()) {
                    rewards.put(task.asText(), rewardInfo);
                }
            });
        }
    }

    static final class ReplayParser {
        private ReplayParser() {
        }

        static List<TaskTranscript> loadTranscripts(Path jobsDir) throws IOException {
            if (!Files.isDirectory(jobsDir)) {
                return List.of();
            }

            try (Stream<Path> paths = Files.walk(jobsDir)) {
                return paths
                    .filter(path -> path.getFileName().toString().equals("corecc.txt"))
                    .sorted(Comparator.comparing(Path::toString))
                    .map(ReplayParser::parseQuietly)
                    .filter(Objects2::nonNull)
                    .filter(TaskTranscript::usable)
                    .collect(Collectors.toList());
            }
        }

        private static TaskTranscript parseQuietly(Path path) {
            try {
                return parse(path);
            } catch (Exception ignored) {
                return null;
            }
        }

        static TaskTranscript parse(Path path) throws IOException {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            List<Map<String, Object>> messages = new ArrayList<>();
            List<ToolInvocation> toolCalls = new ArrayList<>();
            StringBuilder block = null;
            StringBuilder assistant = new StringBuilder();
            String request = null;

            for (String line : lines) {
                if (line.startsWith("[CoreCC wrapper]")) {
                    flushAssistant(assistant, messages);
                    if (block != null) {
                        request = flushBlock(block, messages, toolCalls, request);
                        block = null;
                    }
                    continue;
                }

                if (line.startsWith("> ")) {
                    flushAssistant(assistant, messages);
                    if (block != null) {
                        request = flushBlock(block, messages, toolCalls, request);
                    }
                    block = new StringBuilder(line.substring(2));
                    continue;
                }

                Matcher stats = CC_STATS.matcher(line);
                if (stats.find()) {
                    String beforeStats = line.substring(0, stats.start()).trim();
                    if (block != null) {
                        request = flushBlock(block, messages, toolCalls, request);
                        block = null;
                    }
                    if (!beforeStats.isEmpty()) {
                        assistant.append(beforeStats).append('\n');
                    }
                    flushAssistant(assistant, messages);
                    continue;
                }

                if (block != null) {
                    block.append('\n').append(line);
                } else if (looksLikeUserVisibleText(line)) {
                    assistant.append(line).append('\n');
                }
            }

            flushAssistant(assistant, messages);
            if (block != null) {
                request = flushBlock(block, messages, toolCalls, request);
            }

            String taskId = inferTaskId(path);
            return new TaskTranscript(taskId, path, request == null ? "" : request.strip(), messages, toolCalls);
        }

        private static boolean looksLikeUserVisibleText(String line) {
            if (line.isBlank()) return false;
            if (line.startsWith("Jun ") || line.startsWith("WARNING:") || line.startsWith("INFO:")) return false;
            if (line.startsWith("\tat ")) return false;
            return true;
        }

        private static void flushAssistant(StringBuilder assistant, List<Map<String, Object>> messages) {
            String content = assistant.toString().strip();
            if (!content.isEmpty()) {
                messages.add(message("assistant", content));
            }
            assistant.setLength(0);
        }

        private static void flushBlock(StringBuilder block, List<Map<String, Object>> messages,
                                       List<ToolInvocation> toolCalls) {
            flushBlock(block, messages, toolCalls, null);
        }

        private static String flushBlock(StringBuilder block, List<Map<String, Object>> messages,
                                         List<ToolInvocation> toolCalls, String existingRequest) {
            String content = block.toString().strip();
            if (content.isEmpty()) {
                return existingRequest;
            }

            Matcher tool = TOOL_CALL.matcher(content);
            if (tool.matches()) {
                String name = tool.group(1);
                String arguments = tool.group(2).strip();
                Map<String, Object> assistant = message("assistant", "");
                assistant.put("tool_calls", List.of(Map.of("name", name, "arguments", arguments)));
                messages.add(assistant);
                messages.add(message("tool", "[replay tool result unavailable] " + name + "(" + arguments + ")"));
                toolCalls.add(new ToolInvocation(name, arguments));
                return existingRequest;
            }

            messages.add(message("user", content));
            return existingRequest == null ? content : existingRequest;
        }

        private static Map<String, Object> message(String role, String content) {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", role);
            message.put("content", content);
            return message;
        }

        private static String inferTaskId(Path path) {
            Path parent = path.getParent();
            if (parent != null && parent.getParent() != null) {
                return parent.getParent().getFileName().toString();
            }
            return path.getFileName().toString();
        }
    }

    static final class ReplayCaseBuilder {
        private ReplayCaseBuilder() {
        }

        static List<ReplayCase> build(List<TaskTranscript> transcripts, int maxTokens, int minTokens) {
            List<TaskTranscript> sorted = transcripts.stream()
                .sorted(Comparator.comparingInt(TaskTranscript::transcriptTokens).reversed()
                    .thenComparing(task -> task.logPath().toString()))
                .collect(Collectors.toList());
            List<ReplayCase> cases = new ArrayList<>();

            for (int i = 0; i < sorted.size(); i++) {
                TaskTranscript current = sorted.get(i);
                List<Map<String, Object>> messages = new ArrayList<>();
                int historyIndex = i + 1;
                while (ContextManager.estimateTokens(messages) < minTokens && messages.size() < 250) {
                    TaskTranscript history = sorted.get(historyIndex % sorted.size());
                    if (!history.taskId().equals(current.taskId())) {
                        messages.addAll(deepCopyMessages(history.messages()));
                    }
                    historyIndex++;
                    if (historyIndex - i > sorted.size() * 2) {
                        break;
                    }
                }

                String requestSha = sha256(current.request()).substring(0, 12);
                String taskSentinel = "CORECC_REPLAY_TASK_ID=" + current.taskId();
                String requestSentinel = "CORECC_REPLAY_REQUEST_SHA=" + requestSha;
                String currentRequest = taskSentinel + "\n" + requestSentinel + "\n\n" + current.request();
                messages.add(ReplayParser.message("user", currentRequest));

                List<ToolInvocation> recentTools = recentTools(messages, 3);
                List<String> mustKeep = MustKeep.extract(currentRequest, recentTools);
                int before = ContextManager.estimateTokens(messages);
                if (before >= minTokens) {
                    cases.add(new ReplayCase(
                        current.taskId(),
                        current.logPath(),
                        messages,
                        mustKeep,
                        taskSentinel,
                        requestSentinel,
                        before
                    ));
                }
            }

            return cases.stream()
                .sorted(Comparator.comparingInt(ReplayCase::beforeTokens).reversed()
                    .thenComparing(replayCase -> replayCase.logPath().toString()))
                .collect(Collectors.toList());
        }

        private static List<ToolInvocation> recentTools(List<Map<String, Object>> messages, int limit) {
            List<ToolInvocation> tools = new ArrayList<>();
            for (Map<String, Object> message : messages) {
                if (!"tool".equals(message.get("role"))) continue;
                String content = String.valueOf(message.getOrDefault("content", ""));
                Matcher matcher = Pattern.compile("\\] ([A-Za-z_][\\w.-]*)\\((.*)\\)").matcher(content);
                if (matcher.find()) {
                    tools.add(new ToolInvocation(matcher.group(1), matcher.group(2)));
                }
            }
            int start = Math.max(0, tools.size() - limit);
            return tools.subList(start, tools.size());
        }
    }

    static final class MustKeep {
        private MustKeep() {
        }

        static List<String> extract(String currentRequest, List<ToolInvocation> recentTools) {
            LinkedHashSet<String> items = new LinkedHashSet<>();
            for (String line : currentRequest.split("\\R")) {
                if (line.startsWith("CORECC_REPLAY_")) {
                    items.add(line.strip());
                }
            }
            addMatches(items, ABSOLUTE_PATH, currentRequest);
            addMatches(items, FILE_NAME, currentRequest);
            for (String word : currentRequest.split("[^A-Za-z0-9_+.-]+")) {
                String normalized = word.replaceAll("^[^A-Za-z0-9]+|[^A-Za-z0-9]+$", "");
                if (COMMAND_WORDS.contains(normalized.toLowerCase(Locale.ROOT))) {
                    items.add(normalized);
                }
            }
            for (ToolInvocation tool : recentTools) {
                items.add(tool.name());
                Matcher matcher = TOOL_CORE_VALUE.matcher(tool.arguments());
                while (matcher.find()) {
                    String value = matcher.group(1).strip();
                    if (value.length() > 80) {
                        value = value.substring(0, 80);
                    }
                    if (!value.isBlank()) {
                        items.add(value);
                    }
                }
            }
            return new ArrayList<>(items);
        }

        private static void addMatches(Set<String> items, Pattern pattern, String text) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                items.add(matcher.groupCount() >= 1 && matcher.group(1) != null ? matcher.group(1) : matcher.group());
            }
        }
    }

    record ToolInvocation(String name, String arguments) {
    }

    record TaskTranscript(String taskId, Path logPath, String request, List<Map<String, Object>> messages,
                          List<ToolInvocation> toolCalls) {
        boolean usable() {
            return request != null
                && request.length() >= 20
                && !request.contains("Exception")
                && !messages.isEmpty()
                && transcriptTokens() > 200;
        }

        int transcriptTokens() {
            return ContextManager.estimateTokens(messages);
        }
    }

    record RewardInfo(String reward, String status) {
    }

    record ReplayCase(String taskId, Path logPath, List<Map<String, Object>> messages, List<String> mustKeep,
                      String taskSentinel, String requestSentinel, int beforeTokens, String reward,
                      String rewardStatus) {
        ReplayCase(String taskId, Path logPath, List<Map<String, Object>> messages, List<String> mustKeep,
                   String taskSentinel, String requestSentinel, int beforeTokens) {
            this(taskId, logPath, messages, mustKeep, taskSentinel, requestSentinel, beforeTokens, "", "unknown");
        }

        ReplayCase withReward(RewardInfo rewardInfo) {
            return new ReplayCase(
                taskId,
                logPath,
                messages,
                mustKeep,
                taskSentinel,
                requestSentinel,
                beforeTokens,
                rewardInfo.reward(),
                rewardInfo.status()
            );
        }
    }

    record CaseResult(String taskId, String logPath, String reward, String rewardStatus, int beforeTokens,
                      int afterTokens, int tokensSaved, double compressionPct, List<String> actions,
                      int mustKeepTotal, int mustKeepPreserved, List<String> mustKeepMissing,
                      boolean currentRequestPreserved) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("task_id", taskId);
            map.put("log_path", logPath);
            map.put("reward", reward);
            map.put("reward_status", rewardStatus);
            map.put("before_tokens", beforeTokens);
            map.put("after_tokens", afterTokens);
            map.put("tokens_saved", tokensSaved);
            map.put("compression_pct", round(compressionPct));
            map.put("actions", actions);
            map.put("must_keep_total", mustKeepTotal);
            map.put("must_keep_preserved", mustKeepPreserved);
            map.put("must_keep_missing", mustKeepMissing);
            map.put("current_request_preserved", currentRequestPreserved);
            return map;
        }
    }

    record Summary(int sampleCount, double avgPromptBefore, double avgPromptAfter, double overallCompressionPct,
                   double avgCaseCompressionPct, double maxCompressionPct, double p50CompressionPct,
                   double p90CompressionPct, double mustKeepPreservationPct, double currentRequestPreservedPct,
                   Map<String, Integer> actionCounts) {
        static Summary from(int sampleCount, List<CaseResult> results) {
            double beforeSum = results.stream().mapToInt(CaseResult::beforeTokens).sum();
            double afterSum = results.stream().mapToInt(CaseResult::afterTokens).sum();
            List<Double> compression = results.stream().map(CaseResult::compressionPct).sorted().toList();
            int mustTotal = results.stream().mapToInt(CaseResult::mustKeepTotal).sum();
            int mustPreserved = results.stream().mapToInt(CaseResult::mustKeepPreserved).sum();
            Map<String, Integer> actions = new TreeMap<>();
            for (CaseResult result : results) {
                for (String action : result.actions()) {
                    actions.merge(action, 1, Integer::sum);
                }
            }
            return new Summary(
                sampleCount,
                beforeSum / results.size(),
                afterSum / results.size(),
                percent(beforeSum - afterSum, beforeSum),
                compression.stream().mapToDouble(Double::doubleValue).average().orElse(0),
                compression.stream().mapToDouble(Double::doubleValue).max().orElse(0),
                percentile(compression, 50),
                percentile(compression, 90),
                percent(mustPreserved, mustTotal),
                percent(results.stream().filter(CaseResult::currentRequestPreserved).count(), results.size()),
                actions
            );
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("sample_count", sampleCount);
            map.put("avg_prompt_before", round(avgPromptBefore));
            map.put("avg_prompt_after", round(avgPromptAfter));
            map.put("overall_compression_pct", round(overallCompressionPct));
            map.put("avg_case_compression_pct", round(avgCaseCompressionPct));
            map.put("max_compression_pct", round(maxCompressionPct));
            map.put("p50_compression_pct", round(p50CompressionPct));
            map.put("p90_compression_pct", round(p90CompressionPct));
            map.put("must_keep_preservation_pct", round(mustKeepPreservationPct));
            map.put("current_request_preserved_pct", round(currentRequestPreservedPct));
            map.put("action_counts", actionCounts);
            return map;
        }
    }

    static final class Objects2 {
        private Objects2() {
        }

        static boolean nonNull(Object object) {
            return object != null;
        }
    }

    private static double percentile(List<Double> sorted, double percentile) {
        if (sorted.isEmpty()) return 0;
        double index = (sorted.size() - 1) * percentile / 100.0;
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);
        if (lower == upper) {
            return sorted.get(lower);
        }
        return sorted.get(lower) * (upper - index) + sorted.get(upper) * (index - lower);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

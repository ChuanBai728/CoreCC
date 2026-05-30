package com.corecc.runtime;

import com.corecc.context.CompressionReport;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级运行时统计。
 *
 * 对应 Python 版的 corecc/runtime.py 中的 RuntimeStats dataclass。
 */
public class RuntimeStats {
    private int toolCalls = 0;
    private int toolSuccesses = 0;
    private int toolFailures = 0;
    private int compressions = 0;
    private int compactedChars = 0;
    private int tokensSaved = 0;
    private int toolResultsPersisted = 0;
    private int readDedupHits = 0;
    private Map<String, Integer> compressionActions = new ConcurrentHashMap<>();
    private Map<String, Integer> byTool = new ConcurrentHashMap<>();
    private Map<String, Integer> errorCategories = new ConcurrentHashMap<>();
    private String lastTool = "";
    private String lastErrorCategory = "";
    private String lastNudge = "";
    private final Object lock = new Object();

    /**
     * 记录工具执行结果。
     */
    public void recordTool(String toolName, ToolReview review) {
        synchronized (lock) {
            toolCalls++;
            byTool.merge(toolName, 1, Integer::sum);
            compactedChars += review.getCompactedChars();
            lastTool = toolName;

            if (review.isFailed()) {
                toolFailures++;
                String category = review.getCategory() != null ? review.getCategory() : "unknown";
                errorCategories.merge(category, 1, Integer::sum);
                lastErrorCategory = category;
                lastNudge = review.getNudge() != null ? review.getNudge() : RuntimeReview.NUDGES.get("unknown");
            } else {
                toolSuccesses++;
            }
        }
    }

    /**
     * 记录上下文压缩。
     */
    public void recordCompression(CompressionReport report) {
        synchronized (lock) {
            compressions++;
            if (report == null) return;

            tokensSaved += report.getTokensSaved();
            for (String action : report.getActions()) {
                compressionActions.merge(action, 1, Integer::sum);
                if ("persist_tool_result".equals(action)) {
                    toolResultsPersisted++;
                } else if ("read_dedup".equals(action)) {
                    readDedupHits++;
                }
            }
        }
    }

    // Getters
    public int getToolCalls() { return toolCalls; }
    public int getToolSuccesses() { return toolSuccesses; }
    public int getToolFailures() { return toolFailures; }
    public int getCompressions() { return compressions; }
    public int getCompactedChars() { return compactedChars; }
    public int getTokensSaved() { return tokensSaved; }
    public int getToolResultsPersisted() { return toolResultsPersisted; }
    public int getReadDedupHits() { return readDedupHits; }
    public Map<String, Integer> getCompressionActions() { return compressionActions; }
    public Map<String, Integer> getByTool() { return byTool; }
    public Map<String, Integer> getErrorCategories() { return errorCategories; }
    public String getLastTool() { return lastTool; }
    public String getLastErrorCategory() { return lastErrorCategory; }
    public String getLastNudge() { return lastNudge; }
}

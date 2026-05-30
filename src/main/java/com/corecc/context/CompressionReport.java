package com.corecc.context;

import java.util.*;

/**
 * 一次上下文优化/压缩的可观测结果。
 *
 * 对应 Python 版的 corecc/context.py 中的 CompressionReport dataclass。
 */
public class CompressionReport {
    private boolean changed;
    private int tokensBefore;
    private int tokensAfter;
    private int tokensSaved;
    private List<String> actions;
    private String reason;

    public CompressionReport() {
        this.changed = false;
        this.tokensBefore = 0;
        this.tokensAfter = 0;
        this.tokensSaved = 0;
        this.actions = new ArrayList<>();
        this.reason = "";
    }

    public CompressionReport(boolean changed, int tokensBefore, int tokensAfter,
                            int tokensSaved, List<String> actions, String reason) {
        this.changed = changed;
        this.tokensBefore = tokensBefore;
        this.tokensAfter = tokensAfter;
        this.tokensSaved = tokensSaved;
        this.actions = actions != null ? actions : new ArrayList<>();
        this.reason = reason != null ? reason : "";
    }

    /**
     * 创建空报告（无变化）。
     */
    public static CompressionReport empty(int tokens, String reason) {
        return new CompressionReport(false, tokens, tokens, 0, new ArrayList<>(), reason);
    }

    /**
     * 是否有变化。
     */
    public boolean isChanged() { return changed; }

    // Getters and setters
    public int getTokensBefore() { return tokensBefore; }
    public void setTokensBefore(int tokensBefore) { this.tokensBefore = tokensBefore; }

    public int getTokensAfter() { return tokensAfter; }
    public void setTokensAfter(int tokensAfter) { this.tokensAfter = tokensAfter; }

    public int getTokensSaved() { return tokensSaved; }
    public void setTokensSaved(int tokensSaved) { this.tokensSaved = tokensSaved; }

    public List<String> getActions() { return actions; }
    public void setActions(List<String> actions) { this.actions = actions; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}

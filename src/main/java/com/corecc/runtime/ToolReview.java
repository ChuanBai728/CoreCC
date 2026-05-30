package com.corecc.runtime;

/**
 * 工具执行的标准化后处理结果。
 *
 * 对应 Python 版的 corecc/runtime.py 中的 ToolReview dataclass。
 */
public class ToolReview {
    private String content;
    private boolean failed;
    private String category;
    private String nudge;
    private int compactedChars;

    public ToolReview(String content, boolean failed, String category, String nudge, int compactedChars) {
        this.content = content;
        this.failed = failed;
        this.category = category;
        this.nudge = nudge;
        this.compactedChars = compactedChars;
    }

    // Getters and setters
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public boolean isFailed() { return failed; }
    public void setFailed(boolean failed) { this.failed = failed; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getNudge() { return nudge; }
    public void setNudge(String nudge) { this.nudge = nudge; }

    public int getCompactedChars() { return compactedChars; }
    public void setCompactedChars(int compactedChars) { this.compactedChars = compactedChars; }
}

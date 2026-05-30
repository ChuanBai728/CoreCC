package com.corecc.llm;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.*;

/**
 * 大模型响应，包含文本内容和工具调用。
 * 对应 Python 版的 corecc/llm.py 中的 LLMResponse dataclass。
 */
public class LLMResponse {
    private String content;
    private List<ToolCall> toolCalls;
    private String reasoningContent;
    private int promptTokens;
    private int completionTokens;

    public LLMResponse() {
        this.content = "";
        this.toolCalls = new ArrayList<>();
        this.reasoningContent = "";
    }

    public LLMResponse(String content, List<ToolCall> toolCalls, String reasoningContent,
                       int promptTokens, int completionTokens) {
        this.content = content != null ? content : "";
        this.toolCalls = toolCalls != null ? toolCalls : new ArrayList<>();
        this.reasoningContent = reasoningContent != null ? reasoningContent : "";
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
    }

    /**
     * 转换为 OpenAI 消息格式，用于追加到对话历史。
     */
    public Map<String, Object> toMessage() {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "assistant");

        // Content: null if tool calls present, empty string if neither
        if (content != null && !content.isEmpty()) {
            msg.put("content", content);
        } else if (toolCalls.isEmpty()) {
            msg.put("content", "");
        } else {
            msg.put("content", null);
        }

        // Reasoning content (for DeepSeek thinking-mode models)
        if (reasoningContent != null && !reasoningContent.isEmpty()) {
            msg.put("reasoning_content", reasoningContent);
        }

        // Tool calls in OpenAI format
        if (toolCalls != null && !toolCalls.isEmpty()) {
            List<Map<String, Object>> tcList = new ArrayList<>();
            for (ToolCall tc : toolCalls) {
                Map<String, Object> tcObj = new LinkedHashMap<>();
                tcObj.put("id", tc.getId());
                tcObj.put("type", "function");

                Map<String, Object> func = new LinkedHashMap<>();
                func.put("name", tc.getName());
                func.put("arguments", tc.getArguments() != null ?
                    JsonUtils.toJson(tc.getArguments()) : "{}");
                tcObj.put("function", func);

                tcList.add(tcObj);
            }
            msg.put("tool_calls", tcList);
        }

        return msg;
    }

    // Getters and setters
    @JsonProperty("content")
    public String getContent() { return content; }

    @JsonProperty("content")
    public void setContent(String content) { this.content = content != null ? content : ""; }

    @JsonProperty("tool_calls")
    public List<ToolCall> getToolCalls() { return toolCalls; }

    @JsonProperty("tool_calls")
    public void setToolCalls(List<ToolCall> toolCalls) { this.toolCalls = toolCalls != null ? toolCalls : new ArrayList<>(); }

    @JsonProperty("reasoning_content")
    public String getReasoningContent() { return reasoningContent; }

    @JsonProperty("reasoning_content")
    public void setReasoningContent(String reasoningContent) { this.reasoningContent = reasoningContent != null ? reasoningContent : ""; }

    @JsonProperty("prompt_tokens")
    public int getPromptTokens() { return promptTokens; }

    @JsonProperty("prompt_tokens")
    public void setPromptTokens(int promptTokens) { this.promptTokens = promptTokens; }

    @JsonProperty("completion_tokens")
    public int getCompletionTokens() { return completionTokens; }

    @JsonProperty("completion_tokens")
    public void setCompletionTokens(int completionTokens) { this.completionTokens = completionTokens; }
}

package com.corecc.llm;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * 工具调用信息。
 * 对应 Python 版的 corecc/llm.py 中的 ToolCall dataclass。
 */
public class ToolCall {
    private String id;
    private String name;
    private Map<String, Object> arguments;

    public ToolCall() {}

    public ToolCall(String id, String name, Map<String, Object> arguments) {
        this.id = id;
        this.name = name;
        this.arguments = arguments;
    }

    @JsonProperty("id")
    public String getId() { return id; }

    @JsonProperty("id")
    public void setId(String id) { this.id = id; }

    @JsonProperty("name")
    public String getName() { return name; }

    @JsonProperty("name")
    public void setName(String name) { this.name = name; }

    @JsonProperty("arguments")
    public Map<String, Object> getArguments() { return arguments; }

    @JsonProperty("arguments")
    public void setArguments(Map<String, Object> arguments) { this.arguments = arguments; }
}

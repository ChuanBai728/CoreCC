package com.corecc.tools;

import java.util.Map;

/**
 * 工具接口 —— 定义工具契约。
 *
 * 对应 Python 版的 corecc/tools/base.py 中的 Tool ABC。
 * 对应 Claude Code 的 ToolDef 类型。
 */
public interface Tool {
    /**
     * 工具名称（如 "read_file", "bash"）
     */
    String getName();

    /**
     * 工具描述（大模型据此决定何时使用）
     */
    String getDescription();

    /**
     * 函数参数的 JSON Schema
     */
    Map<String, Object> getParameters();

    /**
     * 是否只读工具（可以安全并行执行）
     */
    boolean isReadOnly();

    /**
     * 执行工具并返回文本结果。
     *
     * @param args 工具参数
     * @return 执行结果文本
     */
    String execute(Map<String, Object> args);

    /**
     * 生成 OpenAI function-calling 格式的 schema。
     */
    default Map<String, Object> toSchema() {
        return Map.of(
            "type", "function",
            "function", Map.of(
                "name", getName(),
                "description", getDescription(),
                "parameters", getParameters()
            )
        );
    }
}

package com.corecc.tools;

import java.util.*;

/**
 * 工具注册中心 —— 管理所有可用工具。
 *
 * 对应 Python 版的 corecc/tools/__init__.py。
 * CoreCC 简化为静态注册 7 个核心工具，覆盖最常用的操作场景。
 */
public class ToolRegistry {
    /**
     * 核心工具集 —— 7 个工具覆盖完整编程工作流：
     * - bash:    执行 shell 命令（运行测试、安装包、git 操作等）
     * - read:    读取文件内容（带行号，支持偏移量）
     * - write:   创建新文件或完全覆盖
     * - edit:    搜索替换式编辑（Claude Code 的核心创新）
     * - glob:    通过 glob 模式查找文件
     * - grep:    正则表达式内容搜索
     * - agent:   生成子智能体处理复杂子任务（多智能体协作）
     */
    public static List<Tool> getAllTools() {
        List<Tool> tools = new ArrayList<>();
        tools.add(new BashTool());
        tools.add(new ReadFileTool());
        tools.add(new WriteFileTool());
        tools.add(new EditFileTool());
        tools.add(new GlobTool());
        tools.add(new GrepTool());
        tools.add(new AgentTool());
        return tools;
    }

    /**
     * 根据名称查找工具实例。
     */
    public static Tool getTool(String name) {
        return getAllTools().stream()
            .filter(t -> t.getName().equals(name))
            .findFirst()
            .orElse(null);
    }
}

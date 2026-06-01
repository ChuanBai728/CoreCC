package com.corecc.tools;

import com.corecc.capabilities.CapabilityConfig;
import com.corecc.capabilities.LoadedCapabilities;
import com.corecc.mcp.McpClient;
import com.corecc.mcp.McpConfigLoader;
import com.corecc.mcp.McpServerConfig;
import com.corecc.mcp.McpToolAdapter;
import com.corecc.mcp.McpToolDescriptor;
import com.corecc.skills.Skill;
import com.corecc.skills.SkillLoader;
import com.corecc.skills.SkillTool;

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
        return coreTools();
    }

    /**
     * Load built-in tools plus optional local skills and stdio MCP tools.
     */
    public static LoadedCapabilities loadConfiguredTools(CapabilityConfig config) {
        List<Tool> tools = coreTools();
        List<Skill> skills = new ArrayList<>();
        List<String> mcpSummaries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (config != null && config.hasSkills()) {
            skills = SkillLoader.load(config.getSkillPaths());
            if (!skills.isEmpty()) {
                tools.add(new SkillTool(skills));
            }
        }

        if (config != null && config.hasMcpConfig()) {
            try {
                List<McpServerConfig> servers = McpConfigLoader.load(
                    config.getMcpConfigPath(), config.getMcpTimeoutSec());
                for (McpServerConfig server : servers) {
                    try {
                        McpClient client = McpClient.start(server);
                        List<McpToolDescriptor> descriptors = client.listTools();
                        if (descriptors.isEmpty()) {
                            client.close();
                            mcpSummaries.add("- " + server.getName() + ": connected, no tools exposed");
                            continue;
                        }

                        for (McpToolDescriptor descriptor : descriptors) {
                            boolean readOnly = server.isTrusted() && descriptor.readOnlyHint();
                            tools.add(new McpToolAdapter(client, descriptor, readOnly));
                        }

                        String instruction = client.getServerInstructions();
                        String suffix = instruction == null || instruction.isBlank()
                            ? ""
                            : " Instructions: " + instruction.replaceAll("\\s+", " ").trim();
                        mcpSummaries.add("- " + server.getName() + ": " + descriptors.size() +
                            " tool(s) from " + client.getServerDisplayName() + "." + suffix);
                    } catch (Exception e) {
                        warnings.add("MCP server '" + server.getName() + "' skipped: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                warnings.add("MCP config skipped: " + e.getMessage());
            }
        }

        return new LoadedCapabilities(tools, skills, mcpSummaries, warnings);
    }

    private static List<Tool> coreTools() {
        List<Tool> tools = new ArrayList<>();
        tools.add(new BashTool());
        tools.add(new ReadFileTool());
        tools.add(new WriteFileTool());
        tools.add(new WriteBytesBase64Tool());
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

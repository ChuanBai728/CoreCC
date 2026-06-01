package com.corecc.mcp;

import com.corecc.tools.Tool;

import java.io.IOException;
import java.util.Map;

/**
 * Exposes one MCP server tool as a CoreCC Tool.
 */
public class McpToolAdapter implements Tool {
    private final McpClient client;
    private final McpToolDescriptor descriptor;
    private final String exposedName;
    private final boolean readOnly;

    public McpToolAdapter(McpClient client, McpToolDescriptor descriptor, boolean readOnly) {
        this.client = client;
        this.descriptor = descriptor;
        this.exposedName = exposedName(client.getServerName(), descriptor.getName());
        this.readOnly = readOnly;
    }

    @Override
    public String getName() {
        return exposedName;
    }

    @Override
    public String getDescription() {
        String label = descriptor.getTitle() == null || descriptor.getTitle().isBlank()
            ? descriptor.getName()
            : descriptor.getTitle();
        String description = descriptor.getDescription() == null ? "" : descriptor.getDescription();
        return "[MCP " + client.getServerName() + "/" + descriptor.getName() + "] " +
            label + (description.isBlank() ? "" : ": " + description);
    }

    @Override
    public Map<String, Object> getParameters() {
        return descriptor.getInputSchema();
    }

    @Override
    public boolean isReadOnly() {
        return readOnly;
    }

    @Override
    public String execute(Map<String, Object> args) {
        try {
            return client.callTool(descriptor.getName(), args);
        } catch (IOException e) {
            return "MCP tool call failed: " + e.getMessage();
        }
    }

    public static String exposedName(String serverName, String toolName) {
        String base = "mcp__" + clean(serverName) + "__" + clean(toolName);
        if (base.length() <= 64) {
            return base;
        }
        String hash = Integer.toHexString((serverName + "/" + toolName).hashCode());
        int prefixLen = 64 - hash.length() - 1;
        return base.substring(0, Math.max(1, prefixLen)) + "_" + hash;
    }

    private static String clean(String raw) {
        String value = raw == null ? "" : raw.trim().replaceAll("[^A-Za-z0-9_]", "_");
        value = value.replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        return value.isEmpty() ? "tool" : value;
    }
}

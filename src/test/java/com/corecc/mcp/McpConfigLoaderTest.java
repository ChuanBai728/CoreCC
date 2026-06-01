package com.corecc.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpConfigLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsClaudeStyleMcpServersConfig() throws Exception {
        Path configPath = tempDir.resolve("mcp.json");
        Files.writeString(configPath, """
            {
              "mcpServers": {
                "fake-server": {
                  "command": "java",
                  "args": ["-version"],
                  "env": {"A": "B"},
                  "cwd": "/app",
                  "trusted": true
                }
              }
            }
            """);

        List<McpServerConfig> servers = McpConfigLoader.load(configPath, 7);

        assertEquals(1, servers.size());
        McpServerConfig server = servers.get(0);
        assertEquals("fake-server", server.getName());
        assertEquals("java", server.getCommand());
        assertEquals(List.of("-version"), server.getArgs());
        assertEquals("B", server.getEnv().get("A"));
        assertEquals(Path.of("/app"), server.getCwd());
        assertTrue(server.isTrusted());
        assertEquals(7, server.getTimeoutSec());
    }
}

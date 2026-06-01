package com.corecc.mcp;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpClientTest {
    @Test
    void listsAndCallsStdioMcpTools() throws Exception {
        McpServerConfig config = new McpServerConfig(
            "fake",
            javaExecutable(),
            List.of("-cp", System.getProperty("java.class.path"), FakeMcpServer.class.getName()),
            Map.of(),
            null,
            true,
            5
        );

        try (McpClient client = McpClient.start(config)) {
            List<McpToolDescriptor> tools = client.listTools();

            assertEquals("Fake MCP", client.getServerDisplayName());
            assertTrue(client.getServerInstructions().contains("fake tools"));
            assertEquals(1, tools.size());
            assertEquals("echo", tools.get(0).getName());
            assertTrue(tools.get(0).readOnlyHint());
            assertEquals("echo:hello", client.callTool("echo", Map.of("text", "hello")));
        }
    }

    @Test
    void adapterUsesPrefixedNameAndTrustedReadOnlyHint() {
        String exposed = McpToolAdapter.exposedName("fake-server", "echo.tool");

        assertTrue(exposed.startsWith("mcp__fake_server__echo_tool"));
    }

    private static String javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win")
            ? "java.exe"
            : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }
}

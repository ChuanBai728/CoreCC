package com.corecc.mcp;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Configuration for one stdio MCP server.
 */
public class McpServerConfig {
    private final String name;
    private final String command;
    private final List<String> args;
    private final Map<String, String> env;
    private final Path cwd;
    private final boolean trusted;
    private final int timeoutSec;

    public McpServerConfig(String name, String command, List<String> args,
                           Map<String, String> env, Path cwd,
                           boolean trusted, int timeoutSec) {
        this.name = name;
        this.command = command;
        this.args = args != null ? List.copyOf(args) : List.of();
        this.env = env != null ? Map.copyOf(env) : Map.of();
        this.cwd = cwd;
        this.trusted = trusted;
        this.timeoutSec = timeoutSec;
    }

    public String getName() {
        return name;
    }

    public String getCommand() {
        return command;
    }

    public List<String> getArgs() {
        return args;
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public Path getCwd() {
        return cwd;
    }

    public boolean isTrusted() {
        return trusted;
    }

    public int getTimeoutSec() {
        return timeoutSec;
    }
}

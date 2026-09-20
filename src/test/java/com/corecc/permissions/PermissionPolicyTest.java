package com.corecc.permissions;

import com.corecc.tools.Tool;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PermissionPolicyTest {
    @Test
    void readOnlyToolsNeverPrompt() {
        PermissionPolicy policy = new PermissionPolicy((name, args) -> fail("must not prompt"), false);
        assertNull(policy.check(tool("read_file", true), Map.of()));
    }

    @Test
    void nonInteractiveMutationIsDeniedUnlessAllowAll() {
        Tool write = tool("write_file", false);
        assertNotNull(new PermissionPolicy(null, false).check(write, Map.of()));
        assertNull(new PermissionPolicy(null, true).check(write, Map.of()));
    }

    @Test
    void alwaysDecisionIsRememberedPerTool() {
        AtomicInteger prompts = new AtomicInteger();
        PermissionPolicy policy = new PermissionPolicy((name, args) -> {
            prompts.incrementAndGet();
            return PermissionPolicy.Decision.ALLOW_ALWAYS;
        }, false);
        Tool write = tool("write_file", false);
        assertNull(policy.check(write, Map.of()));
        assertNull(policy.check(write, Map.of()));
        assertEquals(1, prompts.get());
    }

    private static Tool tool(String name, boolean readOnly) {
        return new Tool() {
            public String getName() { return name; }
            public String getDescription() { return name; }
            public Map<String, Object> getParameters() { return Map.of(); }
            public boolean isReadOnly() { return readOnly; }
            public String execute(Map<String, Object> args) { return "ok"; }
        };
    }
}

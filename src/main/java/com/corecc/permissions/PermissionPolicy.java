package com.corecc.permissions;

import com.corecc.tools.Tool;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Session-scoped consent for tools that can change external state. */
public final class PermissionPolicy {
    public enum Decision { ALLOW_ONCE, ALLOW_ALWAYS, DENY }

    @FunctionalInterface
    public interface Prompt {
        Decision decide(String toolName, Map<String, Object> arguments);
    }

    private final Prompt prompt;
    private final boolean allowAll;
    private final Set<String> alwaysAllowed = ConcurrentHashMap.newKeySet();

    public PermissionPolicy(Prompt prompt, boolean allowAll) {
        this.prompt = prompt;
        this.allowAll = allowAll;
    }

    /** Returns null when execution is allowed, otherwise a model-visible refusal. */
    public String check(Tool tool, Map<String, Object> arguments) {
        if (tool.isReadOnly() || allowAll || alwaysAllowed.contains(tool.getName())) {
            return null;
        }
        if (prompt == null) {
            return "Permission denied: " + tool.getName() +
                " can change state and this non-interactive session cannot ask for consent. " +
                "Rerun with --yes or perform the step manually.";
        }

        Decision decision = prompt.decide(tool.getName(), arguments);
        if (decision == Decision.ALLOW_ALWAYS) {
            alwaysAllowed.add(tool.getName());
            return null;
        }
        if (decision == Decision.ALLOW_ONCE) {
            return null;
        }
        return "Permission denied: the user refused this " + tool.getName() +
            " call. Do not retry it unchanged.";
    }
}

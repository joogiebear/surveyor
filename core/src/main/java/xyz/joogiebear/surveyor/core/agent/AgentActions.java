package xyz.joogiebear.surveyor.core.agent;

import java.util.List;

/** The tool names an agent token can be allowed to call, and the wildcards that cover groups of them. */
public final class AgentActions {

    public static final List<String> TOOLS = List.of(
            "world.list", "selection.get", "selection.set",
            "edit.set", "edit.replace", "edit.copy", "edit.paste", "edit.undo",
            "region.create", "region.update", "region.delete", "region.get", "region.list", "region.at",
            "flag.set", "flag.clear", "member.add", "member.remove",
            "operation.status", "operation.cancel", "audit.query");

    private static final List<String> GROUPS = List.of("world", "selection", "edit", "region", "flag", "member", "operation", "audit");

    private AgentActions() {
    }

    /** A tool name, a group wildcard such as {@code edit.*}, or {@code *}. */
    public static boolean isValid(String action) {
        if (TOOLS.contains(action) || action.equals("*")) {
            return true;
        }
        return action.endsWith(".*") && GROUPS.contains(action.substring(0, action.length() - 2));
    }

    /** Whether the allowed pattern covers the tool. */
    public static boolean covers(String pattern, String tool) {
        if (pattern.equals("*") || pattern.equals(tool)) {
            return true;
        }
        return pattern.endsWith(".*") && tool.startsWith(pattern.substring(0, pattern.length() - 1));
    }
}

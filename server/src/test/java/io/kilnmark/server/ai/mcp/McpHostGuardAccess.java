package io.kilnmark.server.ai.mcp;

/** Lets tests in other packages call the guard's package-private check. */
public final class McpHostGuardAccess {

    private McpHostGuardAccess() {
    }

    public static boolean local(String host) {
        return McpHostGuard.local(host);
    }
}

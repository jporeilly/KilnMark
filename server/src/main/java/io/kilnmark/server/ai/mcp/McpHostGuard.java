package io.kilnmark.server.ai.mcp;

import java.util.Locale;
import java.util.Set;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.auth.AuthMode;
import io.quarkus.vertx.http.runtime.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * With sign-in off ({@code kilnmark.auth.mode=off}, loopback only), anyone who can reach the
 * server is an admin, and a web page could reach {@code /mcp} through DNS rebinding. So, then,
 * {@code /mcp} answers only requests whose Host is a name for this machine. With sign-in on, the
 * API token protects it and this does nothing.
 */
@ApplicationScoped
public class McpHostGuard {

    static final Set<String> LOCAL = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private final boolean signInOff;

    public McpHostGuard(KilnmarkConfig config,
                        @ConfigProperty(name = "quarkus.http.host", defaultValue = "0.0.0.0") String httpHost) {
        this.signInOff = AuthMode.signInOff(config.auth().mode(), httpHost);
    }

    void register(@Observes Filters filters) {
        if (!signInOff) {
            return;
        }
        filters.register(rc -> {
            String path = rc.normalizedPath();
            if ((path.equals("/mcp") || path.startsWith("/mcp/")) && !local(rc.request().getHeader("Host"))) {
                rc.response().setStatusCode(403).putHeader("Content-Type", "application/json")
                    .end("{\"error\": \"With sign-in off, the MCP server answers only requests to localhost.\"}");
                return;
            }
            rc.next();
        }, 100);
    }

    static boolean local(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return false;
        }
        String h = hostHeader.strip().toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            h = end < 0 ? h : h.substring(0, end + 1);
        } else if (h.indexOf(':') >= 0 && h.indexOf(':') == h.lastIndexOf(':')) {
            h = h.substring(0, h.indexOf(':'));
        }
        return LOCAL.contains(h);
    }
}

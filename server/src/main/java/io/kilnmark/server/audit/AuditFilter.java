package io.kilnmark.server.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.jboss.resteasy.reactive.server.ServerResponseFilter;

import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;

/**
 * Records requests in the audit log ({@link AuditLog}): every request that may change something
 * ({@code POST}, {@code PUT}, {@code PATCH}, {@code DELETE}) that succeeded, and every request,
 * reading or not, refused for want of a role (403), or refused for want of sign-in when it would
 * have changed something (401). Only the method, path, status and the caller's address are kept:
 * never a query string or a body, which can hold secrets. The audit log stores events on a thread
 * of its own, so this never blocks, even on the event loop, where a refusal can be answered.
 */
public class AuditFilter {

    private static final Set<String> CHANGING = Set.of("POST", "PUT", "PATCH", "DELETE");

    @Inject
    AuditLog audit;

    @Inject
    SecurityIdentity identity;

    @ServerResponseFilter
    public void record(ContainerRequestContext request, ContainerResponseContext response, HttpServerRequest http) {
        String method = request.getMethod();
        int status = response.getStatus();
        boolean changing = CHANGING.contains(method);
        String action;
        if (status == 403 || status == 401 && changing) {
            action = AuditLog.API_REFUSED;
        } else if (changing && status >= 200 && status < 300) {
            action = AuditLog.API_REQUEST;
        } else {
            return;
        }
        // On a 401 the sign-in failed or was missing: asking the identity again may throw.
        AuditLog.Actor actor = status == 401 ? new AuditLog.Actor(AuditLog.ANONYMOUS, null) : AuditLog.actor(identity);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("method", method);
        detail.put("status", status);
        String client = client(http);
        if (client != null) {
            detail.put("client", client);
        }
        audit.record(actor, action, request.getUriInfo().getRequestUri().getRawPath(), detail);
    }

    /** The caller's address, as the server sees it (behind a proxy, as its forwarding settings say). */
    private static String client(HttpServerRequest http) {
        try {
            return http == null || http.remoteAddress() == null ? null : http.remoteAddress().hostAddress();
        } catch (RuntimeException e) {
            return null;
        }
    }
}

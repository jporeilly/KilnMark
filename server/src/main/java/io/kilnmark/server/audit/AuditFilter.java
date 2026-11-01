package io.kilnmark.server.audit;

import java.util.Map;
import java.util.Set;

import org.jboss.resteasy.reactive.server.ServerResponseFilter;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;

/**
 * Records requests in the audit log ({@link AuditLog}): every request that may change something
 * ({@code POST}, {@code PUT}, {@code PATCH}, {@code DELETE}) that succeeded, and every request,
 * reading or not, refused for want of a role (403), or refused for want of sign-in when it would
 * have changed something (401). Only the method, path and status are kept: never a query string or
 * a body, which can hold secrets.
 */
public class AuditFilter {

    private static final Set<String> CHANGING = Set.of("POST", "PUT", "PATCH", "DELETE");

    @Inject
    AuditLog audit;

    @Inject
    SecurityIdentity identity;

    @ServerResponseFilter
    public void record(ContainerRequestContext request, ContainerResponseContext response) {
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
        String path = request.getUriInfo().getRequestUri().getRawPath();
        audit.record(AuditLog.actor(identity), action, path, Map.of("method", method, "status", status));
    }
}

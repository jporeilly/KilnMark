package io.kilnmark.server.api;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;

import io.kilnmark.server.audit.AuditLog;
import io.kilnmark.server.auth.AccessRole;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/** {@code GET /api/v1/audit}: the audit log, newest first, a page at a time. Admins only. */
@Path("/api/v1/audit")
@Produces(MediaType.APPLICATION_JSON)
public class AuditResource {

    /** One page of events, and the {@code before} that gives the next page (null when this is the last). */
    public record Page(List<AuditLog.Event> events, Long next) {
    }

    private final AuditLog audit;

    public AuditResource(AuditLog audit) {
        this.audit = audit;
    }

    @GET
    @RolesAllowed(AccessRole.ADMIN_ROLE)
    @Operation(summary = "The audit log", description = "Who did what, newest first: requests that changed "
        + "something or were refused, tokens created and revoked, marks set and cleared, sources deleted, Allow "
        + "writing and Allow SQL turned on or off, and Most runs at once changed. Filter by action; page with "
        + "before (the next value of the previous page). The same events go to the kilnmark.audit log category, one JSON object a line, for a "
        + "SIEM. Role: admin.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "A page of events"),
        @APIResponse(responseCode = "400", description = "An action that is not one of the audit log's, or a limit out "
            + "of range"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "403", description = "Not an admin")})
    public Page list(@QueryParam("action") String action, @QueryParam("before") Long before,
                     @QueryParam("limit") @DefaultValue("100") int limit) {
        String a = action == null || action.isBlank() ? null : action.strip();
        if (a != null && !AuditLog.ACTIONS.contains(a)) {
            throw new IllegalArgumentException("action: one of " + String.join(", ",
                AuditLog.ACTIONS.stream().sorted().toList()));
        }
        if (limit < 1 || limit > AuditLog.MAX_LIMIT) {
            throw new IllegalArgumentException("limit: from 1 to " + AuditLog.MAX_LIMIT);
        }
        List<AuditLog.Event> events = audit.list(a, before, limit);
        Long next = events.size() < limit ? null : events.get(events.size() - 1).id();
        return new Page(events, next);
    }
}

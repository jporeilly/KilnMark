package io.kilnmark.server.api;

import java.net.URI;
import java.util.List;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;

import io.kilnmark.server.audit.AuditLog;
import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.quality.Sights;
import io.kilnmark.server.schedules.ScheduleService;
import io.kilnmark.server.schedules.ScheduleStore;
import io.kilnmark.server.schedules.ScheduleViews;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Schedules (ADR 0046, docs/design/schedules.md): pipelines and flows that run at set times, as
 * {@code schedule <id> (set by <name>)} with the role chosen for them. Designers and admins add,
 * change, pause, resume and delete them; stewards read them with the values their runs start
 * with; viewers read them without those values.
 */
@Path("/api/v1/schedules")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AccessRole.VIEWER_ROLE)
public class ScheduleResource {

    private final ScheduleService schedules;
    private final SecurityIdentity identity;
    private final AuditLog audit;
    private final Sights sights;

    public ScheduleResource(ScheduleService schedules, SecurityIdentity identity, AuditLog audit, Sights sights) {
        this.schedules = schedules;
        this.identity = identity;
        this.audit = audit;
        this.sights = sights;
    }

    @GET
    @Operation(summary = "List schedules", description = "Every schedule, or those of one pipeline (pipeline=) or "
        + "flow (flow=), by id: what it runs, its cron expression and zone, the role its runs act as, whether it is "
        + "on, when it fires next and what happened the last time it was due. Stewards and above see the values its "
        + "runs start with; for viewers parameters is null. Role: viewer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The schedules"),
        @APIResponse(responseCode = "401", description = "Not signed in")})
    public List<ScheduleViews.Schedule> list(@QueryParam("pipeline") String pipeline, @QueryParam("flow") String flow) {
        return schedules.list(pipeline, flow, sights.data());
    }

    @GET
    @Path("{id}")
    @Operation(summary = "A schedule", description = "One schedule, as the list shows it. Role: viewer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The schedule"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "404", description = "No such schedule")})
    public ScheduleViews.Schedule get(@PathParam("id") long id) {
        return schedules.get(id, sights.data());
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(AccessRole.DESIGNER_ROLE)
    @Operation(summary = "Add a schedule", description = "Runs a pipeline or a flow (give one) at the times a five-field "
        + "cron expression gives (minute hour day month weekday, such as 0 2 * * MON-FRI), read in zone (an IANA "
        + "name such as Europe/London; UTC by default). Each run is of the latest version, with these parameter "
        + "values, checked as Run now checks them; the values are checked now too, against the latest version. "
        + "runAs is the role the runs act as: steward (the default) or designer, never admin, and never above "
        + "your own. A run is requested by \"schedule <id> (set by <you>)\". enabled false adds it paused. A "
        + "pipeline or flow may have several schedules. Recorded in the audit log, without the values. "
        + "Role: designer.")
    @APIResponses({
        @APIResponse(responseCode = "201", description = "Added; the body is the schedule"),
        @APIResponse(responseCode = "400", description = "Neither or both of pipeline and flow, one that does not "
            + "exist, a cron expression that cannot be read or never fires, an unknown zone, a runAs other than "
            + "steward or designer, or a parameter value the latest version does not take"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "403", description = "Not a designer")})
    public Response create(ScheduleViews.ScheduleChange body) {
        ScheduleStore.Schedule s = schedules.create(body, who(), role());
        audit.record(AuditLog.actor(identity), AuditLog.SCHEDULE_SET, "schedule " + s.id(),
            ScheduleService.auditDetail(s));
        return Response.created(URI.create("/api/v1/schedules/" + s.id())).entity(schedules.get(s.id(), true))
            .build();
    }

    @PUT
    @Path("{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(AccessRole.DESIGNER_ROLE)
    @Operation(summary = "Change a schedule", description = "Replaces everything a schedule is set to, as POST takes "
        + "it: send enabled false to pause it and true to resume it. A change that keeps the times, zone and "
        + "pausing keeps the next fire; otherwise it is worked out again, and a fire that was due and not yet "
        + "started is recorded as missed. Its last outcome stays, unless it now runs something else. From now on its runs are requested by \"schedule <id> (set by <you>)\". "
        + "Recorded in the audit log, without the values. Role: designer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Changed; the body is the schedule"),
        @APIResponse(responseCode = "400", description = "As for POST"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "403", description = "Not a designer"),
        @APIResponse(responseCode = "404", description = "No such schedule")})
    public ScheduleViews.Schedule replace(@PathParam("id") long id, ScheduleViews.ScheduleChange body) {
        ScheduleStore.Schedule s = schedules.replace(id, body, who(), role());
        audit.record(AuditLog.actor(identity), AuditLog.SCHEDULE_SET, "schedule " + s.id(),
            ScheduleService.auditDetail(s));
        return schedules.get(s.id(), true);
    }

    @DELETE
    @Path("{id}")
    @RolesAllowed(AccessRole.DESIGNER_ROLE)
    @Operation(summary = "Delete a schedule", description = "Deletes a schedule. Runs it started stay, as runs "
        + "requested by it. Deleting a pipeline deletes its schedules too. Recorded in the audit log. "
        + "Role: designer.")
    @APIResponses({
        @APIResponse(responseCode = "204", description = "Deleted"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "403", description = "Not a designer"),
        @APIResponse(responseCode = "404", description = "No such schedule")})
    public Response delete(@PathParam("id") long id) {
        ScheduleStore.Schedule s = schedules.delete(id, who(), role());
        audit.record(AuditLog.actor(identity), AuditLog.SCHEDULE_DELETED, "schedule " + s.id(),
            ScheduleService.auditDetail(s));
        return Response.noContent().build();
    }

    @POST
    @Path("preview")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Try a cron expression", description = "The next five times a cron expression would fire in "
        + "a zone (UTC by default), from now, or why it cannot be used: valid false with the problem. Nothing is "
        + "saved. Role: viewer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The times, or the problem"),
        @APIResponse(responseCode = "401", description = "Not signed in")})
    public ScheduleViews.Preview preview(ScheduleViews.PreviewRequest body) {
        if (body == null) {
            throw new IllegalArgumentException("Send {\"cron\": \"0 2 * * *\", \"zone\": \"Europe/London\"}");
        }
        return schedules.preview(body.cron(), body.zone());
    }

    private AccessRole role() {
        return AccessRole.highest(identity::hasRole);
    }

    private String who() {
        return identity.isAnonymous() ? "anonymous" : identity.getPrincipal().getName().replaceAll("\\p{Cntrl}", "?");
    }
}

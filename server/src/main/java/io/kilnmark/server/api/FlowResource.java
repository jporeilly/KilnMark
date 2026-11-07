package io.kilnmark.server.api;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;

import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.flows.FlowService;
import io.kilnmark.server.flows.FlowStore;
import io.kilnmark.server.flows.FlowViews;
import io.kilnmark.server.pipelines.PipelineService;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Flows (docs/design/flows.md): saved as YAML with every version, checked as they are written, and
 * run by workers, node by node. Viewers read; designers save; stewards run and cancel. A flow with a
 * sql node is saved only by a designer whose token sees unmasked values (ADR 0028). Designers
 * delete a flow, with its versions, runs and schedules, while no run of it is going (ADR 0048).
 */
@Path("/api/v1/flows")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AccessRole.VIEWER_ROLE)
public class FlowResource {

    private final FlowService flows;
    private final SecurityIdentity identity;

    /** The audit log, for a deleted flow's schedules. */
    @jakarta.inject.Inject
    io.kilnmark.server.audit.AuditLog audit;

    /** Who is saving: only a caller who may see unmasked values saves a flow with a sql node. */
    @jakarta.inject.Inject
    io.kilnmark.server.quality.Sights sights;

    /** Starting and reading runs for callers that wait, with request keys (ADR 0047). */
    @jakarta.inject.Inject
    RunRequests runRequests;

    public FlowResource(FlowService flows, SecurityIdentity identity) {
        this.flows = flows;
        this.identity = identity;
    }

    @GET
    @Operation(summary = "List flows", description = "Every flow by name, with its latest version, whether that "
        + "version can run, and its latest run. Role: viewer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The flows"),
        @APIResponse(responseCode = "401", description = "Not signed in")})
    public List<FlowViews.FlowSummary> list() {
        return flows.list(sights.data());
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(AccessRole.DESIGNER_ROLE)
    @Operation(summary = "Add a flow", description = "Saves a new flow with its YAML as version 1. The name is the "
        + "YAML's: a small letter followed by small letters, digits and dashes, at most 63 characters, and it cannot "
        + "change. A flow with problems is saved as a draft (valid false) and cannot run until they are fixed. A flow "
        + "with a sql node is saved only by a designer whose token sees unmasked values. The ETag is the latest "
        + "version, to send back in If-Match when saving the next. Role: designer.")
    @APIResponses({
        @APIResponse(responseCode = "201", description = "Added; the body is the flow with its version and problems"),
        @APIResponse(responseCode = "400", description = "No YAML, no name or one that cannot be used, or YAML over "
            + "the size limit"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "403", description = "Not a designer, or the flow has a sql node and the caller "
            + "may not see unmasked values"),
        @APIResponse(responseCode = "409", description = "A flow with that name exists")})
    public Response create(FlowViews.FlowChange body) {
        FlowStore.Flow f = flows.create(yaml(body), body.message(), who(), role(), sights.mayUnmask());
        FlowViews.FlowDetail d = flows.detail(f.name(), sights.data());
        return Response.created(URI.create("/api/v1/flows/" + d.name())).tag(etag(d)).entity(d).build();
    }

    @POST
    @Path("validate")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Check flow YAML", description = "Checks YAML without saving or running it: its shape, "
        + "connections and conditions, and that every pipeline it runs exists. Gives the problems, each with its "
        + "line, column and node, and the parameters the flow declares. Role: viewer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "What the check found; valid is false when there are problems"),
        @APIResponse(responseCode = "400", description = "No YAML, or YAML over the size limit"),
        @APIResponse(responseCode = "401", description = "Not signed in")})
    public FlowViews.Validation validateNew(FlowViews.ValidateRequest body) {
        if (body == null) {
            throw new IllegalArgumentException("Send {\"yaml\": \"...\"}");
        }
        return flows.check(body.yaml(), null);
    }

    @GET
    @Path("{name}")
    @Operation(summary = "A flow", description = "The flow with its latest version's YAML, problems and parameters, "
        + "and its latest run. The ETag is the latest version's number, for If-Match. Role: viewer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The flow"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "404", description = "No such flow")})
    public Response get(@PathParam("name") String name) {
        FlowViews.FlowDetail d = flows.detail(name, sights.data());
        return Response.ok(d).tag(etag(d)).build();
    }

    @PUT
    @Path("{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(AccessRole.DESIGNER_ROLE)
    @Operation(summary = "Save a new version", description = "Saves the YAML as the flow's next version, with a "
        + "message saying what changed; earlier versions stay as they were. The YAML keeps the flow's name. With "
        + "If-Match set to the latest version as you read it (the ETag), the save is refused (409) when someone has "
        + "saved another since. Role: designer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Saved; the body is the flow with the new version"),
        @APIResponse(responseCode = "400", description = "No YAML, YAML over the size limit or naming another flow, "
            + "or an If-Match that is not a version number"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "403", description = "Not a designer, or the flow has a sql node and the caller "
            + "may not see unmasked values"),
        @APIResponse(responseCode = "404", description = "No such flow"),
        @APIResponse(responseCode = "409", description = "Someone else saved a version after the one in If-Match")})
    public Response save(@PathParam("name") String name, @HeaderParam("If-Match") String ifMatch,
                         FlowViews.FlowChange body) {
        Integer expected = expected(ifMatch);
        flows.update(name, yaml(body), body.message(), who(), role(), sights.mayUnmask(), expected);
        FlowViews.FlowDetail d = flows.detail(name, sights.data());
        return Response.ok(d).tag(etag(d)).build();
    }

    @DELETE
    @Path("{name}")
    @RolesAllowed(AccessRole.DESIGNER_ROLE)
    @Operation(summary = "Delete a flow", description = "Deletes the flow with its versions, its runs and their "
        + "nodes, and its schedules. The pipeline runs it started stay, with what they wrote and the lineage they "
        + "recorded; they no longer link to the flow run. Refused while a run is queued or running. Role: designer.")
    @APIResponses({
        @APIResponse(responseCode = "204", description = "Deleted"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "403", description = "Not a designer"),
        @APIResponse(responseCode = "404", description = "No such flow"),
        @APIResponse(responseCode = "409", description = "A run is queued or running, or the flow or one of its "
            + "schedules was busy for a moment (nothing was deleted; try again)")})
    public Response delete(@PathParam("name") String name) {
        // its schedules are deleted with it, in the same transaction: each is recorded (ADR 0029, ADR 0046)
        io.kilnmark.server.schedules.ScheduleService.recordDeletedWith(audit,
            io.kilnmark.server.audit.AuditLog.actor(identity), io.kilnmark.server.schedules.ScheduleStore.Target.FLOW,
            name, flows.delete(name, who(), role()));
        return Response.noContent().build();
    }

    @GET
    @Path("{name}/versions")
    @Operation(summary = "A flow's versions", description = "Every saved version, newest first: who saved it, when, "
        + "the message, whether it could run and how many problems it had. Role: viewer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The versions"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "404", description = "No such flow")})
    public List<FlowViews.VersionSummary> versions(@PathParam("name") String name) {
        return flows.versions(name);
    }

    @GET
    @Path("{name}/versions/{n}")
    @Operation(summary = "One version", description = "A saved version with its YAML, the problems found when it "
        + "was saved and the parameters it declares. Role: viewer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The version"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "404", description = "No such flow or version")})
    public FlowViews.VersionDetail version(@PathParam("name") String name, @PathParam("n") int n) {
        return flows.versionDetail(name, n);
    }

    @POST
    @Path("{name}/validate")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Check YAML for this flow", description = "As POST /api/v1/flows/validate, and a YAML naming "
        + "another flow is a problem, since a flow's name cannot change. Nothing is saved. Role: viewer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "What the check found"),
        @APIResponse(responseCode = "400", description = "No YAML, or YAML over the size limit"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "404", description = "No such flow")})
    public FlowViews.Validation validate(@PathParam("name") String name, FlowViews.ValidateRequest body) {
        if (body == null) {
            throw new IllegalArgumentException("Send {\"yaml\": \"...\"}");
        }
        return flows.check(body.yaml(), name);
    }

    @POST
    @Path("{name}/runs")
    @Consumes({MediaType.APPLICATION_JSON, MediaType.WILDCARD})
    @Produces({MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN})
    @RolesAllowed(AccessRole.STEWARD_ROLE)
    @RunOnVirtualThread
    @Operation(summary = "Run a flow", description = "Queues a run of one version (the latest when version is left "
        + "out) with values for its parameters (the others take their defaults; one with no default must be given). "
        + "The version is checked again first, since pipelines may have changed: one with problems is refused, and "
        + "so is a second run while one is queued or running. The pipelines it runs run as you, with your role. "
        + "With wait (whole seconds, at most kilnmark.api.max-wait, 15 minutes by default) the answer comes when "
        + "the run has ended (200) or when the wait is up (202): read the run with wait to go on waiting. A start is "
        + "never refused for want of a wait place: with as many waiting as allowed, it answers at once (202). With "
        + "an Idempotency-Key header (1 to 200 printable characters), the same key from the same token or person "
        + "for the same request (this flow, version and parameter values) within 24 hours gives the run the first "
        + "request started (200, with Idempotent-Replayed: true) instead of another, so a retry never runs the "
        + "work twice; the key and its run are kept together or not at all. With Accept: text/plain the run comes as name: value lines (id, kind, name, status, "
        + "ended, page, message). Role: steward.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The run ended within the wait, or the key had started it; "
            + "the body is the run, and Location its address"),
        @APIResponse(responseCode = "202", description = "Queued (and, with wait, still going when the wait was "
            + "up); the body is the run, and Location its address"),
        @APIResponse(responseCode = "400", description = "A parameter it does not declare, a value of the wrong "
            + "kind, a parameter with no default left out, a wait that is not a whole number of seconds, or a key "
            + "that is not 1 to 200 printable characters"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "403", description = "Not a steward"),
        @APIResponse(responseCode = "404", description = "No such flow or version"),
        @APIResponse(responseCode = "409", description = "The version has problems that stop it running, or a run "
            + "is already queued or running"),
        @APIResponse(responseCode = "422", description = "The key started a different request in the last 24 "
            + "hours: another pipeline or flow, version or parameter values")})
    public Response run(@PathParam("name") String name, @QueryParam("wait") String wait,
                        @HeaderParam(RunWaits.KEY_HEADER) String key, FlowViews.RunRequest body,
                        @jakarta.ws.rs.core.Context jakarta.ws.rs.core.HttpHeaders headers,
                        @jakarta.ws.rs.core.Context io.vertx.core.http.HttpServerRequest request) {
        AccessRole role = role();
        Integer version = body == null ? null : body.version();
        Map<String, Object> parameters = body == null || body.parameters() == null ? Map.of() : body.parameters();
        return runRequests.start("flow", name, version, parameters, wait, key, identity, headers, request,
            () -> flows.checkRun(name, version, parameters, role), checked -> flows.queueChecked(checked,
                io.kilnmark.server.audit.AuditLog.requester(identity), role).id(),
            id -> flows.run(id).map(FlowStore.Run::status).orElse(null), id -> {
                FlowViews.RunSummary run = flows.runView(id, sights.data());
                return new RunRequests.Shown(run, run.flow(), run.status(), run.error());
            });
    }

    @GET
    @Path("{name}/runs")
    @Operation(summary = "A flow's runs", description = "The latest runs, newest first (20 by default, at most 200). "
        + "Role: viewer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The runs"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "404", description = "No such flow")})
    public List<FlowViews.RunSummary> runs(@PathParam("name") String name, @QueryParam("limit") Integer limit) {
        return flows.runViews(name, limit == null ? 20 : limit, sights.data());
    }

    private static String yaml(FlowViews.FlowChange body) {
        if (body == null) {
            throw new IllegalArgumentException("Send {\"yaml\": \"...\", \"message\": \"...\"}");
        }
        return body.yaml();
    }

    /** The version If-Match names, or null for none; read as for pipelines, refused in the flows' words. */
    private static Integer expected(String ifMatch) {
        try {
            return PipelineService.readVersion(ifMatch);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("If-Match: the flow's latest version as you last read it, for example "
                + "\"3\"");
        }
    }

    private AccessRole role() {
        return AccessRole.highest(identity::hasRole);
    }

    private static jakarta.ws.rs.core.EntityTag etag(FlowViews.FlowDetail d) {
        return new jakarta.ws.rs.core.EntityTag(Integer.toString(d.latestVersion()));
    }

    private String who() {
        return identity.isAnonymous() ? "anonymous" : identity.getPrincipal().getName().replaceAll("\\p{Cntrl}", "?");
    }
}

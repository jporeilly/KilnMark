package io.kilnmark.server.api;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;

import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.flows.FlowService;
import io.kilnmark.server.flows.FlowViews;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** One flow run with each of its nodes; and cancelling it. */
@Path("/api/v1/flow-runs")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AccessRole.VIEWER_ROLE)
public class FlowRunResource {

    private final FlowService flows;
    private final SecurityIdentity identity;

    /** Who is looking: the values a run started with, and messages quoting them, are for stewards and above. */
    @jakarta.inject.Inject
    io.kilnmark.server.quality.Sights sights;

    /** Each Run again from the failure is recorded here (ADR 0029, ADR 0053). */
    @jakarta.inject.Inject
    io.kilnmark.server.audit.AuditLog audit;

    /** Starting and reading runs for callers that wait, with request keys (ADR 0047). */
    @jakarta.inject.Inject
    RunRequests runRequests;

    public FlowRunResource(FlowService flows, SecurityIdentity identity) {
        this.flows = flows;
        this.identity = identity;
    }

    @GET
    @Path("{id}")
    @Produces({MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN})
    @RunOnVirtualThread
    @Operation(summary = "A flow run", description = "The run's status, timings, the parameter values it started with "
        + "and, for every node of its version in order: its status (pending until it starts, then running, "
        + "succeeded, failed, cancelled or skipped), when it started and ended, its message, what it reported, and "
        + "the pipeline run it started. A for-each node has items instead: each item's place, value, status "
        + "(pending, queued, running, succeeded, failed, cancelled or skipped; failed with no pipeline run when it "
        + "could not be queued) and pipeline run with its times. Messages are in Kilnmark's words, never a "
        + "pipeline's error: the pipeline run's own page shows that to those who may see it. Viewers do not see "
        + "the values the run started with, nor messages quoting them, nor items that came from those values, "
        + "which can hold server variables. With wait (whole seconds, at most kilnmark.api.max-wait, 15 minutes by "
        + "default) the answer comes when the run has ended or when the wait is up, whichever is first; the status "
        + "says which. With Accept: text/plain the run comes as name: value lines (id, kind, name, status, ended, "
        + "page, message). Role: viewer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The run", content = @Content(
            mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = FlowViews.RunDetail.class))),
        @APIResponse(responseCode = "400", description = "A wait that is not a whole number of seconds"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "404", description = "No such run"),
        @APIResponse(responseCode = "503", description = "With wait, on a run still going: as many requests as "
            + "allowed are waiting, on this server or for this caller; ask again after Retry-After seconds, or "
            + "without wait")})
    public Response get(@PathParam("id") long id, @QueryParam("wait") String wait,
                        @jakarta.ws.rs.core.Context jakarta.ws.rs.core.HttpHeaders headers,
                        @jakarta.ws.rs.core.Context io.vertx.core.http.HttpServerRequest request) {
        return runRequests.read("flow", id, wait, identity, headers, request, this::status, runId -> {
            FlowViews.RunSummary run = flows.runView(runId, sights.data());
            return new RunRequests.Shown(run, run.flow(), run.status(), run.error());
        }, () -> flows.runDetail(id, sights.data()));
    }

    /** The run's status now, or null when there is no such run. */
    private String status(long id) {
        return flows.run(id).map(io.kilnmark.server.flows.FlowStore.Run::status).orElse(null);
    }

    @POST
    @Path("{id}/cancel")
    @RolesAllowed(AccessRole.STEWARD_ROLE)
    @Operation(summary = "Cancel a flow run", description = "Asks the run to stop: a queued run is cancelled at once; "
        + "a running one starts nothing more, cancels the pipeline runs it started that are still going, and ends "
        + "cancelled within a moment. Role: steward.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Asked; the body is the run"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "403", description = "Not a steward"),
        @APIResponse(responseCode = "404", description = "No such run"),
        @APIResponse(responseCode = "409", description = "The run has already finished")})
    public FlowViews.RunSummary cancel(@PathParam("id") long id) {
        return flows.cancelView(id, who(), AccessRole.highest(identity::hasRole));
    }

    @POST
    @Path("{id}/run-again")
    @RolesAllowed(AccessRole.STEWARD_ROLE)
    @Operation(summary = "Run a failed flow run again from its failure", description = "One more try of the same run "
        + "(ADR 0053), with the same version, parameters and variables, acting as the run did: the nodes that "
        + "succeeded keep what they did, the rest run again, and a for-each node runs only its items that did not "
        + "succeed. Recorded against the run and in the audit log. Refused while another run of the flow is "
        + "queued or running, and when a node that would run again is not safe to (its pipeline appends, or a sql "
        + "node committed), naming the node and why. The try is not tried again by itself. Role: steward, and "
        + "never below the run's own role.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Queued as the run's next try; the body is the run"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "403", description = "Not a steward, or below the run's own role"),
        @APIResponse(responseCode = "404", description = "No such run"),
        @APIResponse(responseCode = "409", description = "The run did not fail, another run of the flow is going, "
            + "or running it again is not safe; the message says which node and why")})
    public FlowViews.RunSummary runAgain(@PathParam("id") long id) {
        FlowViews.RunSummary r = flows.runAgain(id, who(), AccessRole.highest(identity::hasRole));
        audit.record(io.kilnmark.server.audit.AuditLog.actor(identity), io.kilnmark.server.audit.AuditLog.FLOW_RUN_AGAIN,
            String.valueOf(id), java.util.Map.of("flow", r.flow(), "try", r.tryNumber(), "version", r.version()));
        return r;
    }

    private String who() {
        return identity.isAnonymous() ? "anonymous" : identity.getPrincipal().getName().replaceAll("\\p{Cntrl}", "?");
    }
}

package io.kilnmark.server.api;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;

import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.flows.FlowService;
import io.kilnmark.server.flows.FlowViews;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

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

    public FlowRunResource(FlowService flows, SecurityIdentity identity) {
        this.flows = flows;
        this.identity = identity;
    }

    @GET
    @Path("{id}")
    @Operation(summary = "A flow run", description = "The run's status, timings, the parameter values it started with "
        + "and, for every node of its version in order: its status (pending until it starts, then running, "
        + "succeeded, failed, cancelled or skipped), when it started and ended, its message, what it reported, and "
        + "the pipeline run it started. Messages are in Kilnmark's words, never a pipeline's error: the pipeline "
        + "run's own page shows that to those who may see it. Viewers do not see the values the run started with, "
        + "nor messages quoting them, which can hold server variables. Role: viewer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The run"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "404", description = "No such run")})
    public FlowViews.RunDetail get(@PathParam("id") long id) {
        return flows.runDetail(id, sights.data());
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

    private String who() {
        return identity.isAnonymous() ? "anonymous" : identity.getPrincipal().getName().replaceAll("\\p{Cntrl}", "?");
    }
}

package io.kilnmark.server.api;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;

import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.pipelines.PipelineService;
import io.kilnmark.server.pipelines.PipelineViews;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** One pipeline run: its steps, what it wrote and the lineage it recorded; and cancelling it. */
@Path("/api/v1/pipeline-runs")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AccessRole.VIEWER_ROLE)
public class PipelineRunResource {

    private final PipelineService pipelines;
    private final SecurityIdentity identity;

    /** Who is looking: a failed run's messages may quote values from the data (see PipelineService). */
    @jakarta.inject.Inject
    io.kilnmark.server.quality.Sights sights;

    public PipelineRunResource(PipelineService pipelines, SecurityIdentity identity) {
        this.pipelines = pipelines;
        this.identity = identity;
    }

    @GET
    @Path("{id}")
    @Operation(summary = "A pipeline run", description = "The run's status and timings and, per step, rows in and out "
        + "per port, peak memory, time and the error where it failed; the assets its writes wrote, whether they are "
        + "now catalogued, the assets it read and the lineage recorded between them. Role: viewer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The run"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "404", description = "No such run")})
    public PipelineViews.RunDetail get(@PathParam("id") long id) {
        return pipelines.run(id, sights);
    }

    @POST
    @Path("{id}/cancel")
    @RolesAllowed(AccessRole.STEWARD_ROLE)
    @Operation(summary = "Cancel a pipeline run", description = "A queued run is cancelled at once; a running one "
        + "stops within a few seconds (cancelRequested is true until it has). Rows already written stay written. "
        + "Role: steward.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The run as it now is"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "403", description = "Not a steward"),
        @APIResponse(responseCode = "404", description = "No such run"),
        @APIResponse(responseCode = "409", description = "The run has already finished")})
    public PipelineViews.RunSummary cancel(@PathParam("id") long id) {
        String who = identity.isAnonymous() ? "anonymous"
            : identity.getPrincipal().getName().replaceAll("\\p{Cntrl}", "?");
        return pipelines.cancel(id, who);
    }
}

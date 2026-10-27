package io.kilnmark.server.api;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;

import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.pipelines.PipelineService;
import io.kilnmark.server.pipelines.PipelineViews;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/** Asset lineage recorded by pipeline writes. */
@Path("/api/v1/lineage")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AccessRole.VIEWER_ROLE)
public class LineageResource {

    private final PipelineService pipelines;

    public LineageResource(PipelineService pipelines) {
        this.pipelines = pipelines;
    }

    @GET
    @Operation(summary = "An asset's lineage", description = "Where an asset came from (upstream: the assets read by "
        + "the pipeline runs that wrote it) and what was made from it (downstream), as pipeline writes recorded it, "
        + "newest first. Role: viewer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The lineage; empty lists when none is recorded"),
        @APIResponse(responseCode = "400", description = "urn is not an asset reference"),
        @APIResponse(responseCode = "401", description = "Not signed in")})
    public PipelineViews.AssetLineage get(@QueryParam("urn") String urn) {
        return pipelines.lineage(urn);
    }
}

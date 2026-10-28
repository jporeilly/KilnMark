package io.kilnmark.server.api;

import java.util.List;

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
import jakarta.ws.rs.core.MediaType;

/** The step catalogue: every type of step pipelines can use, for the editor and the canvas. */
@Path("/api/v1/steps")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AccessRole.VIEWER_ROLE)
public class StepResource {

    private final PipelineService pipelines;

    public StepResource(PipelineService pipelines) {
        this.pipelines = pipelines;
    }

    @GET
    @Operation(summary = "The step catalogue", description = "Every step type by type: its kind (source, transform, "
        + "gate or sink), label and help, its input and output ports (inputs in manyInputs take any number of edges) "
        + "and its settings schema: key, label, kind of value, whether required, default, help and choices. "
        + "The write step also has writerSettings: for each kind of source that can be written to, the keys of "
        + "the writer settings a step writing to such a source takes. category is the palette folder (Read, "
        + "Write, Filter and route, Transform, Combine, Aggregate, Quality, AI or Other). Role: viewer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The step types"),
        @APIResponse(responseCode = "401", description = "Not signed in")})
    public List<PipelineViews.StepType> list() {
        return pipelines.catalogue();
    }
}

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

/** The node catalogue: every type of node pipelines can use, for the editor and the canvas. */
@Path("/api/v1/node-types")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AccessRole.VIEWER_ROLE)
public class NodeResource {

    private final PipelineService pipelines;

    public NodeResource(PipelineService pipelines) {
        this.pipelines = pipelines;
    }

    @GET
    @Operation(summary = "The node catalogue", description = "Every node type by type: its kind (source, transform, "
        + "gate or sink), label and help, its input and output ports (inputs in manyInputs take any number of edges) "
        + "and its settings schema: key, label, kind of value, whether required, default, help and choices. "
        + "The write node also has writerSettings: for each kind of source that can be written to, the keys of "
        + "the writer settings a node writing to such a source takes. category is the palette folder (Read, "
        + "Write, Filter and route, Transform, Combine, Aggregate, Quality, AI or Other). Role: viewer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The node types"),
        @APIResponse(responseCode = "401", description = "Not signed in")})
    public List<PipelineViews.NodeType> list() {
        return pipelines.catalogue();
    }
}

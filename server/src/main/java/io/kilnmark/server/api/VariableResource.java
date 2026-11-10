package io.kilnmark.server.api;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.Operation;

import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.variables.VariableService;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Server variables: values every pipeline and flow on this server reads as {@code ${vars.name}}
 * (docs/design/pipelines.md, "Parameters and server variables"). Stewards and above read them; designers and admins set them.
 */
@Path("/api/v1/variables")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AccessRole.STEWARD_ROLE)
public class VariableResource {

    /** {@code PUT /api/v1/variables/{name}}: the type (text, number or boolean), the value and a description. */
    public record SetVariable(String type, Object value, String description) {
    }

    private final VariableService variables;
    private final SecurityIdentity identity;

    public VariableResource(VariableService variables, SecurityIdentity identity) {
        this.variables = variables;
        this.identity = identity;
    }

    @GET
    @Operation(summary = "Server variables", description = "Every variable, by name. Role: steward.")
    public List<VariableService.View> list() {
        return variables.list(role());
    }

    @GET
    @Path("{name}")
    @Operation(summary = "One server variable", description = "Role: steward.")
    public VariableService.View get(@PathParam("name") String name) {
        return variables.get(name, role());
    }

    @GET
    @Path("{name}/changes")
    @Operation(summary = "A server variable's changes", description = "The newest 100 first, also after it was "
        + "deleted: the value before and after, who changed it and when. Role: steward.")
    public List<VariableService.ChangeView> changes(@PathParam("name") String name) {
        return variables.changes(name, role());
    }

    @PUT
    @Path("{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Set a server variable", description = "Creates or replaces it. Runs already queued keep "
        + "the value they started with. Never a secret: a name that looks like a credential is refused. "
        + "Role: designer.")
    public VariableService.View set(@PathParam("name") String name, SetVariable body) {
        if (body == null) {
            throw new IllegalArgumentException("Send the variable's type and value, such as {\"type\": \"text\", "
                + "\"value\": \"EMEA\"}");
        }
        return variables.set(name, body.type(), body.value(), body.description(), who(), role());
    }

    @DELETE
    @Path("{name}")
    @Operation(summary = "Delete a server variable", description = "Pipelines that name it cannot run until it is "
        + "set again. Its changes are kept. Role: designer.")
    public Response delete(@PathParam("name") String name) {
        variables.delete(name, who(), role());
        return Response.noContent().build();
    }

    private AccessRole role() {
        return AccessRole.highest(identity::hasRole);
    }

    private String who() {
        return identity.isAnonymous() ? "anonymous" : identity.getPrincipal().getName().replaceAll("\\p{Cntrl}", "?");
    }
}

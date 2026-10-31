package io.kilnmark.server.api;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;

import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.pipelines.Samples;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The samples that ship with Kilnmark: small pipelines and flows, one idea each, that name their
 * sources by placeholders. Viewers list them; stewards add them, choosing a saved source for each
 * placeholder.
 */
@Path("/api/v1/samples")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AccessRole.VIEWER_ROLE)
public class SampleResource {

    private final Samples samples;
    private final SecurityIdentity identity;

    @jakarta.inject.Inject
    io.kilnmark.server.quality.Sights sights;

    public SampleResource(Samples samples, SecurityIdentity identity) {
        this.samples = samples;
        this.identity = identity;
    }

    @GET
    @Operation(summary = "List the samples", description = "The placeholders the samples name instead of real sources "
        + "(each with the kind of source it stands for and what it should hold), and every sample: its file, name, "
        + "whether it is a pipeline or a flow, the placeholders it uses, and whether a pipeline of its name is "
        + "already there. Role: viewer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The placeholders and the samples"),
        @APIResponse(responseCode = "401", description = "Not signed in")})
    public Samples.Catalogue list() {
        return samples.catalogue(sights);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(AccessRole.STEWARD_ROLE)
    @Operation(summary = "Add the samples", description = "Adds every sample pipeline whose placeholders are all given "
        + "a saved source in sources, such as {\"sources\": {\"crm-db\": \"my-postgres\", \"files\": \"my-bucket\"}}. "
        + "A placeholder left out skips the samples that use it. A sample whose name is taken is left as it is; one "
        + "that checks with problems (a table the source's scan did not find, a source that does not allow writing) is "
        + "saved as a draft; one that cannot be saved at all is reported as failed, with why, and the rest are still "
        + "added. Flows are skipped until the flows API comes. Role: steward.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "What happened to each sample: added, exists, skipped or failed"),
        @APIResponse(responseCode = "400", description = "No source chosen, a name that is not a placeholder, or a "
            + "source that is not saved"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "403", description = "Not a steward")})
    public Samples.AddResult add(Samples.AddRequest body) {
        return samples.add(body, who());
    }

    private String who() {
        return identity.isAnonymous() ? "anonymous" : identity.getPrincipal().getName().replaceAll("\\p{Cntrl}", "?");
    }
}

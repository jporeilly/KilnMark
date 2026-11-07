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
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The samples that ship with Kilnmark: small pipelines and flows, one idea each, that name their
 * sources by placeholders. Viewers list them; stewards add them, choosing a saved source for each
 * placeholder. An admin can stop offering them on this server: everything here but the switch is
 * then refused (403), and the Pipelines page shows no Add samples.
 */
@Path("/api/v1/samples")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AccessRole.VIEWER_ROLE)
public class SampleResource {

    private final Samples samples;
    private final SecurityIdentity identity;

    @jakarta.inject.Inject
    io.kilnmark.server.quality.Sights sights;

    @jakarta.inject.Inject
    io.kilnmark.server.ServerSettings settings;

    /** {@code GET} and {@code PUT /api/v1/samples/offered}: whether this server offers the samples. */
    public record Offered(Boolean offered) {
    }

    @GET
    @Path("offered")
    @Operation(summary = "Whether the samples are offered", description = "Whether this server offers the samples "
        + "(Add samples, the starter pack and the sample data). On unless an admin turned it off. Role: viewer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Whether the samples are offered"),
        @APIResponse(responseCode = "401", description = "Not signed in")})
    public Offered offered() {
        return new Offered(settings.samplesOffered());
    }

    @PUT
    @Path("offered")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(AccessRole.ADMIN_ROLE)
    @Operation(summary = "Offer the samples or not", description = "Turns the samples on or off for this server: "
        + "send {\"offered\": false} to stop offering Add samples, the starter pack and the sample data. Pipelines "
        + "already added from the samples stay as they are. Role: admin.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Whether the samples are offered now"),
        @APIResponse(responseCode = "400", description = "No offered value"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "403", description = "Not an admin")})
    public Offered setOffered(Offered body) {
        if (body == null || body.offered() == null) {
            throw new IllegalArgumentException("offered: send {\"offered\": true} or {\"offered\": false}");
        }
        settings.put(io.kilnmark.server.ServerSettings.SAMPLES_OFFERED, body.offered().toString(), who());
        return new Offered(body.offered());
    }

    /** Everything about the samples but the switch: refused while an admin has them turned off. */
    private void requireOffered() {
        if (!settings.samplesOffered()) {
            throw ApiError.of(Response.Status.FORBIDDEN, "The samples are not offered on this server: an admin turns "
                + "them on under Settings, Administration");
        }
    }

    public SampleResource(Samples samples, SecurityIdentity identity) {
        this.samples = samples;
        this.identity = identity;
    }

    @GET
    @Operation(summary = "List the samples", description = "The placeholders the samples name instead of real sources "
        + "(each with the kind of source it stands for and what it should hold), and every sample: its file, name, "
        + "whether it is a pipeline or a flow, the placeholders it uses, and whether a pipeline of its name is "
        + "already there; and, once the sample tables have been created, the database and schema they were created "
        + "in, with the last run that created them. Role: viewer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The placeholders and the samples"),
        @APIResponse(responseCode = "401", description = "Not signed in")})
    public Samples.Catalogue list() {
        requireOffered();
        return samples.catalogue(sights);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(AccessRole.DESIGNER_ROLE)
    @Operation(summary = "Add the samples", description = "Adds every sample pipeline and flow whose placeholders are all "
        + "given a saved source in sources, marked as samples for the Library page, such as {\"sources\": {\"crm-db\": \"my-postgres\", \"files\": \"my-bucket\"}}. "
        + "A placeholder left out skips the samples that use it. A sample whose name is taken is left as it is; one "
        + "that checks with problems (a table the source's scan did not find, a source that does not allow writing) is "
        + "saved as a draft; one that cannot be saved at all is reported as failed, with why, and the rest are still "
        + "added. Flows are added after the pipelines they run. With schema, the samples read the sample tables "
        + "created there (POST /api/v1/samples/tables) instead of crm.customers and the like. Role: designer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "What happened to each sample: added, exists, skipped or failed"),
        @APIResponse(responseCode = "400", description = "No source chosen, a name that is not a placeholder, or a "
            + "source that is not saved"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "403", description = "Not a designer")})
    public Samples.AddResult add(Samples.AddRequest body) {
        requireOffered();
        return samples.add(body, who(), AccessRole.highest(identity::hasRole), sights);
    }

    @POST
    @Path("tables")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(AccessRole.DESIGNER_ROLE)
    @Operation(summary = "Create the sample tables", description = "Copies Kilnmark's sample data (synthetic "
        + "customers, accounts, transactions, orders and products) into one schema of a database, as tables named "
        + "crm_customers, shop_orders and so on, with a pipeline called sample-tables, and queues a run of it. The "
        + "database must be a saved database source that allows writing; the sample data's own read-only source, "
        + "sample-data, is added if it is not there. Run it again to put the tables back as they were. Send "
        + "{\"source\": \"my-postgres\", \"schema\": \"public\"}. Role: designer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The pipeline saved and its run queued"),
        @APIResponse(responseCode = "400", description = "No such source, not a database, a database that does not "
            + "allow writing, or a schema name that cannot be used"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "403", description = "Not a designer")})
    public Samples.TablesResult tables(Samples.TablesRequest body) {
        requireOffered();
        AccessRole role = AccessRole.highest(identity::hasRole);
        // its run names who asked for it as runs do: a token by its id (ADR 0051)
        return samples.createTables(body, io.kilnmark.server.audit.AuditLog.requester(identity), role, sights);
    }

    private String who() {
        return identity.isAnonymous() ? "anonymous" : identity.getPrincipal().getName().replaceAll("\\p{Cntrl}", "?");
    }
}

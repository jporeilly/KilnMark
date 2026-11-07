package io.kilnmark.server.api;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;

import io.kilnmark.server.audit.AuditLog;
import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.quality.Sights;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * The Starter Pack (ADR 0051): example data for trials, demos and training. Everyone signed in reads whether it is on, for the banner; admins turn it on and
 * off (Settings, Starter Pack), and Kilnmark does the work itself ({@link StarterPack}).
 */
@Path("/api/v1/starter-pack")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AccessRole.VIEWER_ROLE)
public class StarterPackResource {

    /** {@code DELETE /api/v1/starter-pack}: {@code {"confirm": "DELETE"}}. */
    public record Off(String confirm) {
    }

    @Inject
    StarterPack pack;

    @Inject
    SecurityIdentity identity;

    @Inject
    Sights sights;

    @GET
    @Operation(summary = "Whether the Starter Pack is on", description = "Whether the Starter Pack (example data and "
        + "accounts, not for production) is on, and since when. The app shows a banner while it is. Who turned it on "
        + "is under details, for admins (by is always null here). Role: viewer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Whether it is on"),
        @APIResponse(responseCode = "401", description = "Not signed in")})
    public StarterPack.State state() {
        StarterPack.State s = pack.state();
        return new StarterPack.State(s.on(), s.since(), null); // who: an admin's sign-in or a token, for admins only
    }

    @GET
    @Path("details")
    @RolesAllowed(AccessRole.ADMIN_ROLE)
    @Operation(summary = "The Starter Pack in full", description = "Whether it is on; whether this install is marked "
        + "production (KILNMARK_PRODUCTION), where the pack is refused; whether it can be turned on here and why not; "
        + "its database (kilnmark_samples) and files folder; the last run creating the sample tables; how many samples "
        + "are in the Library; and, for the example people, which the control script makes and removes (Kilnmark "
        + "holds no Keycloak credential), the commands and the kilnmark.com accounts that have signed in. Role: admin.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The pack"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "403", description = "Not an admin")})
    public StarterPack.Details details() {
        return pack.details(sights);
    }

    @POST
    @RolesAllowed(AccessRole.ADMIN_ROLE)
    @Operation(summary = "Turn the Starter Pack on", description = "Adds the pack: a database source on "
        + "kilnmark_samples (its own database on Kilnmark's PostgreSQL server, reached with its own login) and a files "
        + "source on the pack's folder in the files folder, both with Allow writing on; the sample tables (the pipeline "
        + "sample-tables, run at once) and every sample into the Library. A source of either name that the pack did "
        + "not make is left as it is. Safe to repeat. Refused (409) on an install marked production (KILNMARK_PRODUCTION=true) "
        + "unless force=true, which the audit event records. Answers with each step and how it went. Role: admin.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Each step and how it went"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "403", description = "Not an admin"),
        @APIResponse(responseCode = "409", description = "Marked production, the samples not offered, or no database "
            + "for the pack here")})
    public StarterPack.Result turnOn(@QueryParam("force") boolean force) {
        return pack.turnOn(force, who(), AccessRole.highest(identity::hasRole), sights, AuditLog.actor(identity));
    }

    @DELETE
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(AccessRole.ADMIN_ROLE)
    @Operation(summary = "Turn the Starter Pack off", description = "Removes what the pack added, once confirmed with "
        + "{\"confirm\": \"DELETE\"}: every sample pipeline and flow in the Library, the pack's two sources (and "
        + "Kilnmark's sample data source unless your own pipelines or flows read it), the tables in kilnmark_samples "
        + "(the database stays, empty) and the files in the pack's folder. Nothing else: the example people are the "
        + "control script's to remove. Safe to repeat. Answers with each step and how it went; the banner goes once "
        + "every step has worked. Role: admin.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Each step and how it went"),
        @APIResponse(responseCode = "400", description = "Not confirmed with DELETE"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "403", description = "Not an admin")})
    public StarterPack.Result turnOff(Off body) {
        return pack.turnOff(body == null ? null : body.confirm(), who(), AccessRole.highest(identity::hasRole), sights,
            AuditLog.actor(identity));
    }

    /** Who, as the pack's state and the sample-tables run name them: a token by its id (ADR 0051). */
    private String who() {
        return io.kilnmark.server.audit.AuditLog.requester(identity);
    }
}

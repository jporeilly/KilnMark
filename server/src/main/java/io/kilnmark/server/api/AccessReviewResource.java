package io.kilnmark.server.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;

import io.kilnmark.server.audit.AuditLog;
import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.auth.ApiTokens;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The access review (ADR 0037): who can do what in Kilnmark, for the periodic review a bank's
 * auditors ask for. Every API token with its role, unmask grant, status, expiry and last use, and
 * everyone who has signed in with the identity provider with the role and grant of their last
 * sign-in. Admins only; each view and download is in the audit log.
 */
@Path("/api/v1/access-review")
@RolesAllowed(AccessRole.ADMIN_ROLE)
public class AccessReviewResource {

    /** One line of the review: a token, or a person who signs in with the identity provider. */
    public record Entry(String kind, String name, String role, boolean unmask, String status, String createdBy,
                        Instant createdAt, Instant expiresAt, Instant lastUsedAt, Long signIns, String unmaskApprovedBy) {
    }

    /** The review: when it was made, the longest token lifetime the server allows (days, or null), and every entry. */
    public record Review(Instant generatedAt, Long tokenMaxLifetimeDays, List<Entry> entries) {
    }

    @Inject
    ApiTokens tokens;

    @Inject
    AuditLog audit;

    @Inject
    SecurityIdentity identity;

    @GET
    @Produces({MediaType.APPLICATION_JSON, "text/csv"})
    @Operation(summary = "The access review", description = "Every API token (role, unmask grant, status, who "
        + "created it, expiry, last use) and everyone who has signed in with the identity provider (the role and "
        + "grant of their last sign-in, when, and how many times). format=csv for a spreadsheet. Role: admin; "
        + "each view and download is recorded in the audit log.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The review"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "403", description = "Not an admin")})
    public Response review(@QueryParam("format") String format) {
        boolean csv = "csv".equalsIgnoreCase(format);
        Review r = build(Instant.now());
        audit.record(AuditLog.actor(identity), AuditLog.ACCESS_REVIEW, "access review",
            Map.of("format", csv ? "csv" : "json", "entries", r.entries().size()));
        CacheControl noStore = new CacheControl();
        noStore.setNoStore(true);
        if (!csv) {
            return Response.ok(r, MediaType.APPLICATION_JSON).cacheControl(noStore).build();
        }
        return Response.ok(csv(r), "text/csv; charset=utf-8").cacheControl(noStore)
            .header("Content-Disposition", "attachment; filename=\"kilnmark-access-review.csv\"").build();
    }

    Review build(Instant now) {
        List<Entry> out = new ArrayList<>();
        for (ApiTokens.Token t : tokens.list()) {
            out.add(new Entry("token", t.name(), t.role().id(), t.unmask(), t.status(now), t.createdBy(),
                t.createdAt(), t.expiresAt(), t.lastUsedAt(), null, t.unmaskApprovedBy()));
        }
        for (AuditLog.ProviderAccess p : audit.providerAccess()) {
            out.add(new Entry("provider", p.name(), p.role(), p.unmask(), p.refused() == null
                ? "signs in with the identity provider" : p.role() == null ? "no group gives a role any more"
                : "last sign-in refused: " + p.refused(), null, null, null, p.lastSignIn(), p.signIns(), null));
        }
        Long days = tokens.maxLifetime().map(d -> d.toDays()).orElse(null);
        return new Review(now, days, out);
    }

    /** The review as CSV, one entry a row; cells that a spreadsheet would run as a formula are quoted safe. */
    static String csv(Review r) {
        StringBuilder b = new StringBuilder("kind,name,role,unmask,status,created_by,created_at,expires_at,"
            + "last_used_at,sign_ins,unmask_approved_by\r\n");
        for (Entry e : r.entries()) {
            b.append(String.join(",", cell(e.kind()), cell(e.name()), cell(e.role()), cell(String.valueOf(e.unmask())),
                cell(e.status()), cell(e.createdBy()), cell(e.createdAt()), cell(e.expiresAt()), cell(e.lastUsedAt()),
                cell(e.signIns() == null ? null : e.signIns().toString()), cell(e.unmaskApprovedBy())))
                .append("\r\n");
        }
        return b.toString();
    }

    /** {@link #csv} for tests in other packages. */
    public static String csvForTests(Review r) {
        return csv(r);
    }

    private static String cell(Object value) {
        if (value == null) {
            return "";
        }
        String s = value.toString();
        // A name a person chose could start a formula (=, +, -, @): a leading quote keeps it text
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}

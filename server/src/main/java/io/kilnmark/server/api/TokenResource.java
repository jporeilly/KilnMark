package io.kilnmark.server.api;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import org.eclipse.microprofile.jwt.JsonWebToken;

import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.auth.ApiTokenIdentityProvider;
import io.kilnmark.server.auth.ApiTokens;
import io.kilnmark.server.auth.AuthMode;
import io.kilnmark.server.auth.OidcIdentityAugmentor;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** API tokens: who is signed in, and (for admins) creating, listing and revoking tokens. */
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
public class TokenResource {

    /**
     * Body of {@code POST /api/v1/tokens}. {@code expiresAt} is optional: leave it out for no expiry.
     * {@code unmask} is optional: a designer token sees unmasked values unless it is false; no other
     * role's token may have it true (ADR 0027).
     */
    public record NewToken(String name, String role, Instant expiresAt, Boolean unmask) {
    }

    private final ApiTokens tokens;
    private final SecurityIdentity identity;
    @jakarta.inject.Inject
    io.kilnmark.server.audit.AuditLog audit;
    private final KilnmarkConfig config;

    public TokenResource(ApiTokens tokens, SecurityIdentity identity, KilnmarkConfig config) {
        this.tokens = tokens;
        this.identity = identity;
        this.config = config;
    }

    /**
     * Who is signed in: the name Kilnmark records them by, the name to greet them by, and their role.
     * The web UI uses this to sign in.
     */
    @GET
    @Path("me")
    @RolesAllowed(AccessRole.VIEWER_ROLE)
    public Views.Me me() {
        AccessRole role = identity.getAttribute(ApiTokenIdentityProvider.ROLE);
        Long id = identity.getAttribute(ApiTokenIdentityProvider.TOKEN_ID);
        Instant expires = identity.getAttribute(ApiTokenIdentityProvider.EXPIRES_AT);
        boolean off = config.auth().mode() == AuthMode.OFF;
        boolean provider = !off && OidcIdentityAugmentor.fromProvider(identity);
        String via = off ? "off" : provider ? "provider" : "token";
        String name = identity.getPrincipal().getName();
        String shown = provider ? OidcIdentityAugmentor.displayName((JsonWebToken) identity.getPrincipal()) : name;
        return new Views.Me(name, shown, role.id(), id == null || id == 0 ? null : id, expires, !off,
            identity.hasRole(AccessRole.UNMASK_GRANT), via);
    }

    /** Creates a token and returns it this once; only its hash is kept. */
    @POST
    @Path("tokens")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(AccessRole.ADMIN_ROLE)
    public Response create(NewToken body) {
        if (config.auth().mode() == AuthMode.OFF) {
            throw ApiError.of(Response.Status.CONFLICT, "Sign-in is off (kilnmark.auth.mode=off), so tokens would not "
                + "be checked. Remove KILNMARK_AUTH_MODE and restart the server, then create tokens.");
        }
        if (body == null) {
            throw new IllegalArgumentException("Send the token as JSON: a name, a role (viewer, steward, designer or "
                + "admin), and optionally expiresAt and unmask (designer tokens only)");
        }
        ApiTokens.Issued issued = tokens.create(body.name(), AccessRole.parse(body.role()), body.expiresAt(), caller(),
            body.unmask(), io.kilnmark.server.auth.OidcIdentityAugmentor.personKey(identity));
        ApiTokens.Token t = issued.token();
        java.util.Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("name", t.name());
        detail.put("role", t.role().id());
        detail.put("unmask", t.unmask());
        detail.put("expiresAt", t.expiresAt() == null ? null : t.expiresAt().toString());
        audit.record(io.kilnmark.server.audit.AuditLog.actor(identity), io.kilnmark.server.audit.AuditLog.TOKEN_CREATED, "token " + t.id(), detail);
        if (t.unmaskRequestedBy() != null) {
            audit.record(io.kilnmark.server.audit.AuditLog.actor(identity),
                io.kilnmark.server.audit.AuditLog.UNMASK_REQUESTED, "token " + t.id(), java.util.Map.of("name", t.name()));
        }
        CacheControl noStore = new CacheControl();
        noStore.setNoStore(true);
        return Response.created(URI.create("/api/v1/tokens/" + t.id())).cacheControl(noStore)
            .entity(new Views.NewToken(t.id(), t.name(), t.role().id(), t.unmask(), t.createdAt(), t.expiresAt(),
                issued.secret(), t.unmaskRequestedBy()))
            .build();
    }

    /**
     * The server's token policy: the longest a token may last, in days, or null for no limit (ADR
     * 0037), and whether a second admin must approve the unmask grant (ADR 0038).
     */
    public record TokenPolicy(Long maxLifetimeDays, boolean unmaskApproval) {
    }

    /** What a new token may be: the longest lifetime the server allows. The UI offers only expiries within it. */
    @GET
    @Path("tokens/policy")
    @RolesAllowed(AccessRole.ADMIN_ROLE)
    public TokenPolicy policy() {
        return new TokenPolicy(tokens.maxLifetime().map(d -> d.toDays()).orElse(null), tokens.unmaskApproval());
    }

    /** Every token, newest first, with its status. Secrets are never returned. */
    @GET
    @Path("tokens")
    @RolesAllowed(AccessRole.ADMIN_ROLE)
    public List<Views.Token> list() {
        Instant now = Instant.now();
        return tokens.list().stream().map(t -> Views.Token.of(t, now)).toList();
    }

    /**
     * Gives a designer token the unmask grant it asked for (ADR 0038). Only a second person, signed in
     * with the identity provider, may: the one who asked, and any token, are refused (403).
     */
    @POST
    @Path("tokens/{id}/unmask/approve")
    @RolesAllowed(AccessRole.ADMIN_ROLE)
    public Views.Token approveUnmask(@PathParam("id") long id) {
        signInOn();
        return decided(id, tokens.approveUnmask(id, caller(), io.kilnmark.server.auth.OidcIdentityAugmentor
            .personKey(identity)), io.kilnmark.server.audit.AuditLog.UNMASK_APPROVED);
    }

    /** Refuses a designer token's request for the unmask grant; the token stays, without it. */
    @POST
    @Path("tokens/{id}/unmask/refuse")
    @RolesAllowed(AccessRole.ADMIN_ROLE)
    public Views.Token refuseUnmask(@PathParam("id") long id) {
        signInOn();
        return decided(id, tokens.refuseUnmask(id, caller()), io.kilnmark.server.audit.AuditLog.UNMASK_REFUSED);
    }

    /** With sign-in off every caller is an admin, so no decision about the grant can stand for a person. */
    private void signInOn() {
        if (config.auth().mode() == AuthMode.OFF) {
            throw ApiError.of(Response.Status.CONFLICT, "Sign-in is off (kilnmark.auth.mode=off), so no one can be "
                + "told apart. Turn sign-in on to approve or refuse sensitive values.");
        }
    }

    private Views.Token decided(long id, ApiTokens.GrantDecision d, String action) {
        if (d.problem() != null) {
            throw switch (d.problem()) {
                case NOT_FOUND -> ApiError.of(Response.Status.NOT_FOUND, "No token " + id + " that still works: it "
                    + "may have been revoked or have expired");
                case NOTHING_ASKED -> ApiError.of(Response.Status.CONFLICT, "Token " + id + " is not waiting for the "
                    + "unmask grant: it was already approved or refused, or never asked for it");
                case SAME_PERSON -> ApiError.of(Response.Status.FORBIDDEN, "You asked for this grant, so another "
                    + "person must approve it: seeing sensitive values needs two people (kilnmark.auth.unmask-approval)");
                case NOT_A_PERSON -> ApiError.of(Response.Status.FORBIDDEN, "Approve this as a person signed in with "
                    + "your identity provider: a token names no one, so it cannot be the second person");
            };
        }
        ApiTokens.Token t = d.token();
        java.util.Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("name", t.name());
        detail.put("askedBy", d.askedBy());
        audit.record(io.kilnmark.server.audit.AuditLog.actor(identity), action, "token " + t.id(), detail);
        return Views.Token.of(t, Instant.now());
    }

    /** Revokes a token at once. Revoking it again changes nothing. */
    @DELETE
    @Path("tokens/{id}")
    @RolesAllowed(AccessRole.ADMIN_ROLE)
    public Response revoke(@PathParam("id") long id) {
        ApiTokens.Token t = tokens.revoke(id, caller()).orElseThrow(() -> ApiError.of(Response.Status.NOT_FOUND,
            "No token " + id));
        audit.record(io.kilnmark.server.audit.AuditLog.actor(identity), io.kilnmark.server.audit.AuditLog.TOKEN_REVOKED, "token " + t.id(),
            java.util.Map.of("name", t.name(), "role", t.role().id()));
        return Response.noContent().build();
    }

    private String caller() {
        Long id = identity.getAttribute(ApiTokenIdentityProvider.TOKEN_ID);
        String name = identity.getPrincipal().getName();
        return id == null || id == 0 ? name : "token " + id + " (" + name + ")";
    }
}

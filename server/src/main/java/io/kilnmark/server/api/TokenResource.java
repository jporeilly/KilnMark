package io.kilnmark.server.api;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.auth.ApiTokenIdentityProvider;
import io.kilnmark.server.auth.ApiTokens;
import io.kilnmark.server.auth.AuthMode;
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

    /** The token in use: its name and role. The web UI uses this to sign in. */
    @GET
    @Path("me")
    @RolesAllowed(AccessRole.VIEWER_ROLE)
    public Views.Me me() {
        AccessRole role = identity.getAttribute(ApiTokenIdentityProvider.ROLE);
        Long id = identity.getAttribute(ApiTokenIdentityProvider.TOKEN_ID);
        Instant expires = identity.getAttribute(ApiTokenIdentityProvider.EXPIRES_AT);
        boolean off = config.auth().mode() == AuthMode.OFF;
        String via = off ? "off" : io.kilnmark.server.auth.OidcIdentityAugmentor.fromProvider(identity) ? "provider"
            : "token";
        return new Views.Me(identity.getPrincipal().getName(), role.id(), id == null || id == 0 ? null : id, expires,
            !off, identity.hasRole(AccessRole.UNMASK_GRANT), via);
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
            body.unmask());
        ApiTokens.Token t = issued.token();
        java.util.Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("name", t.name());
        detail.put("role", t.role().id());
        detail.put("unmask", t.unmask());
        detail.put("expiresAt", t.expiresAt() == null ? null : t.expiresAt().toString());
        audit.record(io.kilnmark.server.audit.AuditLog.actor(identity), io.kilnmark.server.audit.AuditLog.TOKEN_CREATED, "token " + t.id(), detail);
        CacheControl noStore = new CacheControl();
        noStore.setNoStore(true);
        return Response.created(URI.create("/api/v1/tokens/" + t.id())).cacheControl(noStore)
            .entity(new Views.NewToken(t.id(), t.name(), t.role().id(), t.unmask(), t.createdAt(), t.expiresAt(),
                issued.secret()))
            .build();
    }

    /** Every token, newest first, with its status. Secrets are never returned. */
    @GET
    @Path("tokens")
    @RolesAllowed(AccessRole.ADMIN_ROLE)
    public List<Views.Token> list() {
        Instant now = Instant.now();
        return tokens.list().stream().map(t -> Views.Token.of(t, now)).toList();
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

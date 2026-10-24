package io.kilnmark.server.api;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.auth.ApiTokenIdentityProvider;
import io.kilnmark.server.auth.ApiTokens;
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

    /** Body of {@code POST /api/v1/tokens}. {@code expiresAt} is optional: leave it out for no expiry. */
    public record NewToken(String name, String role, Instant expiresAt) {
    }

    private final ApiTokens tokens;
    private final SecurityIdentity identity;
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
        return new Views.Me(identity.getPrincipal().getName(), role.id(), id == null || id == 0 ? null : id, expires,
            config.auth().enabled());
    }

    /** Creates a token and returns it this once; only its hash is kept. */
    @POST
    @Path("tokens")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(AccessRole.ADMIN_ROLE)
    public Response create(NewToken body) {
        if (body == null) {
            throw new IllegalArgumentException("Send the token as JSON: a name, a role (viewer, steward or admin) "
                + "and optionally expiresAt");
        }
        ApiTokens.Issued issued = tokens.create(body.name(), AccessRole.parse(body.role()), body.expiresAt(), caller());
        ApiTokens.Token t = issued.token();
        CacheControl noStore = new CacheControl();
        noStore.setNoStore(true);
        return Response.created(URI.create("/api/v1/tokens/" + t.id())).cacheControl(noStore)
            .entity(new Views.NewToken(t.id(), t.name(), t.role().id(), t.createdAt(), t.expiresAt(), issued.secret()))
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
        tokens.revoke(id, caller()).orElseThrow(() -> ApiError.of(Response.Status.NOT_FOUND, "No token " + id));
        return Response.noContent().build();
    }

    private String caller() {
        Long id = identity.getAttribute(ApiTokenIdentityProvider.TOKEN_ID);
        String name = identity.getPrincipal().getName();
        return id == null || id == 0 ? name : "token " + id + " (" + name + ")";
    }
}

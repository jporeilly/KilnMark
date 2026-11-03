package io.kilnmark.server.api;

import java.net.URI;
import java.util.Map;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;

import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.audit.AuditLog;
import io.kilnmark.server.auth.OidcIdentityAugmentor;
import io.quarkus.oidc.OidcSession;
import io.quarkus.security.Authenticated;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Sign-in with the customer's identity provider (ADR 0036): whether it is set up, starting it, and
 * signing out. API tokens are unchanged; this is for people in a browser.
 */
@Path("/api/v1/auth")
@Produces(MediaType.APPLICATION_JSON)
public class AuthResource {

    @Inject
    SecurityIdentity identity;

    @Inject
    KilnmarkConfig config;

    @Inject
    AuditLog audit;

    @Inject
    jakarta.enterprise.inject.Instance<OidcSession> session;

    /** Whether sign-in with the identity provider is on: Quarkus's OIDC client has a tenant. */
    @ConfigProperty(name = "quarkus.oidc.tenant-enabled", defaultValue = "false")
    boolean provider;

    @GET
    @Path("status")
    @PermitAll
    @Operation(summary = "Sign-in status", description = "Whether sign-in with the organisation's identity "
        + "provider is set up, and, for a browser with a session, whether it gives a role or why not. Open to all.")
    @APIResponses({@APIResponse(responseCode = "200", description = "The status")})
    public Views.AuthStatus status() {
        String name = config.auth().oidc().providerName();
        try {
            if (identity.isAnonymous() || !OidcIdentityAugmentor.fromProvider(identity)) {
                return new Views.AuthStatus(provider, name, false, null);
            }
            String refusal = identity.getAttribute(OidcIdentityAugmentor.REFUSAL);
            return new Views.AuthStatus(provider, name, true, refusal);
        } catch (AuthenticationFailedException e) {
            // a session older than the longest one allowed: the person signs in again
            return new Views.AuthStatus(provider, name, false, io.kilnmark.server.auth.OidcAccess.TOO_OLD);
        }
    }

    /**
     * Protected at the HTTP layer, not here: {@code quarkus.http.auth.permission.sign-in} requires a
     * provider sign-in on this path, so the OIDC client redirects to the provider before this runs
     * (Kilnmark's own error mappers would otherwise answer 401 in its place).
     */
    @GET
    @Path("login")
    @PermitAll
    @Operation(summary = "Sign in with the identity provider", description = "Sends the browser to the "
        + "organisation's identity provider, and back to Kilnmark once signed in. For browsers, not scripts: "
        + "scripts use API tokens.")
    @APIResponses({@APIResponse(responseCode = "303", description = "Signed in; on to the web UI")})
    public Response login() {
        return Response.seeOther(URI.create("/")).build();
    }

    @POST
    @Path("sign-out")
    @Authenticated
    @Operation(summary = "Sign out", description = "Ends this browser's session with Kilnmark. The identity "
        + "provider's own session is the provider's: sign out there too on a shared computer.")
    @APIResponses({@APIResponse(responseCode = "204", description = "Signed out")})
    public Response signOut() {
        if (OidcIdentityAugmentor.fromProvider(identity) && session.isResolvable()) {
            audit.record(AuditLog.actor(identity), AuditLog.SIGN_OUT, "session", Map.of());
            session.get().logout().await().indefinitely();
        }
        return Response.noContent().build();
    }
}

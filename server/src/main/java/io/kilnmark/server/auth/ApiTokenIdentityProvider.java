package io.kilnmark.server.auth;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Turns a valid API token into a security identity with its role, every role that role includes,
 * and {@link AccessRole#UNMASK_GRANT} for a designer token that holds it.
 */
@ApplicationScoped
public class ApiTokenIdentityProvider implements IdentityProvider<ApiTokenRequest> {

    /** Identity attribute: the id of the token used. */
    public static final String TOKEN_ID = "kilnmark.token-id";
    /** Identity attribute: the token's {@link AccessRole}. */
    public static final String ROLE = "kilnmark.role";
    /** Identity attribute: when the token expires, or absent. */
    public static final String EXPIRES_AT = "kilnmark.expires-at";

    private final ApiTokens tokens;

    public ApiTokenIdentityProvider(ApiTokens tokens) {
        this.tokens = tokens;
    }

    @Override
    public Class<ApiTokenRequest> getRequestType() {
        return ApiTokenRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(ApiTokenRequest request, AuthenticationRequestContext context) {
        return context.runBlocking(() -> tokens.authenticate(request.token())
            .map(ApiTokenIdentityProvider::identity)
            .orElseThrow(AuthenticationFailedException::new));
    }

    static SecurityIdentity identity(ApiTokens.Caller caller) {
        QuarkusSecurityIdentity.Builder b = QuarkusSecurityIdentity.builder()
            .setPrincipal(new QuarkusPrincipal(caller.name()))
            .addRoles(caller.role().grants())
            .addAttribute(TOKEN_ID, caller.tokenId())
            .addAttribute(ROLE, caller.role());
        if (caller.expiresAt() != null) {
            b.addAttribute(EXPIRES_AT, caller.expiresAt());
        }
        // Checked again here, whatever was stored: only a designer token ever sees unmasked values.
        if (caller.unmask() && caller.role().mayHoldUnmask()) {
            b.addRole(AccessRole.UNMASK_GRANT);
        }
        return b.build();
    }
}

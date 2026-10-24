package io.kilnmark.server.auth;

import java.util.Set;

import io.kilnmark.server.KilnmarkConfig;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.HttpCredentialTransport;
import io.quarkus.vertx.http.runtime.security.HttpSecurityUtils;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Reads {@code Authorization: Bearer km_...} and hands the token to {@link ApiTokenIdentityProvider}.
 * With {@code kilnmark.auth.mode=off} every caller is an admin (a lab on one machine; see {@link AuthMode}).
 */
@ApplicationScoped
public class ApiTokenMechanism implements HttpAuthenticationMechanism {

    /** The challenge sent with a 401. */
    public static final String CHALLENGE = "Bearer realm=\"kilnmark\"";

    private static final String BEARER = "bearer ";

    private final boolean enabled;

    public ApiTokenMechanism(KilnmarkConfig config) {
        this.enabled = config.auth().mode() != AuthMode.OFF;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager identityProviderManager) {
        if (!enabled) {
            return Uni.createFrom().item(ApiTokenIdentityProvider.identity(
                new ApiTokens.Caller(0, "anyone (sign-in is off)", AccessRole.ADMIN, null)));
        }
        String header = context.request().getHeader("Authorization");
        if (header == null || header.length() <= BEARER.length() || !header.regionMatches(true, 0, BEARER, 0,
            BEARER.length())) {
            return Uni.createFrom().optional(java.util.Optional.empty());
        }
        String token = header.substring(BEARER.length()).strip();
        return identityProviderManager.authenticate(
            HttpSecurityUtils.setRoutingContextAttribute(new ApiTokenRequest(token), context));
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        return Uni.createFrom().item(new ChallengeData(401, "WWW-Authenticate", CHALLENGE));
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        return Set.of(ApiTokenRequest.class);
    }

    @Override
    public Uni<HttpCredentialTransport> getCredentialTransport(RoutingContext context) {
        return Uni.createFrom().item(new HttpCredentialTransport(HttpCredentialTransport.Type.AUTHORIZATION, "bearer"));
    }
}

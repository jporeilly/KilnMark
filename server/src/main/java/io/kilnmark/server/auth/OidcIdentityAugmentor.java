package io.kilnmark.server.auth;

import java.time.Instant;

import org.eclipse.microprofile.jwt.JsonWebToken;

import io.kilnmark.server.KilnmarkConfig;
import io.quarkus.oidc.IdTokenCredential;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Gives a person signed in with the identity provider their Kilnmark role (ADR 0036), from
 * {@link OidcAccess}. The identity is built afresh, so no role the OIDC client read from the token
 * (it maps a {@code groups} claim to roles by itself) survives: a provider group called "admin"
 * grants nothing unless the settings say so. A sign-in older than the longest session fails, so the
 * person signs in at the provider again; one with no second factor or no group gets no role, and
 * {@link #REFUSAL} says why.
 */
@ApplicationScoped
public class OidcIdentityAugmentor implements SecurityIdentityAugmentor {

    /** Identity attribute: how the caller signed in, {@link #VIA_PROVIDER} for the identity provider. */
    public static final String VIA = "kilnmark.via";
    /** The value of {@link #VIA} for a sign-in with the identity provider. */
    public static final String VIA_PROVIDER = "provider";
    /** Identity attribute: why a provider sign-in has no role, for the sign-in page. */
    public static final String REFUSAL = "kilnmark.refusal";

    private final KilnmarkConfig config;

    public OidcIdentityAugmentor(KilnmarkConfig config) {
        this.config = config;
    }

    /** Whether this identity came from the identity provider: its principal is the ID token. */
    public static boolean fromProvider(SecurityIdentity identity) {
        return identity != null && !identity.isAnonymous() && identity.getPrincipal() instanceof JsonWebToken
            && identity.getCredential(IdTokenCredential.class) != null;
    }

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
        if (!fromProvider(identity)) {
            return Uni.createFrom().item(identity);
        }
        JsonWebToken token = (JsonWebToken) identity.getPrincipal();
        OidcAccess.Decision d = OidcAccess.decide(config.auth().oidc(), token::getClaim, Instant.now());
        if (OidcAccess.TOO_OLD.equals(d.refusal())) {
            return Uni.createFrom().failure(new AuthenticationFailedException(OidcAccess.TOO_OLD));
        }
        QuarkusSecurityIdentity.Builder b = QuarkusSecurityIdentity.builder()
            .setPrincipal(identity.getPrincipal())
            .addCredentials(identity.getCredentials())
            // the OIDC client's own attributes (its tenant, the session's tokens) are kept as they were
            .addAttributes(identity.getAttributes())
            .addAttribute(VIA, VIA_PROVIDER);
        if (d.allowed()) {
            b.addRoles(d.role().grants()).addAttribute(ApiTokenIdentityProvider.ROLE, d.role());
            if (d.unmask() && d.role().mayHoldUnmask()) {
                b.addRole(AccessRole.UNMASK_GRANT);
            }
        } else {
            b.addAttribute(REFUSAL, d.refusal());
        }
        return Uni.createFrom().item(b.build());
    }
}

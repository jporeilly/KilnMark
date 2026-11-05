package io.kilnmark.server.auth;

import java.time.Instant;
import java.util.Set;

import org.eclipse.microprofile.jwt.JsonWebToken;

import io.kilnmark.server.KilnmarkConfig;
import io.quarkus.oidc.IdTokenCredential;
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
 * grants nothing unless the settings say so. Its name is {@link #actorName}, which no token, the
 * server or an anonymous caller can have. A sign-in with no second factor, no group, or older than
 * the longest session gets no role, and {@link #REFUSAL} says why; signing in again replaces it
 * ({@code /api/v1/auth/login?again=true}).
 */
@ApplicationScoped
public class OidcIdentityAugmentor implements SecurityIdentityAugmentor {

    /** Identity attribute: how the caller signed in, {@link #VIA_PROVIDER} for the identity provider. */
    public static final String VIA = "kilnmark.via";
    /** The value of {@link #VIA} for a sign-in with the identity provider. */
    public static final String VIA_PROVIDER = "provider";
    /** Identity attribute: why a provider sign-in has no role, for the sign-in page. */
    public static final String REFUSAL = "kilnmark.refusal";

    /** How a provider sign-in's name starts, everywhere Kilnmark names a caller. */
    public static final String PREFIX = "sso:";

    private final KilnmarkConfig config;

    public OidcIdentityAugmentor(KilnmarkConfig config) {
        this.config = config;
    }

    /** Whether this identity came from the identity provider: its principal is the ID token. */
    public static boolean fromProvider(SecurityIdentity identity) {
        return identity != null && !identity.isAnonymous() && identity.getPrincipal() instanceof JsonWebToken
            && identity.getCredential(IdTokenCredential.class) != null;
    }

    /**
     * The person behind a provider sign-in, as the provider fixes them: its issuer and their subject,
     * which no name change alters; null for anything else (a token names no person). What the unmask
     * grant's two people are compared by (ADR 0038).
     */
    public static String personKey(SecurityIdentity identity) {
        if (!fromProvider(identity)) {
            return null;
        }
        JsonWebToken t = (JsonWebToken) identity.getPrincipal();
        String iss = t.getIssuer();
        String sub = t.getSubject();
        return iss == null || sub == null || sub.isBlank() ? null : "oidc:" + iss + "|" + sub;
    }

    /**
     * The name a provider sign-in goes by in the audit log, usage records and "created by" columns:
     * {@code sso:} with the person's name and, in brackets, the provider's fixed ID for them
     * ({@code sub}). The name is whatever the provider sends, which some let people choose, so the
     * prefix and the ID keep it from passing for anyone else.
     */
    public static String actorName(JsonWebToken token) {
        if (token instanceof Named named) {
            return named.name(); // named already: the sign-in event sees the identity after this augmentor
        }
        String name = token.getName();
        String sub = token.getSubject();
        String shown = clean(name == null || name.isBlank() ? sub : name);
        return PREFIX + shown + (sub != null && !sub.equals(name) ? " (" + clean(sub) + ")" : "");
    }

    private static String clean(String s) {
        // control, format (bidi, zero-width) and unassigned characters: none may make one name look like another
        String c = s == null ? "" : s.replaceAll("[\\p{C}\\p{Zl}\\p{Zp}]", "?").strip();
        return c.length() > 120 ? c.substring(0, 120) : c;
    }

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
        if (!fromProvider(identity)) {
            return Uni.createFrom().item(identity);
        }
        JsonWebToken token = (JsonWebToken) identity.getPrincipal();
        OidcAccess.Decision d = OidcAccess.decide(config.auth().oidc(), token::getClaim, Instant.now());
        String name = actorName(token);
        QuarkusSecurityIdentity.Builder b = QuarkusSecurityIdentity.builder()
            .setPrincipal(new Named(token, name))
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

    /** The ID token, under the name {@link #actorName} gives it; its claims as they are. */
    private record Named(JsonWebToken token, String name) implements JsonWebToken {
        @Override
        public String getName() {
            return name;
        }

        @Override
        public Set<String> getClaimNames() {
            return token.getClaimNames();
        }

        @Override
        @SuppressWarnings("TypeParameterUnusedInFormals") // JsonWebToken's signature, implemented as it is
        public <T> T getClaim(String claimName) {
            return token.getClaim(claimName);
        }
    }
}

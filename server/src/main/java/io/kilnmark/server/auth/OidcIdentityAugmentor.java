package io.kilnmark.server.auth;

import java.time.Instant;
import java.util.Set;

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

    /**
     * The mark the OIDC client puts on a request while it renews an expired session with the refresh
     * token ({@code OidcIdentityProvider.REFRESH_TOKEN_GRANT_RESPONSE}, Quarkus 3.40; not public API,
     * so {@code OidcRenewalTest} fails if it changes). Read through {@link #renewal}.
     */
    static final String RENEWAL_MARK = "refresh_token_grant_response";

    private final KilnmarkConfig config;

    /** Told of each renewal, which is no new sign-in: it pauses what a lowered role no longer covers. */
    private final jakarta.enterprise.inject.Instance<SignInEvents> signIns;

    public OidcIdentityAugmentor(KilnmarkConfig config, jakarta.enterprise.inject.Instance<SignInEvents> signIns) {
        this.config = config;
        this.signIns = signIns;
    }

    /**
     * Whether this provider identity comes from renewing a session (the ID token had expired, and the
     * OIDC client fetched a fresh one with the refresh token), not from the person signing in. A
     * renewal is not recorded as a sign-in. Unknown counts as a sign-in, which only records one more.
     */
    public static boolean renewal(SecurityIdentity identity) {
        Object rc = identity == null ? null : identity.getAttribute(io.vertx.ext.web.RoutingContext.class.getName());
        return rc instanceof io.vertx.ext.web.RoutingContext r && Boolean.TRUE.equals(r.get(RENEWAL_MARK));
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
     * An opaque key for the person behind a provider sign-in: a hash of {@link #personKey}, so it
     * names no provider address. Schedules keep it to check the person's role at each run, and
     * sign-in events record it (ADR 0046); null for anything but a provider sign-in.
     */
    public static String personRef(SecurityIdentity identity) {
        String key = personKey(identity);
        if (key == null) {
            return null;
        }
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "p:" + java.util.HexFormat.of().formatHex(h, 0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("This Java runtime has no SHA-256", e);
        }
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

    /**
     * The name the screen greets a provider sign-in by: the person's name as the provider gives it
     * ({@code name}, or {@code given_name} and {@code family_name}), else their user name or email.
     * Only for showing to the person themself: the audit log, usage records and "created by" columns
     * keep {@link #actorName}, which no chosen name can imitate.
     */
    public static String displayName(JsonWebToken token) {
        String shown = text(token, "name");
        if (shown.isEmpty()) {
            shown = (text(token, "given_name") + " " + text(token, "family_name")).strip();
        }
        if (shown.isEmpty()) {
            shown = text(token, "preferred_username");
        }
        if (shown.isEmpty()) {
            shown = text(token, "email");
        }
        return shown.isEmpty() ? clean(token.getSubject()) : shown;
    }

    /** A claim as text, cleaned; empty when it is missing or not text. */
    private static String text(JsonWebToken token, String claim) {
        Object v = token.getClaim(claim);
        String s = v instanceof String str ? str : v instanceof jakarta.json.JsonString js ? js.getString() : null;
        return clean(s);
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
        if (renewal(identity)) {
            // The same checks as a sign-in, on the provider's fresh ID token: auth_time is still when
            // the person signed in (OpenID Connect Core 12.2), so the longest session still ends it.
            if (signIns.isResolvable()) {
                signIns.get().renewed(identity, token, d);
            }
            if (!d.allowed()) {
                // A renewal given no role ends the session (AuthErrors answers 401 and clears the cookie):
                // the page says the sign-in has ended, and signing in again shows why and records it.
                return Uni.createFrom().failure(new AuthenticationFailedException("The renewed sign-in was refused"));
            }
        }
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

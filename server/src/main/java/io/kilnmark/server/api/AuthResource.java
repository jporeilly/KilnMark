package io.kilnmark.server.api;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;

import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.audit.AuditLog;
import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.auth.MaxAgeRedirect;
import io.kilnmark.server.auth.OidcIdentityAugmentor;
import io.quarkus.oidc.OidcProviderClient;
import io.quarkus.oidc.OidcSession;
import io.quarkus.oidc.RefreshToken;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
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

    /** The provider's metadata: its end-session endpoint, where signing out sends the browser. */
    @Inject
    jakarta.enterprise.inject.Instance<io.quarkus.oidc.OidcConfigurationMetadata> providerMetadata;

    @jakarta.ws.rs.core.Context
    jakarta.ws.rs.core.UriInfo uriInfo;

    /** The session's refresh token, which signing out asks the provider to revoke. */
    @Inject
    jakarta.enterprise.inject.Instance<RefreshToken> refreshToken;

    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(AuthResource.class);

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
        if (identity.isAnonymous() || !OidcIdentityAugmentor.fromProvider(identity)) {
            return new Views.AuthStatus(provider, name, false, null);
        }
        String refusal = identity.getAttribute(OidcIdentityAugmentor.REFUSAL);
        return new Views.AuthStatus(provider, name, true, refusal);
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
    public Response login(@QueryParam("again") boolean again) {
        // Asked to sign in again (the page's button, after a sign-in given no role): end that session
        // and go back to the provider, asking it to sign the person in afresh (prompt=login,
        // MaxAgeRedirect). Never otherwise: the provider's redirect back lands here too, and a refused
        // session sent round again would loop through the provider for ever.
        if (again && OidcIdentityAugmentor.fromProvider(identity)
            && identity.getAttribute(OidcIdentityAugmentor.REFUSAL) != null && session.isResolvable()) {
            session.get().logout().await().indefinitely();
            return Response.seeOther(URI.create("/api/v1/auth/login?" + MaxAgeRedirect.AFRESH + "=true")).build();
        }
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * What signing out returns: where the browser goes next to end the identity provider's own
     * session (its end-session endpoint, OpenID Connect RP-initiated logout), or null when there is
     * none to end (an API token) or the provider names no such endpoint.
     */
    public record SignedOut(String endSession) {
    }

    @POST
    @Path("sign-out")
    @Authenticated
    @Operation(summary = "Sign out", description = "Ends this browser's session with Kilnmark. For a sign-in "
        + "with the identity provider, endSession is the provider's own sign-out page, with this session's ID "
        + "token as its hint: the browser goes there so the provider's session ends too, and the provider "
        + "sends it back to Kilnmark. Without it, a new sign-in would be let straight in as the same person.")
    @APIResponses({@APIResponse(responseCode = "200", description = "Signed out")})
    public Response signOut() {
        String endSession = null;
        if (OidcIdentityAugmentor.fromProvider(identity) && session.isResolvable()) {
            audit.record(AuditLog.actor(identity), AuditLog.SIGN_OUT, "session", Map.of());
            // read before the session ends: its ID token is the provider's hint
            endSession = endSessionUrl();
            revokeRefreshToken();
            session.get().logout().await().indefinitely();
        }
        return Response.ok(new SignedOut(endSession)).build();
    }

    /**
     * The provider's end-session URL for this session: its end_session_endpoint with the ID token
     * as id_token_hint, Kilnmark's client id, and Kilnmark's own address (as this request reached
     * it, https when Kilnmark sits behind a proxy that ends TLS, as for the sign-in's redirect) to
     * come back to, which the provider must accept (keycloak-setup registers it; for your own
     * provider, register it: docs/guide/people-and-access.md). Null when the provider names no
     * end-session endpoint, or it cannot be read: signing out of Kilnmark never waits on it.
     */
    private String endSessionUrl() {
        try {
            String end = providerMetadata.isResolvable() ? providerMetadata.get().getEndSessionUri() : null;
            if (end == null || end.isBlank()) {
                return null;
            }
            var settings = org.eclipse.microprofile.config.ConfigProvider.getConfig();
            URI base = uriInfo.getBaseUri();
            boolean https = settings.getOptionalValue("quarkus.oidc.authentication.force-redirect-https-scheme",
                Boolean.class).orElse(false);
            String back = (https ? "https" : base.getScheme()) + ":/" + "/" + base.getRawAuthority() + "/";
            StringBuilder url = new StringBuilder(end).append(end.contains("?") ? '&' : '?')
                .append("post_logout_redirect_uri=").append(encode(back));
            var idToken = session.get().getIdToken();
            if (idToken != null && idToken.getRawToken() != null) {
                url.append("&id_token_hint=").append(encode(idToken.getRawToken()));
            }
            settings.getOptionalValue("quarkus.oidc.client-id", String.class)
                .ifPresent(id -> url.append("&client_id=").append(encode(id)));
            return url.toString();
        } catch (RuntimeException e) {
            LOG.warnf("Could not make the identity provider's sign-out address (%s): its own session stays until "
                + "it ends", e.getClass().getSimpleName());
            return null;
        }
    }

    private static String encode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** One person keycloak-setup made: their email, and their groups, ;-separated. */
    public record PersonMade(String person, String groups) {
    }

    /**
     * Body of {@code POST /api/v1/auth/people}: how (add, import, activate, or expire for what an
     * ordinary start's keycloak-setup did), who was made, who was there already, for an import how
     * many lines were refused, who was activated, and who was disabled for an unused password.
     */
    public record PeopleDone(String via, List<PersonMade> made, List<String> kept, Integer refused,
                             List<String> activated, List<String> disabled) {
    }

    /** An email address, in lower case, as the control script and keycloak-setup take it. */
    static final java.util.regex.Pattern EMAIL = java.util.regex.Pattern.compile("[a-z0-9._%+-]+@[a-z0-9-]+(\\.[a-z0-9-]+)+");

    /** Kilnmark's groups in the bundled Keycloak, as keycloak-setup makes them. */
    static final java.util.Set<String> GROUPS = java.util.Set.of("kilnmark-viewers", "kilnmark-stewards",
        "kilnmark-designers", "kilnmark-admins", "kilnmark-sensitive-data");

    /** The most people one call records: keycloak-setup takes at most 1000 from a file. */
    static final int MAX_PEOPLE = 1000;

    private static String own(String person) {
        if (person == null || !EMAIL.matcher(person).matches() || person.endsWith("@kilnmark.com")
            || person.endsWith(".kilnmark.com")) {
            throw new IllegalArgumentException("people: email addresses of your own organisation, in lower case");
        }
        return person;
    }

    @POST
    @Path("people")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(AccessRole.ADMIN_ROLE)
    @Operation(summary = "Record what the people command did", description = "The control script's people add "
        + "(and install's first admin), people import and people activate, and its up, tell Kilnmark what "
        + "keycloak-setup did in the bundled Keycloak, so its audit log holds it: auth.person_added for each person "
        + "made, with their groups, auth.people_imported for each import, with how many were made, already there "
        + "and refused, auth.person_activated, and auth.person_disabled for each person whose temporary password "
        + "went unused. Never a password. An import never makes an admin. Kilnmark holds no Keycloak credential and "
        + "changes nothing here. Role: admin.")
    @APIResponses({
        @APIResponse(responseCode = "204", description = "Recorded"),
        @APIResponse(responseCode = "400", description = "Not add or import, not addresses of the organisation's "
            + "own, not Kilnmark's groups, or an admin from a file"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "403", description = "Not an admin")})
    // What is recorded here is attributed to the admin calling (the control script's admin token):
    // an admin could record a person added who was not, but nothing here changes who can sign in,
    // and Keycloak's own admin events hold what was really done (ADR 0051).
    public Response people(PeopleDone body) {
        String via = body == null ? null : body.via();
        if (!java.util.Set.of("add", "import", "activate", "expire", "stamp").contains(via)) {
            throw new IllegalArgumentException("via: add, import, activate, expire or stamp");
        }
        List<PersonMade> made = body.made() == null ? List.of() : body.made();
        List<String> kept = body.kept() == null ? List.of() : body.kept();
        List<String> activated = body.activated() == null ? List.of() : body.activated();
        List<String> disabled = body.disabled() == null ? List.of() : body.disabled();
        int refused = body.refused() == null ? 0 : body.refused();
        if (made.size() + kept.size() > MAX_PEOPLE || disabled.size() > MAX_PEOPLE || refused < 0 || refused > MAX_PEOPLE
            || ("add".equals(via) && (made.size() + kept.size() != 1 || refused != 0))
            || ("activate".equals(via) && (activated.size() != 1 || !made.isEmpty() || !kept.isEmpty()))
            || (!"activate".equals(via) && !activated.isEmpty())
            || ("expire".equals(via) && (!made.isEmpty() || !kept.isEmpty() || refused != 0))
            || ("stamp".equals(via) && (!made.isEmpty() || !kept.isEmpty()
                || !disabled.isEmpty() || refused != 0))) {
            throw new IllegalArgumentException("people: one person for add or activate, at most " + MAX_PEOPLE
                + " for import");
        }
        List<Map<String, Object>> events = new java.util.ArrayList<>();
        for (PersonMade m : made) {
            List<String> groups = m == null || m.groups() == null ? List.of()
                : java.util.Arrays.stream(m.groups().split(";", -1)).toList();
            if (groups.isEmpty() || !GROUPS.containsAll(groups) || groups.size() != java.util.Set.copyOf(groups).size()) {
                throw new IllegalArgumentException("groups: Kilnmark's groups, each once");
            }
            if ("import".equals(via) && groups.contains("kilnmark-admins")) {
                throw new IllegalArgumentException("groups: admins are added one at a time, never from a file");
            }
            if ("import".equals(via) && groups.contains("kilnmark-sensitive-data")
                && !io.kilnmark.server.starter.ModeCheck.samplesAllowed(config)) {
                throw new IllegalArgumentException("groups: on a production install, people who see sensitive values are "
                    + "added one at a time, never from a file");
            }
            events.add(Map.of("person", own(m.person()), "groups", groups, "via", via));
        }
        kept.forEach(AuthResource::own);
        activated.forEach(AuthResource::own);
        disabled.forEach(AuthResource::own);
        AuditLog.Actor actor = AuditLog.actor(identity);
        for (Map<String, Object> e : events) {
            audit.record(actor, AuditLog.PERSON_ADDED, "person", e);
        }
        for (String person : activated) {
            audit.record(actor, AuditLog.PERSON_ACTIVATED, "person", Map.of("person", person));
        }
        for (String person : disabled) {
            audit.record(actor, AuditLog.PERSON_DISABLED, "person", Map.of("person", person, "reason",
                "temporary password unused"));
        }
        if ("import".equals(via)) {
            audit.record(actor, AuditLog.PEOPLE_IMPORTED, "people", Map.of("made", made.size(), "alreadyThere",
                kept.size(), "refused", refused));
        }
        if ("stamp".equals(via)) {
            audit.record(actor, AuditLog.REALM_STAMPED, "realm kilnmark", Map.of());
        }
        return Response.noContent().build();
    }

    /**
     * Asks the provider to revoke the session's refresh token (RFC 7009), so a copy of the session
     * cookie taken before signing out cannot renew itself afterwards: it ends with the ID token in it.
     * Best effort: a provider without a revocation endpoint, or one that does not answer, never stops
     * the person signing out.
     */
    private void revokeRefreshToken() {
        try {
            String token = refreshToken.isResolvable() ? refreshToken.get().getToken() : null;
            if (token != null && identity.getAttribute(OidcProviderClient.class.getName()) instanceof OidcProviderClient c
                && !Boolean.TRUE.equals(c.revokeRefreshToken(token).await().atMost(java.time.Duration.ofSeconds(5)))) {
                LOG.info("The identity provider did not revoke the refresh token of a session signed out "
                    + "(it may not offer revocation); a copy of the session can renew until the provider's session ends");
            }
        } catch (RuntimeException e) {
            LOG.warnf("Could not ask the identity provider to revoke a signed-out session's refresh token (%s)",
                e.getClass().getSimpleName());
        }
    }
}

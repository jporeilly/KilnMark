package io.kilnmark.server.api;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;

import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.ai.EgressPolicy;
import io.kilnmark.server.audit.AuditLog;
import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.auth.ApiTokens;
import io.kilnmark.server.auth.AuthMode;
import io.kilnmark.server.auth.OidcAccess;
import io.kilnmark.server.auth.RoleTable;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The access review (ADR 0037): who can do what in Kilnmark, for the periodic review a bank's
 * auditors ask for. What each role may do ({@link RoleTable}), the settings that limit people, every
 * API token with its role, unmask grant, status, expiry and last use, and everyone who signs in with
 * the identity provider: those who have signed in, with the role, grant and groups of their last
 * sign-in, and those the control script made in the bundled Keycloak who have not signed in since,
 * with their groups and whether they wait for their first sign-in, wait to be activated, or were
 * disabled. All from Kilnmark's own records and settings: it never asks the identity provider.
 * Admins only; each view and download is in the audit log. Never a token's secret or hash.
 */
@Path("/api/v1/access-review")
@RolesAllowed(AccessRole.ADMIN_ROLE)
public class AccessReviewResource {

    /** A person who has signed in (their latest sign-in gave a role, or none any more). */
    public static final String SIGNED_IN = "signed-in";
    /** A person who has signed in, but no sign-in of theirs was ever given a role. */
    public static final String NO_ROLE = "no-role";
    /** A person the control script made, who has a password to sign in with and has not yet. */
    public static final String WAITING = "waiting";
    /** A person imported on a production install, made disabled with no password until activated. */
    public static final String NOT_ACTIVATED = "not-activated";
    /** A person disabled because their temporary password went unused. */
    public static final String DISABLED = "disabled";

    /**
     * One line of the review: a token, or a person who signs in with the identity provider.
     * {@code example}: a kilnmark.com example account, such as the Starter Pack's people (ADR 0051),
     * which should not be on an install holding real data. For people, {@code groups} are their
     * groups as Kilnmark recorded them (only those the settings name; null when not recorded),
     * {@code sensitiveGroups} those of them that the unmask settings name, and {@code state} one of
     * {@link #SIGNED_IN}, {@link #NO_ROLE}, {@link #WAITING}, {@link #NOT_ACTIVATED} and
     * {@link #DISABLED}; for someone who has not signed in, {@code role} and {@code unmask} are what
     * their groups give under today's settings once they do. All three are null for a token.
     */
    public record Entry(String kind, String name, String role, boolean unmask, String status, String createdBy,
                        Instant createdAt, Instant expiresAt, Instant lastUsedAt, Long signIns, String unmaskApprovedBy,
                        boolean example, List<String> groups, List<String> sensitiveGroups, String state) {
    }

    /**
     * What a role may do: one line of {@link RoleTable}, with {@code byRole} saying for viewer,
     * steward, designer and admin {@code yes}, {@code grant} (only with the sensitive-data grant) or
     * {@code no}.
     */
    public record Line(String id, String what, Map<String, String> byRole) {
    }

    /** A setting that limits people, read-only: what it is, its value in words, and its name. */
    public record Rule(String id, String label, String value, String setting) {
    }

    /**
     * The review: when it was made, the longest token lifetime the server allows (days, or null),
     * every entry, what each role may do, the groups the settings give each role and the
     * sensitive-data grant ({@code unmask}), and the settings that limit people.
     */
    public record Review(Instant generatedAt, Long tokenMaxLifetimeDays, List<Entry> entries, List<Line> roles,
                         Map<String, List<String>> groups, List<Rule> rules) {
    }

    @Inject
    ApiTokens tokens;

    @Inject
    AuditLog audit;

    @Inject
    SecurityIdentity identity;

    @Inject
    KilnmarkConfig config;

    @Inject
    EgressPolicy egress;

    @GET
    @Produces({MediaType.APPLICATION_JSON, "text/csv"})
    @Operation(summary = "The access review", description = "What each role may do, the settings that limit people, "
        + "every API token (role, unmask grant, status, who created it, expiry, last use) and everyone who signs in "
        + "with the identity provider: those who have signed in (the role, grant and groups of their last sign-in, "
        + "when, and how many times) and those the control script made in the bundled Keycloak who have not signed in "
        + "since (their groups, and whether they wait for their first sign-in, wait to be activated, or were disabled "
        + "for an unused temporary password), each kilnmark.com example account marked example (the Starter Pack's "
        + "people). From Kilnmark's own records: it never asks the identity provider. format=csv for a spreadsheet. "
        + "Role: admin; each view and download is recorded in the audit log.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The review"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "403", description = "Not an admin")})
    public Response review(@QueryParam("format") String format) {
        boolean csv = "csv".equalsIgnoreCase(format);
        Review r = build(Instant.now());
        audit.record(AuditLog.actor(identity), AuditLog.ACCESS_REVIEW, "access review",
            Map.of("format", csv ? "csv" : "json", "entries", r.entries().size()));
        CacheControl noStore = new CacheControl();
        noStore.setNoStore(true);
        if (!csv) {
            return Response.ok(r, MediaType.APPLICATION_JSON).cacheControl(noStore).build();
        }
        return Response.ok(csv(r), "text/csv; charset=utf-8").cacheControl(noStore)
            .header("Content-Disposition", "attachment; filename=\"kilnmark-access-review.csv\"").build();
    }

    Review build(Instant now) {
        KilnmarkConfig.Oidc oidc = config.auth().oidc();
        List<Entry> out = new ArrayList<>();
        for (ApiTokens.Token t : tokens.list()) {
            out.add(new Entry("token", t.name(), t.role().id(), t.unmask(), t.status(now), t.createdBy(),
                t.createdAt(), t.expiresAt(), t.lastUsedAt(), null, t.unmaskApprovedBy(), false, null, null, null));
        }
        List<AuditLog.SignedIn> signedIn = audit.signIns();
        // each sign-in by its user name, read once: the bundled Keycloak's is the person's email, which
        // keycloak-setup makes and people cannot change
        Map<String, List<AuditLog.SignedIn>> byUser = new java.util.HashMap<>();
        for (AuditLog.SignedIn s : signedIn) {
            String user = userOf(s.name());
            if (user != null) {
                byUser.computeIfAbsent(user, u -> new ArrayList<>()).add(s);
            }
        }
        Set<String> shown = new HashSet<>();
        List<Entry> waiting = new ArrayList<>();
        for (AuditLog.PersonRecord p : audit.peopleRecorded()) {
            // their sign-ins, since the latest people event for them
            List<AuditLog.SignedIn> theirs = byUser.getOrDefault(p.person(), List.of());
            theirs.forEach(s -> shown.add(s.name()));
            if (theirs.stream().noneMatch(s -> s.lastId() > p.latestId())) {
                waiting.add(madeEntry(oidc, p));
            }
        }
        for (AuditLog.SignedIn s : signedIn) {
            if (s.everGiven() || shown.contains(s.name())) {
                out.add(signedInEntry(oidc, s));
            }
        }
        out.addAll(waiting);
        Long days = tokens.maxLifetime().map(d -> d.toDays()).orElse(null);
        List<Line> roles = RoleTable.LINES.stream().map(l -> new Line(l.id(), l.what(), l.byRole())).toList();
        return new Review(now, days, out, roles, groups(oidc), rules(oidc, days));
    }

    /**
     * Whether a sign-in's name ({@code sso:} with the user name and, in brackets, the provider's ID)
     * is the user {@code person}: exactly that shape, the ID one word, so no other user name (one
     * that itself ends in a bracketed word, say) passes for theirs.
     */
    static boolean sameUser(String signInName, String person) {
        return person != null && person.equals(userOf(signInName));
    }

    /** A sign-in's name: {@code sso:}, the user name, and the provider's ID, one word in brackets. */
    private static final java.util.regex.Pattern SIGN_IN_NAME = java.util.regex.Pattern.compile(
        java.util.regex.Pattern.quote(io.kilnmark.server.auth.OidcIdentityAugmentor.PREFIX) + "(.+) \\([^()\\s]+\\)");

    /** The user name in a sign-in's name, or null when it is not of that shape. */
    static String userOf(String signInName) {
        if (signInName == null) {
            return null;
        }
        java.util.regex.Matcher m = SIGN_IN_NAME.matcher(signInName);
        return m.matches() ? m.group(1) : null;
    }

    private static Entry signedInEntry(KilnmarkConfig.Oidc oidc, AuditLog.SignedIn s) {
        String status;
        String state = SIGNED_IN;
        if (!s.everGiven()) {
            state = NO_ROLE;
            status = "signed in, but given no role: " + s.refused();
        } else if (s.refused() == null) {
            status = "signed in";
        } else if (s.role() == null) {
            status = "no group gives a role any more";
        } else {
            status = "last sign-in refused: " + s.refused();
        }
        List<String> groups = s.groups();
        return new Entry("provider", s.name(), s.role(), s.unmask(), status, null, null, null, s.lastSignIn(),
            s.signIns(), null, io.kilnmark.server.starter.ExampleAccounts.isExample(s.name()), groups,
            groups == null ? null : OidcAccess.unmaskGroupsOf(oidc, groups), state);
    }

    private Entry madeEntry(KilnmarkConfig.Oidc oidc, AuditLog.PersonRecord p) {
        List<String> groups = p.groups();
        AccessRole role = groups == null ? null : OidcAccess.roleOf(oidc, groups);
        boolean unmask = groups != null && OidcAccess.unmasks(oidc, role, groups);
        String state;
        String status;
        if ("disabled".equals(p.latest())) {
            state = DISABLED;
            status = p.hours() == null ? "disabled: temporary password unused"
                : "disabled: temporary password unused for " + p.hours() + " hours";
        } else if ("added".equals(p.latest()) && "import".equals(p.via())
            && !io.kilnmark.server.starter.ModeCheck.starterPack(config)) {
            // a production import gives nobody a password: each waits, disabled, for people activate
            state = NOT_ACTIVATED;
            status = "waiting to be activated";
        } else {
            state = WAITING;
            status = "waiting for first sign-in";
        }
        return new Entry("provider", p.person(), role == null ? null : role.id(), unmask, status, p.addedBy(),
            p.addedAt(), null, null, 0L, null, false, groups,
            groups == null ? null : OidcAccess.unmaskGroupsOf(oidc, groups), state);
    }

    /** The groups the settings give each role, and the sensitive-data grant ({@code unmask}), as written. */
    private static Map<String, List<String>> groups(KilnmarkConfig.Oidc oidc) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        out.put(AccessRole.VIEWER.id(), oidc.viewerGroups().orElse(List.of()));
        out.put(AccessRole.STEWARD.id(), oidc.stewardGroups().orElse(List.of()));
        out.put(AccessRole.DESIGNER.id(), oidc.designerGroups().orElse(List.of()));
        out.put(AccessRole.ADMIN.id(), oidc.adminGroups().orElse(List.of()));
        out.put(AccessRole.UNMASK_GRANT, oidc.unmaskGroups().orElse(List.of()));
        out.replaceAll((k, v) -> v.stream().filter(g -> g != null && !g.isBlank()).map(String::strip).toList());
        return out;
    }

    /** The settings that limit people, as they are on this server; those of sign-in with a provider only when it is on. */
    private List<Rule> rules(KilnmarkConfig.Oidc oidc, Long days) {
        List<Rule> out = new ArrayList<>();
        if (config.auth().mode() == AuthMode.OFF) {
            out.add(new Rule("sign-in", "Sign-in", "Off: every caller is an admin. Allowed only while the server "
                + "listens on this computer alone", "kilnmark.auth.mode"));
        }
        out.add(new Rule("token-lifetime", "Longest token lifetime", days == null
            ? "None: tokens may never expire" : days + (days == 1 ? " day" : " days"),
            "kilnmark.auth.token-max-lifetime"));
        out.add(new Rule("unmask-approval", "A second person approves the sensitive-data grant", tokens.unmaskApproval()
            ? "On: a designer token's grant works only once another person, signed in with your identity provider, "
                + "approves it"
            : "Off: an admin alone can give a designer token the grant", "kilnmark.auth.unmask-approval"));
        boolean provider = org.eclipse.microprofile.config.ConfigProvider.getConfig()
            .getOptionalValue("quarkus.oidc.tenant-enabled", Boolean.class).orElse(false);
        out.add(new Rule("provider", "Sign-in with your identity provider", provider ? "On" : "Off: only API tokens",
            "quarkus.oidc.tenant-enabled"));
        if (provider) {
            out.add(new Rule("max-session", "Longest sign-in", words(oidc.maxSession())
                + " from signing in at the provider, however often it renews", "kilnmark.auth.oidc.max-session"));
            String acr = oidc.mfaAcr().filter(a -> !a.isEmpty()).map(a -> ", or the provider says one of these acr "
                + "values: " + String.join(", ", a)).orElse("");
            out.add(new Rule("mfa", "Second factor (MFA)", oidc.requireMfa()
                ? "Required: a sign-in without one gets no role" + acr : "Not required", "kilnmark.auth.oidc.require-mfa"));
        }
        out.add(new Rule("schedules", "A person's schedules pause", "when they have not signed in for "
            + words(config.schedules().signedInWithin()), "kilnmark.schedules.signed-in-within"));
        out.add(new Rule("hosted-models", "Models outside your network", egress.hostedLockedOff()
            ? "Forbidden by the server's settings: an admin cannot allow them"
            : egress.hostedAllowed() ? "Allowed: an admin can forbid them under Settings, AI"
                : "Forbidden by an admin under Settings, AI", "kilnmark.ai.hosted-models"));
        return out;
    }

    /** A duration in words: days, or hours, or minutes. */
    static String words(Duration d) {
        if (d.toHours() >= 24 && d.toHours() % 24 == 0) {
            long n = d.toDays();
            return n + (n == 1 ? " day" : " days");
        }
        if (d.toMinutes() >= 60 && d.toMinutes() % 60 == 0) {
            long n = d.toHours();
            return n + (n == 1 ? " hour" : " hours");
        }
        long n = Math.max(1, d.toMinutes());
        return n + (n == 1 ? " minute" : " minutes");
    }

    /** The review as CSV, one entry a row; cells that a spreadsheet would run as a formula are quoted safe. */
    static String csv(Review r) {
        StringBuilder b = new StringBuilder("kind,name,role,unmask,status,created_by,created_at,expires_at,"
            + "last_used_at,sign_ins,unmask_approved_by,example,state,groups,sensitive_data_groups\r\n");
        for (Entry e : r.entries()) {
            b.append(String.join(",", cell(e.kind()), cell(e.name()), cell(e.role()), cell(String.valueOf(e.unmask())),
                cell(e.status()), cell(e.createdBy()), cell(e.createdAt()), cell(e.expiresAt()), cell(e.lastUsedAt()),
                cell(e.signIns() == null ? null : e.signIns().toString()), cell(e.unmaskApprovedBy()),
                cell(String.valueOf(e.example())), cell(e.state()), cell(list(e.groups())),
                cell(list(e.sensitiveGroups()))))
                .append("\r\n");
        }
        return b.toString();
    }

    private static String list(List<String> values) {
        return values == null ? null : String.join(";", values);
    }

    /** {@link #csv} for tests in other packages. */
    public static String csvForTests(Review r) {
        return csv(r);
    }

    private static String cell(Object value) {
        if (value == null) {
            return "";
        }
        String s = value.toString();
        // A name a person chose could start a formula (=, +, -, @): a leading quote keeps it text
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}

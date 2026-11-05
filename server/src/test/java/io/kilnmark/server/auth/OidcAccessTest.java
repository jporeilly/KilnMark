package io.kilnmark.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.kilnmark.server.KilnmarkConfig;

/** What a provider sign-in may do, from its ID token's claims (ADR 0036). */
class OidcAccessTest {

    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");

    /** Settings: four groups, one per role, an unmask group, MFA required, 8 hours. */
    private static KilnmarkConfig.Oidc config(boolean requireMfa, List<String> acr) {
        return new KilnmarkConfig.Oidc() {
            @Override public String providerName() { return "Example"; }
            @Override public String groupsClaim() { return "groups"; }
            @Override public Optional<List<String>> viewerGroups() { return Optional.of(List.of("km-viewers")); }
            @Override public Optional<List<String>> stewardGroups() { return Optional.of(List.of("km-stewards")); }
            @Override public Optional<List<String>> designerGroups() { return Optional.of(List.of("km-designers")); }
            @Override public Optional<List<String>> adminGroups() { return Optional.of(List.of("km-admins")); }
            @Override public Optional<List<String>> unmaskGroups() { return Optional.of(List.of("km-unmask")); }
            @Override public boolean requireMfa() { return requireMfa; }
            @Override public Optional<List<String>> mfaAcr() { return Optional.ofNullable(acr); }
            @Override public Duration maxSession() { return Duration.ofHours(8); }
        };
    }

    private static OidcAccess.Decision decide(KilnmarkConfig.Oidc config, Object... claims) {
        Map<String, Object> m = new HashMap<>();
        m.put("auth_time", NOW.minusSeconds(60).getEpochSecond());
        for (int i = 0; i < claims.length; i += 2) {
            m.put((String) claims[i], claims[i + 1]);
        }
        return OidcAccess.decide(config, m::get, NOW);
    }

    @Test
    void theHighestRoleOfTheirGroups() {
        KilnmarkConfig.Oidc c = config(true, null);
        assertEquals(AccessRole.VIEWER, decide(c, "amr", List.of("pwd", "mfa"), "groups", List.of("km-viewers")).role());
        OidcAccess.Decision both = decide(c, "amr", List.of("mfa"), "groups", List.of("km-viewers", "km-admins", "x"));
        assertEquals(AccessRole.ADMIN, both.role());
        assertFalse(both.unmask(), "admins never hold the unmask grant");
    }

    /** Only designers in an unmask group see unmasked values; the group alone gives no role. */
    @Test
    void unmaskForDesignersInAnUnmaskGroupOnly() {
        KilnmarkConfig.Oidc c = config(true, null);
        assertTrue(decide(c, "amr", "mfa", "groups", List.of("km-designers", "km-unmask")).unmask());
        assertFalse(decide(c, "amr", "mfa", "groups", List.of("km-designers")).unmask());
        assertFalse(decide(c, "amr", "mfa", "groups", List.of("km-stewards", "km-unmask")).unmask());
        assertEquals(OidcAccess.NO_GROUP, decide(c, "amr", "mfa", "groups", List.of("km-unmask")).refusal());
    }

    /** Groups compare exactly: a provider group called "admin" grants nothing the settings do not name. */
    @Test
    void groupsTheSettingsDoNotNameGiveNothing() {
        KilnmarkConfig.Oidc c = config(true, null);
        OidcAccess.Decision d = decide(c, "amr", "mfa", "groups", List.of("admin", "KM-ADMINS", " km-admins"));
        assertNull(d.role());
        assertEquals(OidcAccess.NO_GROUP, d.refusal());
        assertEquals(OidcAccess.NO_GROUP, decide(c, "amr", "mfa").refusal(), "no groups claim at all");
    }

    @Test
    void aSecondFactorIsRequired() {
        KilnmarkConfig.Oidc c = config(true, List.of("urn:example:mfa"));
        assertEquals(OidcAccess.NO_MFA, decide(c, "groups", List.of("km-admins")).refusal(), "no amr");
        assertEquals(OidcAccess.NO_MFA, decide(c, "amr", List.of("pwd"), "groups", List.of("km-admins")).refusal());
        assertEquals(OidcAccess.NO_MFA, decide(c, "amr", "pwd", "acr", "1", "groups", List.of("km-admins")).refusal());
        assertTrue(decide(c, "amr", List.of("pwd", "OTP"), "groups", List.of("km-admins")).allowed(), "case ignored");
        assertTrue(decide(c, "amr", "mfa", "groups", List.of("km-admins")).allowed(), "the provider says mfa");
        // One method alone is one factor, however strong: a passwordless SMS, code or face sign-in
        for (String one : List.of("sms", "otp", "hwk")) {
            assertEquals(OidcAccess.NO_MFA, decide(c, "amr", List.of(one), "groups", List.of("km-admins")).refusal(), one);
        }
        assertEquals(OidcAccess.NO_MFA, decide(c, "amr", List.of("pwd", "kba"), "groups", List.of("km-admins")).refusal(),
            "two methods, both something known");
        // Two methods of one kind, a blank, or a method that is no factor at all: still one factor
        for (List<String> one : List.of(List.of("sms", "tel"), List.of("otp", ""), List.of("otp", " "),
            List.of("hwk", "user"), List.of("swk", "pop"))) {
            assertEquals(OidcAccess.NO_MFA, decide(c, "amr", one, "groups", List.of("km-admins")).refusal(), one.toString());
        }
        assertTrue(decide(c, "amr", List.of("hwk", "fpt"), "groups", List.of("km-admins")).allowed(), "held and own");
        assertTrue(decide(c, "acr", "urn:example:mfa", "groups", List.of("km-admins")).allowed(), "an accepted acr");
        // Turned off, a password alone is enough
        assertTrue(decide(config(false, null), "amr", "pwd", "groups", List.of("km-admins")).allowed());
    }

    /** Counted from the last sign-in at the provider (auth_time), which the token must carry. */
    @Test
    void aSessionLastsNoLongerThanTheLongestAllowed() {
        KilnmarkConfig.Oidc c = config(true, null);
        assertEquals(OidcAccess.TOO_OLD, decide(c, "amr", "mfa", "groups", List.of("km-admins"), "auth_time",
            NOW.minus(Duration.ofHours(9)).getEpochSecond()).refusal());
        // Never the issue time instead: a re-issued token's is always new, and would restart the session for ever
        Map<String, Object> noAuthTime = new HashMap<>(Map.of("amr", "mfa", "groups", List.of("km-admins"),
            "iat", NOW.getEpochSecond()));
        assertEquals(OidcAccess.NO_AUTH_TIME, OidcAccess.decide(c, noAuthTime::get, NOW).refusal());
    }

    @Test
    void jsonClaimsAreReadToo() {
        KilnmarkConfig.Oidc c = config(true, null);
        jakarta.json.JsonArray groups = jakarta.json.Json.createArrayBuilder().add("km-stewards").build();
        jakarta.json.JsonArray amr = jakarta.json.Json.createArrayBuilder().add("pwd").add("hwk").build();
        assertEquals(AccessRole.STEWARD, decide(c, "amr", amr, "groups", groups).role());
    }
}

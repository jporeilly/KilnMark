package io.kilnmark.server.schedules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.kilnmark.server.auth.AccessRole;
import jakarta.ws.rs.WebApplicationException;

/** The rules a schedule is saved and claimed by, without a database (ADR 0046). */
class ScheduleRulesTest {

    private static final Instant NOW = Instant.parse("2026-11-06T10:00:30Z");
    private static final Duration HOUR = Duration.ofHours(1);

    private static ScheduleStore.Schedule due(String cron, String zone, Instant nextAt) {
        return new ScheduleStore.Schedule(7, "p", null, cron, zone, "{}", "steward", true, null, "jo", NOW, "jo", NOW,
            nextAt, null, null, null, null, 3L, null);
    }

    @Test
    void aRunActsAsStewardOrDesignerAndNeverAboveWhoSetIt() {
        assertEquals(AccessRole.STEWARD, ScheduleService.runAs(null, AccessRole.DESIGNER));
        assertEquals(AccessRole.STEWARD, ScheduleService.runAs(" ", AccessRole.ADMIN));
        assertEquals(AccessRole.DESIGNER, ScheduleService.runAs("Designer", AccessRole.DESIGNER));
        assertEquals(AccessRole.DESIGNER, ScheduleService.runAs("designer", AccessRole.ADMIN));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ScheduleService.runAs("admin", AccessRole.ADMIN))
            .getMessage().contains("never acts as an admin"));
        assertThrows(IllegalArgumentException.class, () -> ScheduleService.runAs("viewer", AccessRole.ADMIN));
        WebApplicationException above = assertThrows(WebApplicationException.class,
            () -> ScheduleService.runAs("designer", AccessRole.STEWARD));
        assertEquals(403, above.getResponse().getStatus());
    }

    @Test
    void onlyDesignersAndAdminsSetSchedules() {
        ScheduleService.requireSetter(AccessRole.DESIGNER);
        ScheduleService.requireSetter(AccessRole.ADMIN);
        for (AccessRole r : new AccessRole[] {AccessRole.VIEWER, AccessRole.STEWARD, null}) {
            WebApplicationException refused = assertThrows(WebApplicationException.class,
                () -> ScheduleService.requireSetter(r));
            assertEquals(403, refused.getResponse().getStatus());
        }
    }

    @Test
    void aSetterWithNoIdentityIsRefusedWhileSignInIsOn() {
        ScheduleService service = new ScheduleService(null, null, null, null);
        WebApplicationException refused = assertThrows(WebApplicationException.class,
            () -> service.requireIdentity(new ScheduleStore.Setter("someone", null, null)));
        assertEquals(403, refused.getResponse().getStatus());
        service.requireIdentity(new ScheduleStore.Setter("ci", 4L, null));
        service.requireIdentity(new ScheduleStore.Setter("sso:Pat (p1)", null, "p:0123"));
    }

    /** Settings: one group per role, as the identity provider's groups are mapped now. */
    private static io.kilnmark.server.KilnmarkConfig.Oidc oidc() {
        return new io.kilnmark.server.KilnmarkConfig.Oidc() {
            @Override public String providerName() { return "Example"; }
            @Override public String groupsClaim() { return "groups"; }
            @Override public Optional<List<String>> viewerGroups() { return Optional.of(List.of("km-viewers")); }
            @Override public Optional<List<String>> stewardGroups() { return Optional.of(List.of("km-stewards")); }
            @Override public Optional<List<String>> designerGroups() { return Optional.of(List.of("km-designers")); }
            @Override public Optional<List<String>> adminGroups() { return Optional.of(List.of("km-admins")); }
            @Override public Optional<List<String>> unmaskGroups() { return Optional.empty(); }
            @Override public boolean requireMfa() { return true; }
            @Override public Optional<List<String>> mfaAcr() { return Optional.empty(); }
            @Override public Duration maxSession() { return Duration.ofHours(8); }
        };
    }

    @Test
    void aPersonIsCheckedByTodaysGroupMappingAndARecentSignIn() {
        Duration month = Duration.ofDays(30);
        Instant yesterday = NOW.minus(Duration.ofDays(1));
        // signed in as a designer through km-designers, which still gives designer
        assertNull(Setters.personProblem(new io.kilnmark.server.audit.AuditLog.Standing(AccessRole.DESIGNER,
            List.of("km-designers", "other"), yesterday), AccessRole.DESIGNER, oidc(), month, NOW));
        // km-designers was given designer when they signed in; now it gives nothing: the lower counts
        Setters.Problem p = Setters.personProblem(new io.kilnmark.server.audit.AuditLog.Standing(AccessRole.DESIGNER,
            List.of("old-designers"), yesterday), AccessRole.STEWARD, oidc(), month, NOW);
        assertEquals(ScheduleStore.PAUSED, p.outcome());
        assertEquals("the person's groups no longer give the role", p.reason());
        // their groups give steward now: enough for a steward schedule, not a designer one
        assertNull(Setters.personProblem(new io.kilnmark.server.audit.AuditLog.Standing(AccessRole.DESIGNER,
            List.of("km-stewards"), yesterday), AccessRole.STEWARD, oidc(), month, NOW));
        assertEquals(ScheduleStore.PAUSED, Setters.personProblem(new io.kilnmark.server.audit.AuditLog.Standing(
            AccessRole.DESIGNER, List.of("km-stewards"), yesterday), AccessRole.DESIGNER, oidc(), month, NOW).outcome());
        // groups never raise the recorded role
        assertEquals(ScheduleStore.PAUSED, Setters.personProblem(new io.kilnmark.server.audit.AuditLog.Standing(
            AccessRole.STEWARD, List.of("km-admins"), yesterday), AccessRole.DESIGNER, oidc(), month, NOW).outcome());
        // a sign-in recorded before groups were keeps its role
        assertNull(Setters.personProblem(new io.kilnmark.server.audit.AuditLog.Standing(AccessRole.DESIGNER, null,
            yesterday), AccessRole.DESIGNER, oidc(), month, NOW));
        // no sign-in for longer than the setting: paused, saying so
        Setters.Problem old = Setters.personProblem(new io.kilnmark.server.audit.AuditLog.Standing(AccessRole.DESIGNER,
            List.of("km-designers"), NOW.minus(Duration.ofDays(31))), AccessRole.DESIGNER, oidc(), month, NOW);
        assertEquals("paused: the person who set it has not signed in for 30 days", old.outcome());
        assertEquals("paused", ScheduleService.outcomeWithoutReason(old.outcome()));
    }

    @Test
    void theNextFireIsNeverBeforeTheLastOne() {
        // claimed by a process whose clock was ahead: the next fire comes after that claim, not now
        Instant claimedAt = NOW.plus(Duration.ofMinutes(12));
        ScheduleStore.Schedule s = new ScheduleStore.Schedule(7, "p", null, "*/5 * * * *", "UTC", "{}", "steward",
            true, null, "jo", NOW, "jo", NOW, NOW, claimedAt, null, null, null, 3L, null);
        assertEquals(Instant.parse("2026-11-06T10:15:00Z"), Scheduler.plan(s, NOW, HOUR).nextAt());
        assertEquals(claimedAt, ScheduleStore.later(NOW, claimedAt));
        assertEquals(NOW, ScheduleStore.later(NOW, null));
    }

    @Test
    void aStartingFireCountsAsLostOnlyWhenOld() {
        assertEquals(Duration.ofMinutes(5), ScheduleStore.staleAfter(Duration.ofSeconds(30)));
        assertEquals(Duration.ofMinutes(6), ScheduleStore.staleAfter(Duration.ofMinutes(2)));
        assertEquals(Scheduler.STARTED, Scheduler.kind(Scheduler.STARTED));
        assertEquals("failed", Scheduler.kind("failed: Version 3 of p cannot run: region must be text"));
        assertEquals("missed", Scheduler.kind(ScheduleStore.CHANGED_WHILE_DUE));
    }

    @Test
    void zonesAreIanaNames() {
        assertEquals("UTC", ScheduleService.zone(null).getId());
        assertEquals("Europe/London", ScheduleService.zone(" Europe/London ").getId());
        assertThrows(IllegalArgumentException.class, () -> ScheduleService.zone("+01:00"));
        assertThrows(IllegalArgumentException.class, () -> ScheduleService.zone("Europe/Atlantis"));
    }

    @Test
    void aFireWithinTheCatchUpRunsOnceAndTheNextIsFromNow() {
        ScheduleStore.Claim c = Scheduler.plan(due("*/5 * * * *", "UTC", NOW.minus(Duration.ofMinutes(50))), NOW, HOUR);
        assertNull(c.outcome());
        assertEquals(Instant.parse("2026-11-06T10:05:00Z"), c.nextAt());
    }

    @Test
    void aFireOlderThanTheCatchUpIsMissed() {
        ScheduleStore.Claim c = Scheduler.plan(due("*/5 * * * *", "UTC", NOW.minus(Duration.ofMinutes(61))), NOW, HOUR);
        assertEquals(Scheduler.MISSED, c.outcome());
        assertEquals(Instant.parse("2026-11-06T10:05:00Z"), c.nextAt());
    }

    @Test
    void aScheduleThatCanNoLongerBeReadStopsSayingWhy() {
        ScheduleStore.Claim c = Scheduler.plan(due("0 0 * *", "UTC", NOW), NOW, HOUR);
        assertNull(c.nextAt());
        assertTrue(c.outcome().startsWith(Scheduler.FAILED + "cron:"), c.outcome());
    }

    @Test
    void viewersSeeThatAFireFailedNotWhy() {
        assertEquals("failed", ScheduleService.outcomeWithoutReason("failed: Version 3 of p cannot run"));
        assertEquals("failed", ScheduleService.outcomeWithoutReason(ScheduleStore.UNQUEUED));
        assertEquals(Scheduler.SKIPPED, ScheduleService.outcomeWithoutReason(Scheduler.SKIPPED));
        assertEquals(Scheduler.MISSED, ScheduleService.outcomeWithoutReason(Scheduler.MISSED));
        assertEquals(Scheduler.STARTED, ScheduleService.outcomeWithoutReason(Scheduler.STARTED));
        assertEquals("paused", ScheduleService.outcomeWithoutReason(ScheduleStore.PAUSED));
        assertNull(ScheduleService.outcomeWithoutReason(null));
    }

    @Test
    void longOutcomesAreClipped() {
        String clipped = ScheduleStore.clip("x".repeat(900));
        assertEquals(ScheduleStore.MAX_OUTCOME, clipped.length());
        assertTrue(clipped.endsWith("..."));
    }
}

package io.kilnmark.server.schedules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import io.kilnmark.server.auth.AccessRole;
import jakarta.ws.rs.WebApplicationException;

/** The rules a schedule is saved and claimed by, without a database (ADR 0046). */
class ScheduleRulesTest {

    private static final Instant NOW = Instant.parse("2026-11-06T10:00:30Z");
    private static final Duration HOUR = Duration.ofHours(1);

    private static ScheduleStore.Schedule due(String cron, String zone, Instant nextAt) {
        return new ScheduleStore.Schedule(7, "p", null, cron, zone, "{}", "steward", true, null, "jo", NOW, "jo", NOW,
            nextAt, null, null, null, null);
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
    void theNextFireIsNeverBeforeTheLastOne() {
        // claimed by a process whose clock was ahead: the next fire comes after that claim, not now
        Instant claimedAt = NOW.plus(Duration.ofMinutes(12));
        ScheduleStore.Schedule s = new ScheduleStore.Schedule(7, "p", null, "*/5 * * * *", "UTC", "{}", "steward",
            true, null, "jo", NOW, "jo", NOW, NOW, claimedAt, null, null, null);
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
        assertNull(ScheduleService.outcomeWithoutReason(null));
    }

    @Test
    void longOutcomesAreClipped() {
        String clipped = ScheduleStore.clip("x".repeat(900));
        assertEquals(ScheduleStore.MAX_OUTCOME, clipped.length());
        assertTrue(clipped.endsWith("..."));
    }
}

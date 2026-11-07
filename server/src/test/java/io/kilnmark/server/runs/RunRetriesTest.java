package io.kilnmark.server.runs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.yaml.Position;
import io.kilnmark.kiln.yaml.RunOptions;

/**
 * Whether a try is tried again, and when (ADR 0053, decisions 2 to 5): every rule on its own, with no
 * database. The services pass what they find; these are the rules they follow.
 */
class RunRetriesTest {

    private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
    private static final RandomGenerator NO_SPREAD = new RandomGenerator() {
        @Override
        public long nextLong() {
            return 0;
        }

        @Override
        public double nextDouble() {
            return 0;
        }
    };

    private static RunOptions retries(int n, Duration delay, RunOptions.RetryOn on) {
        return new RunOptions(n, delay, on, null, null, Position.START);
    }

    private static RunRetries.Decision decide(RunOptions o, int tried, RunRetries.Ending ending) {
        return RunRetries.decide(o, o.tries(5), tried, ending, null, null, NOW, NO_SPREAD);
    }

    @Test
    void aFailureIsTriedAgainAfterTheDelayDoubledEachTime() {
        RunOptions o = retries(3, Duration.ofMinutes(5), RunOptions.RetryOn.ANY);
        RunRetries.Decision first = decide(o, 0, RunRetries.Ending.ERROR);
        assertTrue(first.again());
        assertEquals(NOW.plus(Duration.ofMinutes(5)), first.at());
        assertEquals(NOW.plus(Duration.ofMinutes(10)), decide(o, 1, RunRetries.Ending.ERROR).at());
        assertEquals(NOW.plus(Duration.ofMinutes(20)), decide(o, 2, RunRetries.Ending.ERROR).at());
        RunRetries.Decision last = decide(o, 3, RunRetries.Ending.ERROR);
        assertFalse(last.again(), "4 tries in all");
        assertEquals("Not tried again: all 4 tries were made", last.why());
    }

    @Test
    void aRetryStormIsHeldBackByTheCapAndTheSpread() {
        // Five runs failing together against one source, each from the longest delay: no wait is more than
        // an hour and a tenth, and the spread keeps them from coming back at the same moment.
        RunOptions o = retries(5, RunOptions.MAX_RETRY_DELAY, RunOptions.RetryOn.ANY);
        RandomGenerator random = RandomGenerator.of("L64X128MixRandom");
        java.util.Set<Instant> when = new java.util.HashSet<>();
        for (int run = 0; run < 5; run++) {
            for (int tried = 0; tried < 5; tried++) {
                RunRetries.Decision d = RunRetries.decide(o, 6, tried, RunRetries.Ending.ERROR, null, null, NOW, random);
                Duration wait = Duration.between(NOW, d.at());
                assertTrue(wait.compareTo(Duration.ofHours(1)) >= 0 && wait.compareTo(Duration.ofMinutes(66)) <= 0,
                    "each wait is the capped hour plus at most a tenth: " + wait);
                if (tried == 4) {
                    when.add(d.at());
                }
            }
        }
        assertTrue(when.size() > 1, "the spread puts the runs' last tries apart");
    }

    @Test
    void aCancelARefusalAGateAndACommitInDoubtAreNeverTriedAgain() {
        RunOptions o = retries(5, Duration.ofMinutes(1), RunOptions.RetryOn.ANY);
        assertEquals("Not tried again: a cancelled run is never tried again",
            decide(o, 0, RunRetries.Ending.CANCELLED).why());
        assertFalse(decide(o, 0, RunRetries.Ending.REFUSED).again());
        assertFalse(decide(o, 0, RunRetries.Ending.GATE).again());
        assertFalse(decide(o, 0, RunRetries.Ending.IN_DOUBT).again());
        assertFalse(decide(o, 0, RunRetries.Ending.STOPPED).again());
        assertTrue(decide(o, 0, RunRetries.Ending.ERROR).again());
        assertTrue(decide(o, 0, RunRetries.Ending.LOST).again());
        assertTrue(decide(o, 0, RunRetries.Ending.TIMED_OUT).again(), "a timed-out try may be tried again");
    }

    @Test
    void retryOnLostTriesAgainOnlyALostTry() {
        RunOptions o = retries(2, Duration.ofMinutes(1), RunOptions.RetryOn.LOST);
        assertTrue(decide(o, 0, RunRetries.Ending.LOST).again());
        RunRetries.Decision error = decide(o, 0, RunRetries.Ending.ERROR);
        assertFalse(error.again());
        assertTrue(error.why().contains("retry_on: lost"), error.why());
        assertFalse(decide(o, 0, RunRetries.Ending.TIMED_OUT).again());
    }

    @Test
    void writesThatAreNotSafeToRepeatAreNeverTriedAgain() {
        RunOptions o = retries(2, Duration.ofMinutes(1), RunOptions.RetryOn.ANY);
        RunRetries.Decision d = RunRetries.decide(o, 3, 0, RunRetries.Ending.ERROR, "write-orders appends", null, NOW,
            NO_SPREAD);
        assertFalse(d.again());
        assertEquals("Not tried again: trying again could write twice: write-orders appends", d.why());
    }

    @Test
    void noTryStartsAfterTheSchedulesNextFire() {
        RunOptions o = retries(2, Duration.ofMinutes(10), RunOptions.RetryOn.ANY);
        RunRetries.Decision before = RunRetries.decide(o, 3, 0, RunRetries.Ending.ERROR, null,
            NOW.plus(Duration.ofMinutes(11)), NOW, NO_SPREAD);
        assertTrue(before.again(), "the try starts before the next fire");
        RunRetries.Decision after = RunRetries.decide(o, 3, 0, RunRetries.Ending.ERROR, null,
            NOW.plus(Duration.ofMinutes(10)), NOW, NO_SPREAD);
        assertFalse(after.again(), "a try due as the next fire is due would run into it");
        assertEquals("Not tried again: the next scheduled run is due first", after.why());
    }

    @Test
    void theServerCapAndARunWithNoRetries() {
        RunOptions o = retries(4, Duration.ofMinutes(1), RunOptions.RetryOn.ANY);
        assertEquals(1, o.tries(0), "0 turns retries off on the server");
        RunRetries.Decision off = RunRetries.decide(o, o.tries(0), 0, RunRetries.Ending.ERROR, null, null, NOW, NO_SPREAD);
        assertFalse(off.again());
        assertTrue(off.why().contains("off on this server"), off.why());
        RunRetries.Decision none = RunRetries.decide(RunOptions.NONE, 1, 0, RunRetries.Ending.ERROR, null, null, NOW,
            NO_SPREAD);
        assertFalse(none.again());
        assertTrue(none.why().contains("no retries"), none.why());
        assertEquals(3, o.tries(2), "the cap limits a run's own retries");
    }

    @Test
    void whatARunWaitingForItsNextTrySays() {
        assertEquals("Try 1 of 3 failed; trying again at 10:05 (UTC)",
            RunRetries.waiting(0, 3, NOW.plus(Duration.ofMinutes(5)), ZoneId.of("UTC")));
        assertEquals("", RunRetries.afterTries(0));
        assertEquals(", after 3 tries", RunRetries.afterTries(2));
        assertEquals("Took longer than 2h and was stopped", RunRetries.tookTooLong(Duration.ofHours(2)));
    }

    @Test
    void theServerSettingsAreCheckedWhenItStarts() {
        assertNull(RunSettings.problem(Duration.ofMinutes(2), 5));
        assertNull(RunSettings.problem(Duration.ofSeconds(30), 0));
        assertNull(RunSettings.problem(Duration.ofMinutes(30), 5));
        assertTrue(RunSettings.problem(Duration.ofSeconds(29), 5).contains("lost-after"));
        assertTrue(RunSettings.problem(Duration.ofMinutes(31), 5).contains("lost-after"));
        assertTrue(RunSettings.problem(Duration.ofMinutes(2), 6).contains("max-retries"));
        assertTrue(RunSettings.problem(Duration.ofMinutes(2), -1).contains("max-retries"));
        assertThrows(IllegalStateException.class, () -> new RunSettings(Duration.ofHours(1), 5));
        assertEquals(3, new RunSettings(Duration.ofMinutes(2), 2).tries(retries(5, Duration.ofMinutes(1),
            RunOptions.RetryOn.ANY)));
    }

    @Test
    void ownersAreSignInNamesOrTeamAddressesAnAdminAdded() {
        java.util.Set<String> teams = java.util.Set.of("Data-Team@example.com");
        assertEquals(List.of("alice", "data-team@example.com", "sso:Bob Smith (b-1)"),
            Owners.check(List.of(" alice ", "DATA-team@example.com", "sso:Bob Smith (b-1)", "alice"), teams),
            "trimmed, addresses lower-cased, repeats left out");
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
            () -> Owners.check(List.of("someone@elsewhere.example"), teams));
        assertTrue(unknown.getMessage().contains("an admin has added"), unknown.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Owners.check(java.util.stream.IntStream.range(0, 11)
            .mapToObj(i -> "p" + i).toList(), teams), "at most 10");
        assertThrows(IllegalArgumentException.class, () -> Owners.check(List.of(""), teams));
        assertThrows(IllegalArgumentException.class, () -> Owners.check(List.of("a\u0007b"), teams));
        assertThrows(IllegalArgumentException.class, () -> Owners.check(null, teams));
        assertEquals(List.of(), Owners.check(List.of(), teams), "none is allowed: admins are told then");
        assertTrue(Owners.team("data-team@example.com"));
        assertFalse(Owners.team("sso:alice@example.com (a-1)"), "a provider sign-in with an email is a person");
        assertFalse(Owners.team("alice"));
        assertEquals(List.of("alice"), Owners.defaultFor("alice"));
        assertEquals(List.of(), Owners.defaultFor("anonymous"));
        assertEquals(List.of(), Owners.defaultFor("server (samples)"));
        Owners.Shown hidden = Owners.shown(List.of("alice", "data-team@example.com"), false);
        assertNull(hidden.owners().get(1).entry(), "a team address is shown to designers and admins only");
        assertEquals("A team address", hidden.owners().get(1).label());
        assertEquals("data-team@example.com", Owners.shown(List.of("data-team@example.com"), true).owners().get(0)
            .entry());
        assertTrue(Owners.shown(List.of(), false).none());
    }
}

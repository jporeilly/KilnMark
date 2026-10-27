package io.kilnmark.server.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** One email per failing streak, one when it recovers, and nothing for repeats. */
class DedupeTest {

    /** Plays events through the decision, keeping the state as the store would. */
    private static final class Problem {
        Dedupe.State state;
        int mailed;
        Severity last;

        boolean fire(NotificationKind kind, Severity severity) {
            Dedupe.Decision d = Dedupe.decide(kind, severity, state);
            if (d.next() != null) {
                state = d.next();
            }
            if (d.send()) {
                mailed++;
                last = d.severity();
            }
            return d.send();
        }
    }

    @Test
    void aRuleFailingOnEveryScanIsToldOnceThenOnceWhenItRecovers() {
        Problem p = new Problem();
        assertTrue(p.fire(NotificationKind.RULE_FAILED, Severity.HIGH), "the first failure is told");
        for (int scan = 0; scan < 10; scan++) {
            assertFalse(p.fire(NotificationKind.RULE_FAILED, Severity.HIGH), "the same failure on scan " + scan);
        }
        assertTrue(p.fire(NotificationKind.RULE_RECOVERED, Severity.LOW), "the recovery is told");
        assertEquals(Severity.HIGH, p.last, "at the failure's severity, so the same people hear of it");
        assertFalse(p.fire(NotificationKind.RULE_RECOVERED, Severity.LOW), "passing again is not news");
        assertTrue(p.fire(NotificationKind.RULE_FAILED, Severity.MEDIUM), "a new streak is told");
        assertEquals(3, p.mailed);
    }

    @Test
    void aProblemThatGetsWorseIsToldAgainButNotWhenItEases() {
        Problem p = new Problem();
        assertTrue(p.fire(NotificationKind.RULE_FAILED, Severity.MEDIUM));
        assertTrue(p.fire(NotificationKind.RULE_FAILED, Severity.HIGH), "a warning that becomes a failure");
        assertFalse(p.fire(NotificationKind.RULE_FAILED, Severity.MEDIUM), "back to a warning: still the same streak");
        assertFalse(p.fire(NotificationKind.RULE_FAILED, Severity.HIGH), "failing again at the level already told");
        assertTrue(p.fire(NotificationKind.RULE_RECOVERED, Severity.LOW));
        assertEquals(Severity.HIGH, p.last, "the recovery reaches everyone who heard of the worst of it");
    }

    @Test
    void aRecoveryNobodyHeardTheFailureOfIsNotTold() {
        Problem p = new Problem();
        assertFalse(p.fire(NotificationKind.RULE_RECOVERED, Severity.LOW));
        assertNull(p.state);
    }

    @Test
    void oneOffEventsAreToldOncePerKey() {
        Problem p = new Problem();
        assertTrue(p.fire(NotificationKind.DRIFT, Severity.HIGH));
        assertFalse(p.fire(NotificationKind.DRIFT, Severity.HIGH), "the same event again (a re-run) is dropped");
        assertFalse(p.state.open(), "a one-off event leaves nothing open");
    }

    @Test
    void clearingClosesQuietlySoTheNextFailureIsTold() {
        Problem p = new Problem();
        assertTrue(p.fire(NotificationKind.SCAN_FAILED, Severity.HIGH));
        assertFalse(p.fire(NotificationKind.SCAN_FAILED, Severity.HIGH));
        p.state = Dedupe.clear(p.state);
        assertFalse(p.state.open());
        assertTrue(p.fire(NotificationKind.SCAN_FAILED, Severity.HIGH), "failing again after a good scan");
        assertNull(Dedupe.clear(null), "clearing a problem never seen keeps nothing");
    }

    @Test
    void everyKindSaysHowItRepeats() {
        assertEquals(NotificationKind.Repeat.OPENS, NotificationKind.RULE_FAILED.repeat());
        assertEquals(NotificationKind.Repeat.CLOSES, NotificationKind.RULE_RECOVERED.repeat());
        assertEquals(NotificationKind.Repeat.OPENS, NotificationKind.CONNECTION_FAILED.repeat());
        assertEquals(NotificationKind.Repeat.ONCE, NotificationKind.ISSUE_OPENED.repeat());
        assertEquals("rule_failed", NotificationKind.RULE_FAILED.id());
        assertEquals("pipeline_low_quality", NotificationKind.PIPELINE_LOW_QUALITY.id());
        assertEquals("drift", NotificationKind.DRIFT.id());
    }
}

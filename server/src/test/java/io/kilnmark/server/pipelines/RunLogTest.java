package io.kilnmark.server.pipelines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.graph.RunListener;
import io.kilnmark.kiln.graph.RunProgress;
import io.kilnmark.server.auth.AccessRole;

/** The run's log and progress as the worker keeps them, without a database. */
class RunLogTest {

    static final ObjectMapper JSON = new ObjectMapper();

    /** Keeps what would be written; can be told to fail. */
    static final class Kept extends PipelineStore {
        final List<PipelineStore.Event> events = new ArrayList<>();
        final List<String> progress = new ArrayList<>();
        final List<String> series = new ArrayList<>();
        boolean broken;
        /** Refuses any batch holding an event whose text has this in it. */
        String refuse;

        Kept() {
            super(null);
        }

        @Override
        public void addEvents(long runId, List<Event> batch) {
            if (broken || refuse != null && batch.stream().anyMatch(e -> e.text().contains(refuse))) {
                throw new IllegalStateException("database down");
            }
            events.addAll(batch);
        }

        @Override
        public void saveProgress(long runId, String p, String s) {
            if (broken) {
                throw new IllegalStateException("database down");
            }
            progress.add(p);
            series.add(s);
        }
    }

    private static RunContext context() {
        RunContext c = new RunContext("p", 7, "jo", AccessRole.STEWARD);
        c.secret("hunter22");
        return c;
    }

    @Test
    void theEnginesWordsAreTheDetailWithSecretsBlankedOut() {
        Kept store = new Kept();
        RunLog log = new RunLog(store, 7, context(), JSON);
        Instant t = Instant.parse("2026-10-28T10:00:00Z");
        log.stepStarted("read", t);
        log.firstRowOut("read", t);
        log.stepEnded("write", RunResult.Outcome.FAILED, t);
        log.note(RunListener.Level.ERROR, "write", "login hunter22 refused the value 'Ann'");
        log.flush();
        assertEquals(List.of(1, 2, 3, 4), store.events.stream().map(PipelineStore.Event::seq).toList());
        assertEquals("Step read started", store.events.get(0).text());
        assertEquals("error", store.events.get(2).level());
        assertEquals("Step write failed", store.events.get(2).text());
        PipelineStore.Event note = store.events.get(3);
        assertEquals("engine_note", note.kind());
        assertEquals("Step write reported an error", note.text());
        assertFalse(note.text().contains("Ann"), "the words are the server's own");
        assertEquals("login ******** refused the value 'Ann'", note.detail());
    }

    @Test
    void aGatesVerdictIsInTheLogWithItsReasonsAsTheDetail() {
        Kept store = new Kept();
        RunLog log = new RunLog(store, 7, context(), JSON);
        QualityGate.RuleOutcome email = new QualityGate.RuleOutcome("email format", "pattern", "email", "validity",
            "inline", "fail", 229, 5000, "'ann@' is not an email");
        QualityGate.RuleOutcome ok = new QualityGate.RuleOutcome("id present", "not_null", "id", "completeness",
            "inline", "pass", 0, 5000, null);
        log.gate("check", new QualityGate.Outcome("quarantine", "quarantined", null, 5000, 4771, 229, 229, 0.95,
            java.util.Map.of(), List.of(), List.of(email, ok), null, null, null));
        log.gate("strict", new QualityGate.Outcome("stop", "stopped", null, 10, 0, 10, 0, 0.0, java.util.Map.of(),
            List.of(), List.of(), null, null, "10 of 10 rows failed"));
        log.gate("none", null);
        log.flush();
        assertEquals(2, store.events.size());
        PipelineStore.Event q = store.events.get(0);
        assertEquals("gate_verdict", q.kind());
        assertEquals("warn", q.level());
        assertEquals("Gate check quarantined 229 rows (email format: 229)", q.text(),
            "the failing rules and their counts, as the run's quality section shows them to everyone");
        assertEquals("email format: 'ann@' is not an email", q.detail(), "a rule's message can quote values");
        assertEquals("error", store.events.get(1).level());
        assertEquals("Gate strict stopped the run", store.events.get(1).text());
        assertEquals("10 of 10 rows failed", store.events.get(1).detail());
    }

    @Test
    void theLogStopsAtTenThousandEventsAndSaysSo() {
        Kept store = new Kept();
        RunLog log = new RunLog(store, 7, context(), JSON);
        for (int i = 0; i < RunLog.MAX_EVENTS + 50; i++) {
            log.stepStarted("s" + i, Instant.now());
        }
        log.flush();
        assertEquals(RunLog.MAX_EVENTS + 1, store.events.size());
        PipelineStore.Event last = store.events.getLast();
        assertEquals("log_truncated", last.kind());
        assertEquals(RunLog.MAX_EVENTS + 1, last.seq());
        assertTrue(last.text().contains("10,000"), last.text());
    }

    @Test
    void aBrokenDatabaseNeverFailsTheRun() {
        Kept store = new Kept();
        store.broken = true;
        RunLog log = new RunLog(store, 7, context(), JSON);
        log.stepStarted("read", Instant.now());
        log.progress(new RunProgress(Instant.now(), List.of(), List.of(), null));
        log.finish();
        log.flush();
        assertTrue(store.events.isEmpty());
    }

    @Test
    void progressIsCountsStatesAndTimesWrittenAtMostEveryTwoSeconds() throws Exception {
        Kept store = new Kept();
        RunLog log = new RunLog(store, 7, context(), JSON);
        Instant t = Instant.parse("2026-10-28T10:00:00Z");
        RunProgress running = new RunProgress(t, List.of(
            new RunProgress.StepProgress("read", RunProgress.State.RUNNING, 0, 900, 450.04, t, t, t, null, 0,
                1_500_000_000L, 1.0),
            new RunProgress.StepProgress("write", RunProgress.State.RUNNING, 800, 0, 400, t, t, t, null, 20_000_000L, 0,
                0)),
            List.of(new RunProgress.EdgeProgress("read", "out", "write", "in", 800, 4, 4, true, false, 1.0)), "write");
        log.progress(running);
        log.progress(running); // too soon: not written again
        assertEquals(1, store.progress.size());
        JsonNode p = JSON.readTree(store.progress.getFirst());
        assertEquals("write", p.path("slowest").asText());
        assertEquals("running", p.path("steps").get(0).path("state").asText());
        assertEquals(450.0, p.path("steps").get(0).path("rowsPerSecond").asDouble());
        assertTrue(p.path("steps").get(0).path("finishedAt").isNull());
        assertTrue(p.path("edges").get(0).path("full").asBoolean());
        assertEquals(1.0, p.path("edges").get(0).path("fill").asDouble());
        assertEquals(1500, p.path("steps").get(0).path("heldUpMs").asLong());
        assertEquals(20, p.path("steps").get(1).path("waitingForRowsMs").asLong());
        assertEquals(1.0, p.path("steps").get(0).path("outputFill").asDouble());
        assertEquals(log.workerStarted().toString(), p.path("workerStartedAt").asText(), "one clock: the worker's");
        JsonNode series = JSON.readTree(store.series.getFirst());
        assertEquals(List.of("read", "write"), List.of(series.path("steps").get(0).asText(), series.path("steps").get(1).asText()));
        assertEquals(450.0, series.path("points").get(0).path("r").get(0).asDouble());
        assertEquals(1.0, series.path("points").get(0).path("q").get(0).asDouble());
        RunProgress ended = new RunProgress(t, List.of(
            new RunProgress.StepProgress("read", RunProgress.State.ENDED, 0, 900, 0, t, t, t, t, 0, 0, 0)), List.of(),
            null);
        log.progress(ended); // the last one is always written
        assertEquals(2, store.progress.size());
        assertNull(JSON.readTree(store.progress.getLast()).path("slowest").textValue());
        log.finish();
        assertTrue(JSON.readTree(store.progress.getLast()).hasNonNull("workerFinishedAt"));
    }

    @Test
    void theSeriesKeepsAtMostNineHundredPointsAndAGateStartingToQuarantineIsMarked() {
        Kept store = new Kept();
        RunLog log = new RunLog(store, 7, context(), JSON);
        Instant t = Instant.now();
        for (int i = 0; i < 5_000; i++) {
            long rows = i > 10 ? 5 : 0;
            log.progress(new RunProgress(t.plusMillis(i * 2_000L), List.of(
                new RunProgress.StepProgress("check", RunProgress.State.RUNNING, 10, 10, 5, t, t, t, null, 0, 0, 0.5)),
                List.of(new RunProgress.EdgeProgress("check", "quarantine", "aside", "in", rows, 0, 4, false, true, 0)),
                null));
        }
        assertTrue(log.points() < RunLog.MAX_POINTS && log.points() > RunLog.MAX_POINTS / 4, String.valueOf(log.points()));
        log.flush();
        assertEquals(1, store.events.stream().filter(e -> e.kind().equals("gate_quarantining")).count());
        assertEquals("Gate check started setting rows aside", store.events.getFirst().text());
    }

    @Test
    void oneEventTheDatabaseRefusesDoesNotLoseTheRest() {
        Kept store = new Kept();
        store.refuse = "poison";
        RunLog log = new RunLog(store, 7, context(), JSON);
        log.server(null, "info", "run_note", "fine", null);
        log.server(null, "info", "run_note", "poison", null);
        log.server(null, "info", "run_note", "also fine", "with a \u0000 NUL");
        log.flush();
        assertEquals(List.of("fine", "also fine"), store.events.stream().map(PipelineStore.Event::text).toList());
        assertEquals("with a  NUL", store.events.get(1).detail(), "NUL characters are dropped");
        assertEquals("ab", RunLog.cut("a\u0000b", 10));
    }
}

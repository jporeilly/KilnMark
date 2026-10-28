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
        boolean broken;

        Kept() {
            super(null);
        }

        @Override
        public void addEvents(long runId, List<Event> batch) {
            if (broken) {
                throw new IllegalStateException("database down");
            }
            events.addAll(batch);
        }

        @Override
        public void saveProgress(long runId, String p) {
            if (broken) {
                throw new IllegalStateException("database down");
            }
            progress.add(p);
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
        log.flush();
        assertTrue(store.events.isEmpty());
    }

    @Test
    void progressIsCountsStatesAndTimesWrittenAtMostEveryTwoSeconds() throws Exception {
        Kept store = new Kept();
        RunLog log = new RunLog(store, 7, context(), JSON);
        Instant t = Instant.parse("2026-10-28T10:00:00Z");
        RunProgress running = new RunProgress(t, List.of(
            new RunProgress.StepProgress("read", RunProgress.State.RUNNING, 0, 900, 450.04, t, t, t, null),
            new RunProgress.StepProgress("write", RunProgress.State.RUNNING, 800, 0, 400, t, t, t, null)),
            List.of(new RunProgress.EdgeProgress("read", "out", "write", "in", 800, 4, 4, true, false)), "write");
        log.progress(running);
        log.progress(running); // too soon: not written again
        assertEquals(1, store.progress.size());
        JsonNode p = JSON.readTree(store.progress.getFirst());
        assertEquals("write", p.path("slowest").asText());
        assertEquals("running", p.path("steps").get(0).path("state").asText());
        assertEquals(450.0, p.path("steps").get(0).path("rowsPerSecond").asDouble());
        assertTrue(p.path("steps").get(0).path("finishedAt").isNull());
        assertTrue(p.path("edges").get(0).path("full").asBoolean());
        RunProgress ended = new RunProgress(t, List.of(
            new RunProgress.StepProgress("read", RunProgress.State.ENDED, 0, 900, 0, t, t, t, t)), List.of(), null);
        log.progress(ended); // the last one is always written
        assertEquals(2, store.progress.size());
        assertNull(JSON.readTree(store.progress.getLast()).path("slowest").textValue());
    }
}

package io.kilnmark.server.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import jakarta.enterprise.inject.Vetoed;

/** The one-line hook pipelines call when a run finishes: what it tells, and when it stays quiet. */
class PipelineHookTest {

    /** A publisher that keeps what it would have fired. */
    @Vetoed // not a second bean: the parent's scope annotation is @Inherited
    static final class Fake extends NotificationPublisher {
        final List<NotificationEvent> fired = new ArrayList<>();
        /** Recovered notices: fired on every success, told only when a failure was (Dedupe, Repeat.CLOSES). */
        final List<NotificationEvent> recovered = new ArrayList<>();
        final List<String> clearedKeys = new ArrayList<>();

        @Override
        public void publish(NotificationEvent e) {
            (e.kind() == NotificationKind.PIPELINE_RECOVERED ? recovered : fired).add(e);
        }

        @Override
        public void clear(String key) {
            clearedKeys.add(key);
        }
    }

    @Test
    void aFailedRunIsToldWithALinkAndClearedBySuccess() {
        Fake p = new Fake();
        p.pipelineRunFinished("nightly-load", 42, "failed", null, null, "#/pipeline-runs/42");
        assertEquals(1, p.fired.size());
        NotificationEvent e = p.fired.get(0);
        assertEquals(NotificationKind.PIPELINE_FAILED, e.kind());
        assertEquals(Severity.HIGH, e.severity());
        assertEquals("Run 42 of nightly-load failed", e.title());
        assertEquals("#/pipeline-runs/42", e.link());
        assertEquals("pipeline:nightly-load", e.dedupeKey(), "one email per failing streak of the pipeline");
        assertNull(e.subjectUrn());
        assertEquals("pipeline:nightly-load", e.ownedBy(), "its owners are told by right (ADR 0053)");
        p.pipelineRunFinished("nightly-load", 43, "succeeded", null, null, "#/pipeline-runs/43");
        assertEquals(1, p.fired.size(), "a success tells nothing else");
        // It ends the streak as a recovery on the same key: told only when the failure was (ADR 0053).
        assertEquals(1, p.recovered.size());
        assertEquals("pipeline:nightly-load", p.recovered.get(0).dedupeKey());
        assertEquals("pipeline:nightly-load", p.recovered.get(0).ownedBy());
    }

    @Test
    void cancelledRunsAndSuccessesAboveThresholdStayQuiet() {
        Fake p = new Fake();
        p.pipelineRunFinished("x", 1, "cancelled", null, null, null);
        p.pipelineRunFinished("x", 2, "succeeded", 0.9, 0.8, null);
        p.pipelineRunFinished("x", 3, "succeeded", null, 0.8, null);
        assertTrue(p.fired.isEmpty(), p.fired.toString());
    }

    @Test
    void aSuccessBelowThresholdIsToldEachTime() {
        Fake p = new Fake();
        p.pipelineRunFinished("x", 7, "succeeded", 0.5, 0.8, "#/pipeline-runs/7");
        assertEquals(NotificationKind.PIPELINE_LOW_QUALITY, p.fired.get(0).kind());
        assertEquals("pipeline-quality:7", p.fired.get(0).dedupeKey());
        assertTrue(p.fired.get(0).detail().contains("50"), p.fired.get(0).detail());
    }

    @Test
    void theLowQualityNoticeNamesTheOutputAndComesOncePerRun() {
        Fake p = new Fake();
        p.pipelineRunFinished("x", 8, "succeeded", 0.912, 0.95, "good", "#/pipeline-runs/8");
        assertEquals(1, p.fired.size());
        NotificationEvent e = p.fired.get(0);
        assertEquals(NotificationKind.PIPELINE_LOW_QUALITY, e.kind());
        assertEquals("Quality score 91.2% for what node good wrote, below the pipeline's threshold of 95%.", e.detail());
        assertEquals("pipeline-quality:8", e.dedupeKey(), "one per run, whatever else is told");
        // At the threshold is not below it; a failed run is told as a failure, not as low quality.
        p.pipelineRunFinished("x", 9, "succeeded", 0.95, 0.95, "good", null);
        p.pipelineRunFinished("x", 10, "failed", 0.1, 0.95, "good", null);
        assertEquals(List.of(NotificationKind.PIPELINE_LOW_QUALITY, NotificationKind.PIPELINE_FAILED),
            p.fired.stream().map(NotificationEvent::kind).toList());
    }
}

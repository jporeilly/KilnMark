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
        final List<String> cleared = new ArrayList<>();

        @Override
        public void publish(NotificationEvent e) {
            fired.add(e);
        }

        @Override
        public void clear(String key) {
            cleared.add(key);
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
        p.pipelineRunFinished("nightly-load", 43, "succeeded", null, null, "#/pipeline-runs/43");
        assertEquals(1, p.fired.size(), "a success tells nothing on its own");
        assertEquals(List.of("pipeline:nightly-load"), p.cleared, "but ends the streak");
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

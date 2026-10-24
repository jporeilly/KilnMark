package io.kilnmark.kiln;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** How a pipeline run ended, step by step. */
public record RunResult(String pipeline, Status status, Instant startedAt, Instant finishedAt,
                        List<StepResult> steps, String error) {

    public enum Status { SUCCEEDED, FAILED }

    /** One step's part in a run. {@code error} is null unless this step is where the run failed. */
    public record StepResult(String name, String type, long rowsOut, long peakMemoryBytes, String error) {
    }

    public RunResult {
        steps = List.copyOf(steps);
    }

    public Duration duration() {
        return Duration.between(startedAt, finishedAt);
    }

    public boolean succeeded() {
        return status == Status.SUCCEEDED;
    }
}

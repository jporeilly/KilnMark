package io.kilnmark.server.schedules;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/** What the schedules API takes and gives (docs/design/schedules.md). */
public final class ScheduleViews {

    private ScheduleViews() {
    }

    /**
     * A schedule. {@code parameters} is null for viewers, who never see the values a run starts
     * with. {@code nextAt} is null while it is paused; {@code lastOutcome} says what happened the
     * last time it was due, with the run it started.
     */
    public record Schedule(long id, String pipeline, String flow, String cron, String zone, JsonNode parameters,
                           String runAs, boolean enabled, String description, String createdBy, Instant createdAt,
                           String updatedBy, Instant updatedAt, Instant nextAt, Instant lastAt, String lastOutcome,
                           Long lastPipelineRun, Long lastFlowRun) {
    }

    /**
     * A schedule to add, or what to replace one with: one of pipeline or flow; cron (five fields);
     * zone (default UTC); the values its runs start with; runAs (steward, the default, or designer);
     * enabled (default true); and a short description.
     */
    public record ScheduleChange(String pipeline, String flow, String cron, String zone,
                                 Map<String, Object> parameters, String runAs, Boolean enabled, String description) {
    }

    /** A cron expression and zone to try. */
    public record PreviewRequest(String cron, String zone) {
    }

    /** When an expression would fire next, or why it cannot be used. */
    public record Preview(boolean valid, String problem, String cron, String zone, List<Instant> next) {
    }
}

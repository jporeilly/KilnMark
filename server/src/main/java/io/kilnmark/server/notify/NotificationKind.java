package io.kilnmark.server.notify;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * The kinds of event that notify people, in the app and by email. Each kind says how repeats are handled
 * ({@link Repeat}): a problem that persists (a failing rule, a source that cannot be reached) is
 * mailed once until it clears, while one-off events (an issue opened, drift found) are mailed each
 * time. The ids ({@code rule_failed}, {@code drift}, ...) are part of the API (the in-app feed and
 * subscriptions) and are stored: never rename one.
 */
public enum NotificationKind {
    /** A quality rule warns, fails or cannot run, having passed (or never run) before. */
    RULE_FAILED("Quality rule failing", "A rule starts to warn, fail or not run", Repeat.OPENS),
    /** A rule that was failing passes again. */
    RULE_RECOVERED("Quality rule recovered", "A rule that was failing passes again", Repeat.CLOSES),
    /** A new issue in the stewards' issue list. */
    ISSUE_OPENED("Issue opened", "A new task in the issue list, from a failing rule or from drift", Repeat.ONCE),
    /** A scan of a source failed. */
    SCAN_FAILED("Scan failed", "A scan of a source fails", Repeat.OPENS),
    /** A scan found changes since the previous one: schema, shape, volume or freshness. */
    DRIFT("Drift detected", "Columns added, removed or retyped, shapes that change, row counts that drop "
        + "or spike, data that stops arriving", Repeat.ONCE),
    /** Testing a saved source's connection failed. */
    CONNECTION_FAILED("Connection test failed", "Testing a source's connection fails", Repeat.OPENS),
    /** A pipeline run failed (wired up by the pipelines change; see NotificationPublisher#pipelineRunFinished). */
    PIPELINE_FAILED("Pipeline run failed", "A pipeline run fails", Repeat.OPENS),
    /** A pipeline run succeeded, but the quality of what it wrote is below its threshold. */
    PIPELINE_LOW_QUALITY("Pipeline quality below threshold", "A pipeline run succeeds but what it wrote scores "
        + "below the pipeline's quality threshold", Repeat.ONCE),
    /** A flow run failed (docs/design/flows.md). */
    FLOW_FAILED("Flow run failed", "A flow run fails", Repeat.OPENS),
    /** A flow's notify node sent its message. */
    FLOW_NOTICE("Flow notice", "A flow's notify node sends a message", Repeat.ONCE);

    /** How repeats of one problem are handled. */
    public enum Repeat {
        /** Starts or continues a problem: mailed when it starts, and again only if it gets worse. */
        OPENS,
        /** Ends a problem: mailed only if its start was, to those who heard of the start. */
        CLOSES,
        /** Stands alone: mailed every time (repeats of the very same event are dropped). */
        ONCE
    }

    private final String label;
    private final String description;
    private final Repeat repeat;

    NotificationKind(String label, String description, Repeat repeat) {
        this.label = label;
        this.description = description;
        this.repeat = repeat;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public Repeat repeat() {
        return repeat;
    }

    /** Reads a kind's id, such as {@code rule_failing}. */
    public static NotificationKind parse(String text) {
        if (text != null) {
            for (NotificationKind k : values()) {
                if (k.id().equalsIgnoreCase(text.trim())) {
                    return k;
                }
            }
        }
        throw new IllegalArgumentException("kinds: unknown event kind '" + text + "'; use "
            + String.join(", ", EnumSet.allOf(NotificationKind.class).stream().map(NotificationKind::id).toList()));
    }

    /**
     * Every kind but {@link #ISSUE_OPENED}: issues come from failing rules and drift, which these
     * already cover, so the default does not mail the same problem twice.
     */
    public static Set<NotificationKind> defaults() {
        return EnumSet.complementOf(EnumSet.of(ISSUE_OPENED));
    }
}

package io.kilnmark.server.notify;

import java.text.NumberFormat;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.jboss.logging.Logger;

import io.kilnmark.graph.Urn;
import io.kilnmark.server.quality.Drift;
import io.kilnmark.server.quality.Rule;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

/**
 * Where code says that something happened which people may want to hear about. Each method builds
 * a {@link NotificationEvent} and fires it as a CDI event; {@link Notifier} records it and tells
 * the people who asked, in the app and by email. Never throws and never sends anything itself, so
 * it can be called from any code path, including the request thread and the scan worker.
 *
 * <p>Titles and details here hold names, counts and shares only, never values from the data (see
 * {@link NotificationEvent}).
 *
 * <p><b>Pipelines:</b> the pipelines change calls {@link #pipelineRunFinished} once per finished
 * run, a single line where its service writes the run's final status.
 */
@ApplicationScoped
public class NotificationPublisher {

    private static final Logger LOG = Logger.getLogger(NotificationPublisher.class);

    @Inject
    Event<NotificationEvent> events;
    @Inject
    Event<NotificationCleared> cleared;

    /** Fires an event. Failures are logged, never thrown: the work that happened is already done. */
    public void publish(NotificationEvent e) {
        try {
            events.fire(e);
        } catch (RuntimeException ex) {
            LOG.warnf(ex, "Could not record the %s notification %s", e.kind().id(), e.dedupeKey());
        }
    }

    /** Ends a problem without telling anyone (a scan that works again), so its next start is told. */
    public void clear(String dedupeKey) {
        try {
            cleared.fire(new NotificationCleared(dedupeKey));
        } catch (RuntimeException ex) {
            LOG.warnf(ex, "Could not clear the notification state %s", dedupeKey);
        }
    }

    // ---- quality ----

    /** After a rule ran: tells of a rule that warns, fails or cannot run, and of one that recovers. */
    public void ruleEvaluated(Rule rule, Rule.Status status, Rule.Status previous, long failing, long total,
                              String error) {
        try {
            ruleEvaluatedNow(rule, status, previous, failing, total, error);
        } catch (RuntimeException ex) {
            LOG.warnf(ex, "Could not tell of an event (%s)", "ruleEvaluated");
        }
    }

    private void ruleEvaluatedNow(Rule rule, Rule.Status status, Rule.Status previous, long failing, long total,
                              String error) {
        String key = "rule:" + rule.id();
        String where = path(rule.assetUrn());
        String source = source(rule.assetUrn());
        String link = NotificationEvent.assetLink(rule.assetUrn());
        if (status == Rule.Status.PASS) {
            if (previous != null && previous != Rule.Status.PASS) {
                publish(new NotificationEvent(NotificationKind.RULE_RECOVERED, Severity.LOW, rule.assetUrn(),
                    rule.name() + " passes again on " + where, "The rule passes again"
                        + (rule.type().wholeAsset() ? "." : ": " + rows(failing, total) + "."),
                    link, source, null, null, key, null));
            }
            return;
        }
        Severity severity = switch (status) {
            case FAIL -> Severity.HIGH;
            case WARN -> Severity.MEDIUM;
            default -> Severity.LOW;
        };
        String verb = switch (status) {
            case FAIL -> "fails";
            case WARN -> "warns";
            default -> "could not run";
        };
        String detail;
        String restricted = null;
        if (status == Rule.Status.ERROR) {
            detail = "The rule could not run. Open the asset to see why.";
            // The reason can be a database's own words, which may quote the data. A custom SQL rule's can
            // quote any table the source's login reads, which no notification can mask, so it is never sent
            // (ADR 0027): the app shows it to those who may see it.
            restricted = error == null || rule.type() == io.kilnmark.server.quality.RuleType.CUSTOM_SQL ? null
                : "The rule could not run: " + error;
        } else if (rule.type().wholeAsset()) {
            detail = "The check on the whole " + (status == Rule.Status.FAIL ? "fails." : "warns.");
        } else {
            detail = rows(failing, total) + (failing == 1 ? " fails." : " fail.");
        }
        publish(new NotificationEvent(NotificationKind.RULE_FAILED, severity, rule.assetUrn(),
            rule.name() + " " + verb + " on " + where, detail, link, source, rule.field(), restricted, key, null));
    }

    /** A new issue in the issue list. {@code severity} is the issue's: low, medium or high. */
    public void issueOpened(long issueId, String urn, String title, String severity) {
        try {
            issueOpenedNow(issueId, urn, title, severity);
        } catch (RuntimeException ex) {
            LOG.warnf(ex, "Could not tell of an event (%s)", "issueOpened");
        }
    }

    private void issueOpenedNow(long issueId, String urn, String title, String severity) {
        publish(new NotificationEvent(NotificationKind.ISSUE_OPENED, Severity.parse(severity), urn,
            title + " (" + path(urn) + ")", "A new issue is open in the issue list for " + path(urn) + ".",
            NotificationEvent.issuesLink(), source(urn), null, null, "issue:" + issueId, null));
    }

    /**
     * Drift found by comparing a scan with the previous one: one event per asset and scan, at the
     * highest severity found. Drift messages hold words, counts and shares only.
     */
    public void driftFound(String urn, long versionId, List<Drift.Event> found) {
        try {
            driftFoundNow(urn, versionId, found);
        } catch (RuntimeException ex) {
            LOG.warnf(ex, "Could not tell of an event (%s)", "driftFound");
        }
    }

    private void driftFoundNow(String urn, long versionId, List<Drift.Event> found) {
        if (found.isEmpty()) {
            return;
        }
        Severity top = Severity.LOW;
        for (Drift.Event d : found) {
            top = Severity.max(top, Severity.parse(d.severity()));
        }
        List<Drift.Event> worst = found.stream()
            .sorted(Comparator.comparing((Drift.Event d) -> Severity.parse(d.severity())).reversed()).limit(5).toList();
        StringBuilder detail = new StringBuilder();
        for (Drift.Event d : worst) {
            detail.append("- ").append(d.message()).append('\n');
        }
        if (found.size() > worst.size()) {
            detail.append("- and ").append(found.size() - worst.size()).append(" more\n");
        }
        String what = found.size() == 1 ? "1 change" : found.size() + " changes";
        publish(new NotificationEvent(NotificationKind.DRIFT, top, urn, what + " since the last scan of " + path(urn),
            detail.toString().strip(), NotificationEvent.assetLink(urn), source(urn), null, null,
            "drift:" + versionId, null));
    }

    // ---- scans and sources ----

    /** After a scan: a failed scan is told once until a scan of the source succeeds again. */
    public void scanFinished(String source, long runId, String status, String runDetail) {
        try {
            scanFinishedNow(source, runId, status, runDetail);
        } catch (RuntimeException ex) {
            LOG.warnf(ex, "Could not tell of an event (%s)", "scanFinished");
        }
    }

    private void scanFinishedNow(String source, long runId, String status, String runDetail) {
        String key = "scan:" + source;
        if ("failed".equals(status)) {
            // The run's own words can name hosts and users and quote the data: for stewards and admins
            // only, and for admins alone when the source has columns marked sensitive (Exposure).
            publish(new NotificationEvent(NotificationKind.SCAN_FAILED, Severity.HIGH, null, "Scan of " + source
                + " failed", "The scan failed. Open the source to see why.", NotificationEvent.sourceLink(source),
                source, null, runDetail == null || runDetail.isBlank() ? null : "It said: " + runDetail, key, null));
        } else {
            clear(key);
            clear("connection:" + source); // the scan connected, so the connection works
        }
    }

    /**
     * After testing a saved source's connection: a failure is told once until a test or scan
     * succeeds. The reason is for roles that may test sources (it can name hosts and users).
     */
    public void connectionTested(String source, boolean ok, String problem) {
        try {
            connectionTestedNow(source, ok, problem);
        } catch (RuntimeException ex) {
            LOG.warnf(ex, "Could not tell of an event (%s)", "connectionTested");
        }
    }

    private void connectionTestedNow(String source, boolean ok, String problem) {
        String key = "connection:" + source;
        if (ok) {
            clear(key);
            return;
        }
        publish(new NotificationEvent(NotificationKind.CONNECTION_FAILED, Severity.MEDIUM, null,
            "Connection test of " + source + " failed", "Testing the connection to " + source + " failed. Open the "
                + "source to test it again.", NotificationEvent.sourceLink(source), source, null,
            problem == null ? null : "It said: " + problem, key, null));
    }

    // ---- pipelines ----

    /**
     * The hook for pipeline runs, for the pipelines change to call once per finished run (one line,
     * where the run's final status is written):
     *
     * <pre>{@code notifications.pipelineRunFinished(run.pipeline(), id, o.status(), score, threshold, link);}</pre>
     *
     * A failed run is told once until a run of the pipeline succeeds; a run that succeeded with a
     * quality score below its pipeline's threshold is told each time. Other statuses (cancelled)
     * tell nothing.
     *
     * @param pipeline  the pipeline's name
     * @param runId     the run
     * @param status    the run's final status: {@code succeeded}, {@code failed} or {@code cancelled}
     * @param score     the quality score of what the run wrote (0 to 1), or null if not scored
     * @param threshold the pipeline's quality threshold (0 to 1), or null if it has none
     * @param link      the run's in-app address ({@code #/...}), or null
     */
    public void pipelineRunFinished(String pipeline, long runId, String status, Double score, Double threshold,
                                    String link) {
        pipelineRunFinished(pipeline, runId, status, score, threshold, null, link);
    }

    /**
     * As {@link #pipelineRunFinished(String, long, String, Double, Double, String)}, naming the write
     * node whose output scored lowest ({@code output}, or null), so the notification says which.
     */
    public void pipelineRunFinished(String pipeline, long runId, String status, Double score, Double threshold,
                                    String output, String link) {
        try {
            pipelineRunFinishedNow(pipeline, runId, status, score, threshold, output, link);
        } catch (RuntimeException ex) {
            LOG.warnf(ex, "Could not tell of an event (%s)", "pipelineRunFinished");
        }
    }

    private void pipelineRunFinishedNow(String pipeline, long runId, String status, Double score, Double threshold,
                                        String output, String link) {
        String key = "pipeline:" + pipeline;
        if ("failed".equals(status)) {
            publish(new NotificationEvent(NotificationKind.PIPELINE_FAILED, Severity.HIGH, null, "Run " + runId + " of "
                + pipeline + " failed", "The pipeline run failed. Open the run to see which node stopped it.", link,
                null, null, null, key, null));
            return;
        }
        if (!"succeeded".equals(status)) {
            return;
        }
        clear(key);
        if (score != null && threshold != null && score < threshold) {
            publish(new NotificationEvent(NotificationKind.PIPELINE_LOW_QUALITY, Severity.MEDIUM, null,
                "Run " + runId + " of " + pipeline + " wrote data below its quality threshold", "Quality score "
                    + percent(score) + (output == null ? "" : " for what node " + output + " wrote")
                    + ", below the pipeline's threshold of " + percent(threshold) + ".", link, null,
                null, null, "pipeline-quality:" + runId, null));
        }
    }

    // ---- flows ----

    /**
     * A flow run ended. A failed run is told once until a run of the flow succeeds; other endings tell
     * nothing. {@code message} is the flow's own words (a stop node's message, which node failed), never
     * a pipeline's error, which can quote data.
     */
    public void flowRunFinished(String flow, long runId, String status, String message) {
        try {
            String key = "flow:" + flow;
            if ("failed".equals(status)) {
                publish(new NotificationEvent(NotificationKind.FLOW_FAILED, Severity.HIGH, null, "Run " + runId + " of flow "
                    + flow + " failed", message == null ? "The flow run failed." : message, null, null, null, null, key,
                    null));
            } else if ("succeeded".equals(status)) {
                clear(key);
            }
        } catch (RuntimeException ex) {
            LOG.warnf(ex, "Could not tell of an event (%s)", "flowRunFinished");
        }
    }

    /**
     * A flow's notify node: its title and message, as the flow wrote them (with the run's parameters and
     * the server's variables put in), at its severity ({@code info}, {@code low}, {@code medium} or
     * {@code high}). Each run's node is told once.
     */
    public void flowNotice(String flow, long runId, String node, String severity, String title, String message) {
        try {
            publish(new NotificationEvent(NotificationKind.FLOW_NOTICE, Severity.valueOf(severity.toUpperCase(Locale.ROOT)),
                null, title, message, null, null, null, null, "flow-notice:" + runId + ":" + node, null));
        } catch (RuntimeException ex) {
            LOG.warnf(ex, "Could not tell of an event (%s)", "flowNotice");
        }
    }

    // ---- wording ----

    static String rows(long failing, long total) {
        NumberFormat n = NumberFormat.getIntegerInstance(Locale.UK);
        return n.format(failing) + " of " + n.format(total) + (total == 1 ? " row" : " rows")
            + (total > 0 ? " (" + percent((double) failing / total) + ")" : "");
    }

    static String percent(double share) {
        NumberFormat p = NumberFormat.getPercentInstance(Locale.UK);
        p.setMaximumFractionDigits(share > 0 && share < 0.01 ? 2 : 1);
        return p.format(share);
    }

    private static String path(String urn) {
        try {
            return Urn.parse(urn).path();
        } catch (RuntimeException e) {
            return urn;
        }
    }

    private static String source(String urn) {
        try {
            return Urn.parse(urn).source();
        } catch (RuntimeException e) {
            return null;
        }
    }
}

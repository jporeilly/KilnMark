package io.kilnmark.server.quality;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.kilnmark.graph.Urn;
import io.kilnmark.graph.store.GraphStore;
import io.kilnmark.graph.store.Records;
import io.kilnmark.kiln.MetadataEvent;
import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.Connectors;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.plugins.jdbc.JdbcConnector;
import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.sources.HostPolicy;
import io.kilnmark.server.SecretBox;
import io.kilnmark.server.scan.ScanService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Data quality around scans: decides how each asset is sampled, stores profile versions and
 * samples as the scan reports them, then (as a job of kind {@value #JOB_KIND} on the queue)
 * works out measures, drift and scores, runs the rules and their actions.
 */
@ApplicationScoped
public class QualityService {

    private static final Logger LOG = Logger.getLogger(QualityService.class);

    /** Job kind for quality evaluations in the job queue. */
    public static final String JOB_KIND = "quality";

    /** The retired server-wide switch is still read so a server that sets it starts; say it does nothing. */
    @SuppressWarnings("deprecation")
    void retiredSettings(@jakarta.enterprise.event.Observes io.quarkus.runtime.StartupEvent event) {
        if (config.quality().customSql()) {
            LOG.warn("kilnmark.quality.custom-sql is retired and does nothing: custom SQL rules run on sources with "
                + "Allow SQL (allow_sql) turned on, by designers whose token sees unmasked values (ADR 0028). "
                + "Remove the setting.");
        }
    }
    /** Source setting: keep samples and example values (default true). */
    public static final String KEEP_SAMPLES = "keep_samples";
    /** Source setting: rows sampled per asset. */
    public static final String SAMPLE_ROWS = "sample_rows";
    static final int MAX_SAMPLE_ROWS = 10_000;
    static final int HISTORY = 10;

    private final GraphStore graph;
    private final QualityStore store;
    private final Connectors connectors;
    private final SecretBox secrets;
    private final KilnmarkConfig config;
    private final ObjectMapper json;
    /** One client for every webhook call. */
    private final Webhooks hooks;
    /** Notifications (docs/design/notifications.md); field injection keeps the constructor as other streams know it. */
    @jakarta.inject.Inject
    io.kilnmark.server.notify.NotificationPublisher notifications;

    public QualityService(GraphStore graph, QualityStore store, Connectors connectors, SecretBox secrets,
                          KilnmarkConfig config, ObjectMapper json) {
        this.graph = graph;
        this.store = store;
        this.connectors = connectors;
        this.secrets = secrets;
        this.config = config;
        this.json = json;
        this.hooks = new Webhooks(config.quality().webhookAllowList().orElse(List.of()), config.quality().webhookTimeout());
    }

    /**
     * The row count a scan should record for an asset: the database's estimate when the table was
     * read through a sample, otherwise the rows read. For {@code ScanService} when it stores a
     * {@code ROW_COUNT} event, so a sampled read never overwrites the catalogue's count with the
     * size of the sample.
     */
    public static long reportedRows(Map<String, String> rowCount) {
        long rows = Long.parseLong(rowCount.get("rows"));
        if (Boolean.parseBoolean(rowCount.get("sampled")) && rowCount.containsKey("estimated_rows")) {
            return Math.max(rows, Long.parseLong(rowCount.get("estimated_rows")));
        }
        return rows;
    }

    /**
     * Whether values may be shown for an asset now: its source keeps samples. When the source's
     * {@code keep_samples} has been turned off since the last scan, the stored sample and failing
     * rows are removed at once. (When stream A's source update lands, it should call
     * {@link #settingsChanged} as well.)
     */
    public boolean samplesAllowed(String urn) {
        Optional<QualityStore.SourceRef> s = store.sourceOfAsset(urn);
        if (s.isEmpty()) {
            return false;
        }
        Optional<Records.Source> source = graph.source(s.get().name());
        if (source.isPresent() && !keepSamples(source.get().settings())) {
            store.purgeSamples(s.get().id(), true);
            return false;
        }
        return true;
    }

    /** To call after a source's settings change: purges stored values at once if keep_samples went off. */
    public void settingsChanged(Records.Source source) {
        if (!keepSamples(source.settings())) {
            store.purgeSamples(source.id(), true);
        }
    }

    // ---- during a scan ----

    /** Whether a source keeps samples and example values: its {@code keep_samples} setting, on by default. */
    public static boolean keepSamples(Map<String, String> settings) {
        String v = settings == null ? null : settings.get(KEEP_SAMPLES);
        if (v == null || v.isBlank()) {
            return true;
        }
        return !Set.of("false", "off", "no", "0").contains(v.trim().toLowerCase(Locale.ROOT));
    }

    int sampleRows(Map<String, String> settings) {
        String v = settings == null ? null : settings.get(SAMPLE_ROWS);
        try {
            return v == null || v.isBlank() ? config.quality().sampleRows()
                : Math.clamp(Integer.parseInt(v.trim()), 0, MAX_SAMPLE_ROWS);
        } catch (NumberFormatException e) {
            return config.quality().sampleRows();
        }
    }

    /**
     * Settings for the scan's profile node: whether values are kept, and how rows are sampled:
     * by key hash for tables with a primary key, by whole-row hash for other tables, and by a
     * reservoir seeded from the asset's URN for files.
     */
    public Map<String, String> profileSettings(Records.Source source, Connector connector, AssetInfo a, String urn) {
        Map<String, String> s = new HashMap<>();
        boolean keep = keepSamples(source.settings());
        s.put("keep-values", Boolean.toString(keep));
        s.put("examples", Integer.toString(config.quality().examples()));
        if (!keep) {
            return s;
        }
        s.put("sample-size", Integer.toString(sampleRows(source.settings())));
        s.put("sample-max-bytes", Long.toString((long) Math.max(1, config.quality().sampleMaxMb()) << 20));
        if (connector instanceof JdbcConnector) {
            s.put("sample-mode", a.primaryKey().isEmpty() ? "row" : "key");
            s.put("sample-key", String.join(",", a.primaryKey()));
        } else {
            s.put("sample-mode", "reservoir");
            s.put("sample-seed", Long.toString(seed(urn)));
        }
        return s;
    }

    /** A stable seed per asset: the same asset always gets the same reservoir for the same input. */
    static long seed(String urn) {
        return UUID.nameUUIDFromBytes(urn.getBytes(StandardCharsets.UTF_8)).getMostSignificantBits();
    }

    /**
     * Stores what quality needs from a scan's events: a profile version per asset (from its row
     * count) and its sample. Never throws: a failure here must not fail the scan.
     */
    public void observe(MetadataEvent e, long runId, Records.Source source) {
        try {
            switch (e.kind()) {
                case ROW_COUNT -> {
                    Map<String, String> a = e.attributes();
                    boolean sampled = Boolean.parseBoolean(a.get("sampled"));
                    store.saveVersion(e.assetUrn(), runId, e.observedAt(), Long.parseLong(a.get("rows")),
                        Boolean.parseBoolean(a.get("complete")), sampled,
                        a.containsKey("sample_percent") ? Double.valueOf(a.get("sample_percent")) : null,
                        a.containsKey("estimated_rows") ? Long.valueOf(a.get("estimated_rows")) : null);
                }
                case SAMPLE -> {
                    if (!keepSamples(source.settings())) {
                        return; // the profile node was told not to keep values; never store any
                    }
                    Map<String, String> a = e.attributes();
                    String method = a.getOrDefault("method", "none");
                    List<String> key = a.getOrDefault("key", "").isBlank() ? List.of()
                        : List.of(a.get("key").split(","));
                    store.saveSample(e.assetUrn(), runId, method, key, a.getOrDefault("columns", "[]"),
                        a.getOrDefault("rows", "[]"), a.containsKey("seen") ? Long.valueOf(a.get("seen")) : null,
                        a.getOrDefault("examples", "{}"), e.observedAt());
                }
                default -> { }
            }
        } catch (RuntimeException ex) {
            LOG.warnf(ex, "Could not store quality data for %s (run %d)", e.assetUrn(), runId);
        }
    }

    /** After a scan: drops samples no longer allowed, and queues the quality evaluation. Never throws. */
    public void afterScan(Records.Source source, long runId) {
        try {
            store.purgeSamples(source.id(), !keepSamples(source.settings()));
            if (!store.versionsOfRun(runId).isEmpty()) {
                store.queueRun(source.id(), null, null, runId, "scan", "scan", config.worker().maxAttempts(), 2);
            }
        } catch (RuntimeException ex) {
            LOG.warnf(ex, "Could not queue the quality evaluation after scan %d of %s", runId, source.name());
        }
    }

    // ---- on demand ----

    /** Queues an evaluation of an asset's rules, or of one rule. */
    public long queueManual(String urn, Long ruleId, String by) {
        QualityStore.SourceRef s = store.sourceOfAsset(urn).orElseThrow(
            () -> new IllegalArgumentException("No asset " + urn));
        return store.queueRun(s.id(), urn, ruleId, null, "manual", by, config.worker().maxAttempts(), 0);
    }

    // ---- the job ----

    /**
     * Marks failed the evaluations whose job ran out of attempts (a worker died running them each
     * time); called by workers' recovery, on start and every 30 seconds, right after it fails such
     * jobs. Never throws.
     */
    public void failOrphanedRuns() {
        try {
            int n = store.failOrphanedRuns();
            if (n > 0) {
                LOG.infof("%d quality runs were left by a stopped worker and are now marked failed", n);
            }
        } catch (RuntimeException e) {
            LOG.debugf(e, "Could not check for quality runs left by a stopped worker");
        }
    }

    /** Runs the quality evaluation behind job {@code jobId}; called by a worker. */
    public ScanService.Result execute(long jobId, BooleanSupplier cancelled) {
        Optional<Long> runId = store.runForJob(jobId);
        if (runId.isEmpty()) {
            return new ScanService.Result(ScanService.Outcome.SKIPPED, "Job " + jobId + " names no quality run");
        }
        Optional<QualityStore.Run> run = store.run(runId.get());
        if (run.isEmpty()) {
            return new ScanService.Result(ScanService.Outcome.SKIPPED, "Quality run " + runId.get() + " no longer exists");
        }
        QualityStore.Run r = run.get();
        if (r.status().equals("succeeded") || r.status().equals("failed")) {
            return new ScanService.Result(ScanService.Outcome.SKIPPED, "Quality run " + r.id() + " had already finished");
        }
        store.startRun(r.id());
        try {
            String detail = r.trigger().equals("scan") ? afterScanJob(r, cancelled) : manualJob(r, cancelled);
            if (detail == null) {
                return new ScanService.Result(ScanService.Outcome.CANCELLED, null);
            }
            store.finishRun(r.id(), "succeeded", detail);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ScanService.Result(ScanService.Outcome.CANCELLED, null);
        } catch (HostRefused e) {
            store.finishRun(r.id(), "failed", e.getMessage());
        } catch (RuntimeException e) {
            LOG.errorf(e, "Quality run %d failed", r.id());
            // The exception may carry a database's own text: it goes to the log, not to every role.
            store.finishRun(r.id(), "failed", "The quality evaluation failed. The server log has the details; run the "
                + "rules again once the cause is fixed.");
        }
        return new ScanService.Result(ScanService.Outcome.FINISHED, null);
    }

    /** Every asset the scan profiled: measures, drift, rules, scores. Null when cancelled. */
    private String afterScanJob(QualityStore.Run r, BooleanSupplier cancelled) throws InterruptedException {
        List<QualityStore.Version> versions = r.scanRunId() == null ? List.of() : store.versionsOfRun(r.scanRunId());
        int rules = 0;
        int failing = 0;
        int drift = 0;
        String refused = null;
        for (QualityStore.Version v : versions) {
            if (cancelled.getAsBoolean()) {
                return null;
            }
            drift += analyse(v.id());
            try {
                Outcome o = evaluate(v.assetUrn(), store.rules(v.assetUrn(), null, true), r.id());
                rules += o.ran();
                failing += o.failing();
            } catch (HostRefused e) {
                // Measures and drift need no connection: they go on for every asset; the rules wait for the host.
                refused = e.getMessage();
            }
            rescore(v.assetUrn());
            store.pruneVersions(v.assetUrn(), config.quality().keepVersions());
        }
        return versions.size() + (versions.size() == 1 ? " asset" : " assets") + " analysed, " + rules
            + (rules == 1 ? " rule" : " rules") + " run (" + failing + " failing or warning), " + drift
            + " drift " + (drift == 1 ? "event" : "events") + (refused == null ? "" : ". Rules not run: " + refused);
    }

    private String manualJob(QualityStore.Run r, BooleanSupplier cancelled) throws InterruptedException {
        List<Rule> rules;
        if (r.ruleId() != null) {
            rules = store.rule(r.ruleId()).map(List::of).orElse(List.of());
        } else {
            rules = store.rules(r.assetUrn(), null, true);
        }
        if (cancelled.getAsBoolean()) {
            return null;
        }
        Outcome o = evaluate(r.assetUrn(), rules, r.id());
        rescore(r.assetUrn());
        return o.ran() + (o.ran() == 1 ? " rule" : " rules") + " run (" + o.failing() + " failing or warning)";
    }

    /** Measures and drift for a profile version; returns the number of drift events. */
    public int analyse(long versionId) {
        ProfileFacts now = store.facts(versionId).orElseThrow();
        List<ProfileFacts> history = history(now);
        List<Measures.Measure> measures = Measures.of(now, history, List.of(), config.quality().defaultCadence());
        store.saveMeasures(versionId, measures);
        int events = 0;
        // Freshness and volume are judged against what the asset's history has taught, once it has.
        ProfileFacts.FieldFacts recency = Measures.recencyField(now);
        java.time.Duration cadence = recency == null ? null : Measures.learnedCadence(recency.name(), now, history);
        Double band = Measures.learnedBand(history);
        if (!history.isEmpty()) {
            ProfileFacts previous = history.get(0);
            List<Drift.Event> found = Drift.compare(now, previous, cadence, band);
            List<Long> ids = store.saveDrift(now.assetUrn(), versionId, previous.versionId(), "previous", found);
            events += found.size();
            if (notifications != null) {
                notifications.driftFound(now.assetUrn(), versionId, found);
            }
            for (int i = 0; i < found.size(); i++) {
                Drift.Event e = found.get(i);
                if (e.change().equals("column_renamed")) {
                    store.carrySensitive(now.assetUrn(), e.evidence().path("from").asText(), e.field());
                }
                if (!e.severity().equals("low")) {
                    String title = "Drift: " + e.message();
                    store.openDriftIssue(now.assetUrn(), e.field(), e.change(), ids.get(i), title,
                        "Found when comparing with the previous scan (" + previous.observedAt() + ").", e.severity())
                        .ifPresent(issue -> issueOpened(issue, now.assetUrn(), title, e.severity()));
                }
            }
        }
        Optional<QualityStore.Version> baseline = store.baseline(now.assetUrn());
        if (baseline.isPresent() && baseline.get().id() != versionId
            && (history.isEmpty() || history.get(0).versionId() != baseline.get().id())) {
            ProfileFacts base = store.facts(baseline.get().id()).orElseThrow();
            List<Drift.Event> found = Drift.compare(now, base, cadence, band);
            store.saveDrift(now.assetUrn(), versionId, base.versionId(), "baseline", found);
            events += found.size();
        }
        store.markAnalysed(versionId);
        return events;
    }

    private List<ProfileFacts> history(ProfileFacts now) {
        List<ProfileFacts> out = new ArrayList<>();
        for (long id : store.earlierVersions(now.assetUrn(), now.versionId(), HISTORY)) {
            store.facts(id).ifPresent(out::add);
        }
        return out;
    }

    /** How many rules ran, and how many did not pass. */
    record Outcome(int ran, int failing) {
    }

    /** The source names a host the administrator no longer allows; the message is for every role. */
    static final class HostRefused extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        HostRefused(String message) {
            super(message, null, false, false);
        }
    }

    /** Runs rules on an asset, stores their results and carries out their actions. */
    Outcome evaluate(String urn, List<Rule> rules, Long qualityRunId) throws InterruptedException {
        if (rules.isEmpty()) {
            return new Outcome(0, 0);
        }
        Records.AssetDetail asset = graph.asset(urn).orElseThrow(() -> new IllegalStateException("No asset " + urn));
        Records.Source source = graph.source(asset.source()).orElseThrow();
        Connector connector = connectors.get(source.kind()).orElseThrow(() -> new IllegalStateException(
            "This server has no connector for sources of kind " + source.kind()));
        String secret = graph.sourceSecret(source.name()).map(secrets::open).orElse(null);
        SourceConfig cfg = new SourceConfig(source.name(), source.kind(), source.settings(), secret);
        String refused = HostPolicy.of(config).refusal(connector, cfg);
        if (refused != null) {
            throw new HostRefused(refused);
        }
        boolean keep = keepSamples(source.settings());
        Map<String, String> kinds = new LinkedHashMap<>();
        asset.fields().forEach(f -> kinds.put(f.name(), f.kind()));
        Long previousRows = previousRows(urn);
        RuleRunner.Target target = new RuleRunner.Target(connector, cfg, assetInfo(asset), kinds, previousRows,
            other -> graph.asset(other).map(o -> new SqlRules.Table(o.container(), o.name())).orElse(null));
        RuleRunner runner = new RuleRunner(config.quality().queryTimeout(), keep ? config.quality().failingRows() : 0,
            config.scan().memoryLimitMb() << 20, io.kilnmark.server.sources.CommonSettings.allowsSql(source.settings()));
        Map<Long, QualityStore.Result> before = store.latestResults(urn);
        List<RowChecks.Evaluation> evaluations = runner.run(rules, target, Instant.now());
        int failing = 0;
        for (int i = 0; i < rules.size(); i++) {
            Rule rule = rules.get(i);
            RowChecks.Evaluation e = evaluations.get(i);
            Rule.Status status = e.error() != null ? Rule.Status.ERROR : rule.status(e.failing(), e.total());
            long resultId = store.saveResult(rule, qualityRunId, status, e, keep);
            if (status != Rule.Status.PASS) {
                failing++;
            }
            QualityStore.Result previous = before.get(rule.id());
            act(rule, status, e, previous == null ? null : previous.status(), resultId, hooks);
        }
        return new Outcome(rules.size(), failing);
    }

    /** An asset's enabled rules, for a pipeline's quality gate. */
    public List<Rule> enabledRules(String urn) {
        return store.rules(urn, null, true);
    }

    /** The row count of an asset's latest profile version (its last scan or write), or null. */
    public Long latestRowCount(String urn) {
        return store.latestVersion(urn).map(QualityStore.Version::rowCount).orElse(null);
    }

    private Long previousRows(String urn) {
        List<QualityStore.Version> v = store.versions(urn, 2);
        // The latest version is this scan's (or the last scan's, on demand): compare with the one before it.
        return v.size() < 2 ? null : v.get(1).rowCount();
    }

    /** The asset as a connector's reader needs it, rebuilt from the catalogue. */
    static AssetInfo assetInfo(Records.AssetDetail a) {
        List<AssetInfo.FieldInfo> fields = a.fields().stream().map(f -> new AssetInfo.FieldInfo(f.name(), f.position(),
            f.sourceType(), f.kind(), f.nullable(), f.description())).toList();
        return new AssetInfo(Urn.parse(a.urn()).path(), a.kind(), a.container(), a.name(), a.description(), fields,
            a.primaryKey(), false, true, Map.of("schema", a.container(), "table", a.name()));
    }

    /** A rule's actions: steward tasks open and close with its status; webhooks hear of problems and recoveries. */
    private void act(Rule rule, Rule.Status status, RowChecks.Evaluation e, Rule.Status previous, long resultId,
                     Webhooks hooks) {
        if (rule.actions().task()) {
            if (status == Rule.Status.PASS) {
                store.resolveRuleIssue(rule.id());
            } else {
                String severity = status == Rule.Status.FAIL ? "high" : status == Rule.Status.WARN ? "medium" : "low";
                // Issues are read by every role: a freshness message states the age of a value, not said of a
                // column marked sensitive.
                boolean quiet = rule.type() == RuleType.FRESHNESS && store.sensitive(rule.assetUrn()).contains(rule.field());
                String detail = status == Rule.Status.ERROR ? "The rule could not run: " + e.error()
                    : Measures.count(e.failing()) + " of " + Measures.count(e.total()) + " rows fail"
                        + (e.message() == null || quiet ? "" : " (" + e.message() + ")");
                String title = "Rule '" + rule.name() + "' " + status.id() + "s";
                store.openRuleIssue(rule, title, detail, severity)
                    .ifPresent(issue -> issueOpened(issue, rule.assetUrn(), title, severity));
            }
        }
        if (notifications != null) {
            notifications.ruleEvaluated(rule, status, previous, e.failing(), e.total(), e.error());
        }
        boolean notable = status != Rule.Status.PASS || (previous != null && previous != Rule.Status.PASS);
        if (rule.actions().webhook() != null && notable) {
            ObjectNode body = json.createObjectNode();
            body.put("event", "quality.result");
            body.put("resultId", resultId);
            body.put("ruleId", rule.id());
            body.put("rule", rule.name());
            body.put("type", rule.type().id());
            body.put("dimension", rule.dimension().id());
            body.put("asset", rule.assetUrn());
            body.put("field", rule.field());
            body.put("status", status.id());
            body.put("previousStatus", previous == null ? null : previous.id());
            if (e.error() == null) {
                body.put("failing", e.failing());
                body.put("total", e.total());
            } else {
                body.put("error", e.error());
            }
            body.put("observedAt", Instant.now().toString());
            if (Webhooks.NOT_ALLOWED.equals(hooks.send(rule.actions().webhook(), body.toString()))) {
                store.noteResult(resultId, "The webhook was not called: its address is no longer on this server's "
                    + "webhook allow-list (kilnmark.quality.webhook-allow-list).");
            }
        }
    }

    private void issueOpened(long issue, String urn, String title, String severity) {
        if (notifications != null) {
            notifications.issueOpened(issue, urn, title, severity);
        }
    }

    /** Recomputes an asset's scores from its latest measures, drift and rule results. */
    public void rescore(String urn) {
        Optional<QualityStore.Version> latest = store.latestVersion(urn);
        List<Measures.Measure> measures = latest.map(v -> store.measureObjects(v.id())).orElse(List.of());
        List<Drift.Event> drift = latest.map(v -> store.driftOf(v.id())).orElse(List.of());
        Map<Long, Rule> rules = new HashMap<>();
        store.rules(urn, null, false).forEach(r -> rules.put(r.id(), r));
        List<Scores.RuleResult> results = new ArrayList<>();
        for (QualityStore.Result res : store.latestResults(urn).values()) {
            Rule rule = rules.get(res.ruleId());
            if (rule == null || !rule.enabled()) {
                continue;
            }
            results.add(new Scores.RuleResult(rule.field(), rule.dimension(), rule.name(), res.status(),
                res.failing() == null ? 0 : res.failing(), res.total() == null ? 0 : res.total(), rule.type().wholeAsset()));
        }
        store.saveScores(urn, Scores.of(measures, results, drift));
    }

    // ---- previews, patterns and recommendations ----

    /** A rule evaluated against the asset's stored sample. */
    public record Preview(long checked, long failing, double share, Rule.Status status, String message,
                          RowChecks.FailingRows failingRows) {
    }

    /** Evaluates a checked rule against the sample; empty when no sample is kept. */
    public Optional<Preview> preview(Rule rule) {
        Optional<QualityStore.Sample> sample = store.sample(rule.assetUrn());
        if (sample.isEmpty() || sample.get().rows() == null || sample.get().rows().isEmpty()) {
            return Optional.empty();
        }
        List<String> columns = new ArrayList<>();
        sample.get().columns().forEach(c -> columns.add(c.asText()));
        Map<String, String> kinds = new LinkedHashMap<>();
        graph.asset(rule.assetUrn()).ifPresent(a -> a.fields().forEach(f -> kinds.put(f.name(), f.kind())));
        RowChecks.Check check = RowChecks.of(rule, columns, kinds, null, Instant.now(), 5, "sample");
        for (JsonNode row : sample.get().rows()) {
            check.row(c -> row.get(c) == null || row.get(c).isNull() ? null : row.get(c).asText());
        }
        RowChecks.Evaluation e = check.finish();
        if (e.error() != null) {
            return Optional.of(new Preview(0, 0, 0, Rule.Status.ERROR, e.error(), null));
        }
        double share = e.total() == 0 ? 0 : (double) e.failing() / e.total();
        String message = rule.type().wholeAsset() ? "Checks on the whole asset cannot be previewed on a sample; run "
            + "the rule instead" : "Would fail " + Measures.percent(share) + " of sampled rows (" + Measures.count(e.failing())
            + " of " + Measures.count(e.total()) + ")";
        return Optional.of(new Preview(e.total(), e.failing(), Measures.round(share), rule.status(e.failing(), e.total()),
            message, e.failingRows()));
    }

    /** The facts of an asset's latest version. */
    public Optional<ProfileFacts> latestFacts(String urn) {
        return store.latestVersion(urn).flatMap(v -> store.facts(v.id()));
    }

    public List<ProfileFacts> historyOf(ProfileFacts now) {
        return history(now);
    }

    /**
     * Accepts a value shape for a field: it counts as valid from now on, and a matches_pattern rule
     * (validity) is created from its expression, or from {@code regex} when the steward edited it.
     */
    public QualityStore.Accepted accept(String urn, String field, String shape, String regex, String by,
                                        RuleDraft.Context context) {
        if (shape == null || shape.isBlank() || shape.length() > 200) {
            throw new IllegalArgumentException("shape: the value shape to accept, as the profile shows it");
        }
        boolean collapsed = latestFacts(urn).map(f -> f.field(field)).map(f -> "true".equals(f.text("patterns_collapsed")))
            .orElse(false);
        String expression = regex == null || regex.isBlank() ? Patterns.regex(shape, collapsed) : regex.trim();
        List<QualityStore.Accepted> already = store.accepted(urn).stream().filter(a -> a.field().equals(field)).toList();
        if (already.stream().anyMatch(a -> a.shape().equals(shape))) {
            throw new IllegalStateException("That pattern is already accepted for " + field);
        }
        List<String> all = new ArrayList<>();
        already.forEach(a -> all.add(a.regex()));
        all.add(expression);
        Rule rule = patternRule(urn, field, all, by, context);
        // One validity rule per field covers all of its accepted shapes.
        Long ruleId = already.stream().map(QualityStore.Accepted::ruleId).filter(java.util.Objects::nonNull).findFirst()
            .orElse(null);
        QualityStore.Accepted accepted;
        if (ruleId != null && store.updateRule(ruleId, rule).isPresent()) {
            accepted = store.acceptWith(urn, field, shape, expression, ruleId, by);
        } else {
            accepted = store.accept(urn, field, shape, expression, rule, by);
        }
        rescoreQuietly(urn);
        return accepted;
    }

    /** Withdraws an accepted shape; the field's validity rule then covers the shapes still accepted, if any. */
    public boolean unaccept(String urn, long id, String by, RuleDraft.Context context) {
        Optional<QualityStore.Accepted> gone = store.accepted(urn).stream().filter(a -> a.id() == id).findFirst();
        if (gone.isEmpty() || !store.deleteAccepted(urn, id)) {
            return false;
        }
        QualityStore.Accepted a = gone.get();
        List<String> remaining = store.accepted(urn).stream().filter(x -> x.field().equals(a.field()))
            .map(QualityStore.Accepted::regex).toList();
        if (a.ruleId() != null) {
            if (remaining.isEmpty()) {
                store.deleteRule(a.ruleId());
            } else {
                store.updateRule(a.ruleId(), patternRule(urn, a.field(), remaining, by, context));
            }
        }
        rescoreQuietly(urn);
        return true;
    }

    private Rule patternRule(String urn, String field, List<String> regexes, String by, RuleDraft.Context context) {
        String regex = regexes.size() == 1 ? regexes.get(0)
            : "^(" + String.join("|", regexes.stream().map(QualityService::stripAnchors).toList()) + ")$";
        ObjectNode params = json.createObjectNode().put("regex", regex);
        return new RuleDraft(urn, field, field + " matches its accepted patterns", "matches_pattern", "validity", params,
            "share", null, 0.0, null, true).check(context, by);
    }

    private static String stripAnchors(String regex) {
        String r = regex;
        if (r.startsWith("^")) {
            r = r.substring(1);
        }
        if (r.endsWith("$") && !r.endsWith("\\$")) {
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }

    /** Validity counts accepted shapes from the next scan; the measures of the current version are redone now. */
    void rescoreQuietly(String urn) {
        try {
            store.latestVersion(urn).ifPresent(v -> analyseMeasuresOnly(v.id()));
            rescore(urn);
        } catch (RuntimeException e) {
            LOG.warnf(e, "Could not refresh the scores of %s", urn);
        }
    }

    private void analyseMeasuresOnly(long versionId) {
        ProfileFacts now = store.facts(versionId).orElseThrow();
        store.saveMeasures(versionId, Measures.of(now, history(now), List.of(), config.quality().defaultCadence()));
    }

    public KilnmarkConfig.Quality settings() {
        return config.quality();
    }
}

package io.kilnmark.server.pipelines;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.kilnmark.graph.store.GraphStore;
import io.kilnmark.graph.store.Records;
import io.kilnmark.kiln.MetadataEvent;
import io.kilnmark.kiln.SettingSpec;
import io.kilnmark.kiln.StepException;
import io.kilnmark.kiln.StepFactories;
import io.kilnmark.kiln.StepFactory;
import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.graph.GraphRunner;
import io.kilnmark.kiln.graph.PipelineGraph;
import io.kilnmark.kiln.plugins.write.WriteReport;
import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.api.ApiError;
import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.jobs.JobQueue;
import io.kilnmark.server.quality.Sights;
import io.kilnmark.server.quality.QualityService;
import io.kilnmark.server.scan.ScanService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Pipelines (docs/design/pipelines.md sections 5 and 6): saving them with every version, checking
 * their YAML, queueing runs on the job queue and, on a worker, running one exact version and
 * recording each step's part, the assets it wrote and their lineage.
 */
@ApplicationScoped
public class PipelineService {

    private static final Logger LOG = Logger.getLogger(PipelineService.class);

    /** Pipeline names: a small letter, then small letters, digits and dashes; at most 63 characters. */
    public static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    static final int MAX_MESSAGE = 500;
    static final int MAX_DISPLAY_NAME = 200;
    static final int MAX_DESCRIPTION = 2000;

    private final PipelineStore store;
    private final PipelineCompilerPort compiler;
    private final StepFactories steps;
    private final GraphRunner runner;
    private final GraphStore graph;
    private final PipelineSources sources;
    private final KilnmarkConfig config;
    private final ObjectMapper json;
    private final PipelineExecution execution;

    /** The scan's path for storing row counts and profiles, reused for what pipelines write. */
    @Inject
    ScanService scans;

    /** Quality: profile versions of written assets, and their evaluation afterwards. */
    @Inject
    QualityService quality;


    @Inject
    public PipelineService(PipelineStore store, PipelineCompilerPort compiler, StepFactories steps, GraphRunner runner,
                           GraphStore graph, PipelineSources sources, KilnmarkConfig config, ObjectMapper json) {
        this.store = store;
        this.compiler = compiler;
        this.steps = steps;
        this.runner = runner;
        this.graph = graph;
        this.sources = sources;
        this.config = config;
        this.json = json;
        this.execution = new PipelineExecution(json);
    }

    // ---- names and checks ----

    /** Why {@code name} cannot name a pipeline, or null when it can. */
    public static String nameProblem(String name) {
        if (name == null || name.isBlank()) {
            return "name: give the pipeline a name, such as customer-billing";
        }
        if (!NAME.matcher(name).matches()) {
            return "name: '" + name + "' cannot name a pipeline. Start with a small letter, then use small letters, "
                + "digits and dashes, at most 63 characters, such as customer-billing";
        }
        return null;
    }

    static WebApplicationException fail(Response.Status status, String message) {
        return new WebApplicationException(Response.status(status).type(MediaType.APPLICATION_JSON)
            .entity(new ApiError(message)).build());
    }

    private void checkSize(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            throw new IllegalArgumentException("yaml: send the pipeline's YAML");
        }
        int max = Math.max(1, config.pipelines().maxYamlKb());
        int size = yaml.getBytes(StandardCharsets.UTF_8).length;
        if (size > max * 1024) {
            throw new IllegalArgumentException("yaml: the pipeline is " + ((size + 1023) / 1024) + " KB; this server "
                + "accepts at most " + max + " KB (kilnmark.pipelines.max-yaml-kb)");
        }
    }

    private static String clip(String text, int max, String field) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.strip();
        if (t.length() > max) {
            throw new IllegalArgumentException(field + ": at most " + max + " characters; this has " + t.length());
        }
        if (t.chars().anyMatch(c -> c < 0x20 && c != '\n' && c != '\t')) {
            throw new IllegalArgumentException(field + ": no control characters");
        }
        return t;
    }

    // ---- validation ----

    /**
     * Checks YAML without running anything: problems with lines, each step's ports and output
     * columns. {@code expectedName}, when given, is the pipeline's name, which the YAML's
     * {@code name} must match.
     */
    public PipelineViews.Validation validate(String yaml, String expectedName) {
        checkSize(yaml);
        PipelineCompilerPort.Compilation c = compiler.check(yaml, steps);
        List<PipelineCompilerPort.Problem> problems = new ArrayList<>(c.problems());
        if (expectedName != null && c.name() != null && !c.name().equals(expectedName)) {
            problems.add(0, new PipelineCompilerPort.Problem(lineOf(yaml, "name"), 1, null, "name: this pipeline is "
                + "called " + expectedName + "; a pipeline's name cannot change. Write name: " + expectedName));
        }
        return new PipelineViews.Validation(problems.isEmpty(), c.name(), c.description(), problems, c.notes(),
            c.steps(), c.edges());
    }

    /** The line of a top-level key, or 0. */
    static int lineOf(String yaml, String key) {
        Matcher m = Pattern.compile("(?m)^" + Pattern.quote(key) + "\\s*:").matcher(yaml);
        if (!m.find()) {
            return 0;
        }
        int line = 1;
        for (int i = 0; i < m.start(); i++) {
            if (yaml.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private String problemsJson(List<PipelineCompilerPort.Problem> problems) {
        try {
            return json.writeValueAsString(problems);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private List<PipelineCompilerPort.Problem> problems(String text) {
        try {
            return text == null ? List.of() : json.readValue(text, new TypeReference<>() { });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    // ---- pipelines and versions ----

    public List<PipelineViews.PipelineSummary> list(Sights sights) {
        List<PipelineStore.Summary> all = store.pipelines();
        prepare(all.stream().map(PipelineStore.Summary::lastRun).toList(), sights);
        return all.stream().map(s -> new PipelineViews.PipelineSummary(s.pipeline().name(),
            s.pipeline().displayName(), s.pipeline().description(), s.pipeline().latestVersion(), s.valid(),
            s.pipeline().updatedBy(), s.pipeline().updatedAt(), summary(s.lastRun(), sights))).toList();
    }

    private PipelineStore.Pipeline find(String name) {
        return store.pipeline(name).orElseThrow(() -> fail(Response.Status.NOT_FOUND, "No pipeline called " + name));
    }

    public PipelineViews.PipelineDetail get(String name, Sights sights) {
        PipelineStore.Pipeline p = find(name);
        PipelineStore.Version v = store.version(name, p.latestVersion()).orElse(null);
        PipelineStore.Run last = store.runs(name, 1).stream().findFirst().orElse(null);
        return detail(p, v, last, sights);
    }

    private PipelineViews.PipelineDetail detail(PipelineStore.Pipeline p, PipelineStore.Version v,
                                                PipelineStore.Run last, Sights sights) {
        return new PipelineViews.PipelineDetail(p.name(), p.displayName(), p.description(), p.latestVersion(),
            p.createdBy(), p.createdAt(), p.updatedBy(), p.updatedAt(), v == null ? null : version(v), summary(last, sights));
    }

    private PipelineViews.VersionDetail version(PipelineStore.Version v) {
        return new PipelineViews.VersionDetail(v.n(), v.yaml(), v.message(), v.author(), v.createdAt(), v.valid(),
            problems(v.problems()));
    }

    /** Adds a pipeline with its first version; an invalid one is saved as a draft. */
    public PipelineViews.PipelineDetail create(PipelineViews.NewPipeline body, String by) {
        if (body == null) {
            throw new IllegalArgumentException("Send the pipeline as JSON with a name and its YAML");
        }
        String problem = nameProblem(body.name());
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        PipelineViews.Validation v = validate(body.yaml(), body.name());
        String display = clip(body.displayName(), MAX_DISPLAY_NAME, "displayName");
        String description = clip(body.description() != null ? body.description() : v.description(), MAX_DESCRIPTION,
            "description");
        String message = clip(body.message() == null ? "First version" : body.message(), MAX_MESSAGE, "message");
        try {
            PipelineStore.Pipeline p = store.create(body.name(), display == null ? body.name() : display, description,
                by, body.yaml(), message, v.valid(), problemsJson(v.problems()));
            LOG.infof("Pipeline %s added by %s", p.name(), by);
            return detail(p, store.version(p.name(), 1).orElse(null), null, null);
        } catch (PipelineStore.Exists e) {
            throw fail(Response.Status.CONFLICT, "A pipeline called " + body.name() + " already exists");
        }
    }

    /**
     * Saves a new version. {@code expected}, from If-Match, is the latest version the caller read;
     * when someone has saved another since, the change is refused (409).
     */
    public PipelineViews.PipelineDetail save(String name, Integer expected, PipelineViews.PipelineChange body,
                                             String by, Sights sights) {
        PipelineStore.Pipeline old = find(name);
        if (body == null) {
            throw new IllegalArgumentException("Send the new version as JSON with its YAML and a message");
        }
        PipelineViews.Validation v = validate(body.yaml(), name);
        String display = clip(body.displayName(), MAX_DISPLAY_NAME, "displayName");
        String description = body.description() != null ? clip(body.description(), MAX_DESCRIPTION, "description")
            : v.description() != null ? clip(v.description(), MAX_DESCRIPTION, "description") : old.description();
        String message = clip(body.message(), MAX_MESSAGE, "message");
        try {
            PipelineStore.Pipeline p = store.saveVersion(name, expected, body.yaml(), message, by, v.valid(),
                problemsJson(v.problems()), display, description).orElseThrow(() -> fail(Response.Status.NOT_FOUND,
                    "No pipeline called " + name));
            return detail(p, store.version(name, p.latestVersion()).orElse(null),
                store.runs(name, 1).stream().findFirst().orElse(null), sights);
        } catch (PipelineStore.Changed e) {
            throw changedSince();
        }
    }

    static WebApplicationException changedSince() {
        return fail(Response.Status.CONFLICT, "Someone else saved a new version of this pipeline after you opened it. "
            + "Load it as it is now, then make your change again.");
    }

    /** The version number sent in If-Match ({@code "3"}, quotes and a weak W/ prefix allowed), or null for none. */
    public static Integer readVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank() || ifMatch.strip().equals("*")) {
            return null;
        }
        String v = ifMatch.strip();
        if (v.startsWith("W/")) {
            v = v.substring(2);
        }
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        try {
            int n = Integer.parseInt(v);
            if (n >= 1) {
                return n;
            }
        } catch (NumberFormatException e) {
            // explained below
        }
        throw new IllegalArgumentException("If-Match: the pipeline's latest version as you last read it, for example "
            + "\"3\"");
    }

    public void delete(String name, String by) {
        try {
            if (!store.delete(name)) {
                throw fail(Response.Status.NOT_FOUND, "No pipeline called " + name);
            }
            LOG.infof("Pipeline %s deleted by %s", name, by);
        } catch (PipelineStore.Busy e) {
            throw fail(Response.Status.CONFLICT, "A run of " + name + " is queued or running. Wait for it to finish, "
                + "or cancel it, then delete the pipeline.");
        }
    }

    public List<PipelineViews.VersionSummary> versions(String name) {
        find(name);
        return store.versions(name).stream().map(v -> new PipelineViews.VersionSummary(v.n(), v.message(), v.author(),
            v.createdAt(), v.valid(), problems(v.problems()).size())).toList();
    }

    public PipelineViews.VersionDetail version(String name, int n) {
        find(name);
        return store.version(name, n).map(this::version).orElseThrow(() -> fail(Response.Status.NOT_FOUND,
            name + " has no version " + n));
    }

    // ---- runs ----

    /**
     * Queues a run of one version (the latest by default). The version is checked again now, since
     * sources may have changed since it was saved: one with problems cannot run.
     */
    public PipelineViews.RunSummary queueRun(String name, Integer version, String by, AccessRole role) {
        PipelineStore.Pipeline p = find(name);
        int n = version == null ? p.latestVersion() : version;
        PipelineStore.Version v = store.version(name, n).orElseThrow(() -> fail(Response.Status.NOT_FOUND,
            name + " has no version " + n));
        PipelineViews.Validation check = validate(v.yaml(), name);
        if (!check.valid()) {
            throw fail(Response.Status.CONFLICT, "Version " + n + " of " + name + " cannot run until its problems are "
                + "fixed: " + firstProblems(check.problems()));
        }
        PipelineStore.Run run;
        try {
            run = store.queueRun(name, n, by, role.id());
        } catch (PipelineStore.Busy e) {
            throw fail(Response.Status.CONFLICT, "A run of " + name + " is already queued or running. Wait for it to "
                + "finish, or cancel it, then run the pipeline again.");
        }
        LOG.infof("Run %d of pipeline %s (version %d) queued by %s", run.id(), name, n, by);
        return summary(run, null); // just queued: no error yet
    }

    private static String firstProblems(List<PipelineCompilerPort.Problem> problems) {
        List<String> out = new ArrayList<>();
        for (PipelineCompilerPort.Problem p : problems.subList(0, Math.min(3, problems.size()))) {
            out.add((p.line() > 0 ? "line " + p.line() + ": " : "") + p.message());
        }
        return String.join("; ", out) + (problems.size() > 3 ? "; and " + (problems.size() - 3) + " more" : "");
    }

    public List<PipelineViews.RunSummary> runs(String name, int limit, Sights sights) {
        find(name);
        List<PipelineStore.Run> runs = store.runs(name, Math.max(1, Math.min(limit, 200)));
        prepare(runs, sights);
        return runs.stream().map(r -> summary(r, sights)).toList();
    }

    public PipelineViews.RunDetail run(long id, Sights sights) {
        PipelineStore.Run r = store.run(id).orElseThrow(() -> fail(Response.Status.NOT_FOUND, "No pipeline run " + id));
        String withheld = withheld(r, sights);
        List<PipelineViews.StepRunView> stepViews = store.stepRuns(id).stream().map(s -> new PipelineViews.StepRunView(
            s.stepId(), s.type(), s.position(), s.rowsIn(), s.rowsOut(), tree(s.portRows()), s.peakMemory(),
            s.durationMs(), s.error() == null || withheld == null ? s.error() : "This step failed. " + withheld,
            s.outcome())).toList();
        JsonNode result = tree(r.result());
        List<PipelineViews.WrittenAsset> written = new ArrayList<>();
        List<String> read = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (result != null) {
            for (JsonNode w : result.path("written")) {
                written.add(new PipelineViews.WrittenAsset(w.path("step").asText(), w.path("urn").asText(),
                    w.path("source").asText(), w.path("target").asText(), w.path("mode").asText(),
                    w.hasNonNull("rows") ? w.get("rows").asLong() : null, w.path("registered").asBoolean()));
            }
            result.path("read").forEach(n -> read.add(n.asText()));
            for (JsonNode n : result.path("notes")) {
                // A note's text is the server's own; its detail may quote the engine, so it is held back as
                // the run's other messages are.
                notes.add(noteText(n, withheld));
            }
        }
        List<PipelineViews.LineageLink> lineage = store.lineageOfRun(id).stream()
            .map(l -> new PipelineViews.LineageLink(l.stepId(), l.inputUrn(), l.outputUrn())).toList();
        return new PipelineViews.RunDetail(summary(r, sights), stepViews, written, read, lineage, notes);
    }

    /** Asks a run to stop: a queued run is cancelled at once, a running one within a few seconds. */
    public PipelineViews.RunSummary cancel(long id, String by) {
        PipelineStore.Run before = store.run(id).orElseThrow(() -> fail(Response.Status.NOT_FOUND,
            "No pipeline run " + id));
        if (before.finished()) {
            throw fail(Response.Status.CONFLICT, "Run " + id + " has already finished (" + before.status() + ")");
        }
        PipelineStore.Run r = store.requestCancel(id, by).orElseThrow(() -> fail(Response.Status.NOT_FOUND,
            "No pipeline run " + id));
        if (r.status().equals("running") && !store.jobActive(id)) {
            // Its worker stopped without finishing it: nothing will notice the request, so finish it here,
            // or the pipeline could never run again.
            store.finishRun(id, "cancelled", "Cancelled by " + by + "; the worker running it had stopped", null);
            r = store.run(id).orElse(r);
        }
        LOG.infof("Run %d of pipeline %s cancelled by %s", id, r.pipeline(), by);
        return summary(r, null); // a cancel says who cancelled, nothing from the data
    }

    /** Lineage recorded by pipeline writes: what an asset came from, and what was made from it. */
    public PipelineViews.AssetLineage lineage(String urn) {
        if (urn == null || !urn.startsWith("urn:kilnmark:")) {
            throw new IllegalArgumentException("urn: an asset reference, such as urn:kilnmark:lab-db:crm.customers");
        }
        List<PipelineViews.LineageEntry> up = new ArrayList<>();
        List<PipelineViews.LineageEntry> down = new ArrayList<>();
        for (PipelineStore.Lineage l : store.lineageOfAsset(urn)) {
            if (l.outputUrn().equals(urn)) {
                up.add(new PipelineViews.LineageEntry(l.inputUrn(), l.pipeline(), l.runId(), l.stepId(), l.recordedAt()));
            } else {
                down.add(new PipelineViews.LineageEntry(l.outputUrn(), l.pipeline(), l.runId(), l.stepId(),
                    l.recordedAt()));
            }
        }
        return new PipelineViews.AssetLineage(urn, up, down);
    }

    private JsonNode tree(String text) {
        if (text == null) {
            return null;
        }
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    static final String FOR_STEWARDS = "Stewards can see why: the message may hold values from the data.";
    static final String FOR_ADMINS = "Only admins can see why: the message may hold values of columns marked "
        + "sensitive.";

    /**
     * Why the run's messages from the engine are held back from this caller, or null when they are
     * not: a failed run's error, its steps' errors and the details of its notes, whatever its status.
     * An engine's message may quote values from the rows (a value that would not convert, say), so
     * only roles that see data read them, and only admins when the run read or wrote an asset with
     * columns marked sensitive. Decided through the request's {@link Sights}, as every view of data
     * values is, on the assets the run recorded as it started; {@code sights} null is the server's
     * own use.
     */
    private String withheld(PipelineStore.Run r, Sights sights) {
        if (sights == null) {
            return null;
        }
        if (!sights.data()) {
            return FOR_STEWARDS;
        }
        if (sights.mayUnmask()) {
            return null;
        }
        List<String> urns = assets(r);
        sights.load(urns); // a no-op for assets already loaded by prepare()
        return urns.stream().anyMatch(u -> !sights.marks(u).isEmpty()) ? FOR_ADMINS : null;
    }

    /** Loads the marks of every asset these runs touched in one query, before their views are made. */
    private void prepare(List<PipelineStore.Run> runs, Sights sights) {
        if (sights == null || !sights.data() || sights.mayUnmask()) {
            return;
        }
        sights.load(runs.stream().filter(java.util.Objects::nonNull).flatMap(r -> assets(r).stream()).distinct()
            .toList());
    }

    /** The assets a run reads and writes: as recorded when it started, else from its result. */
    private List<String> assets(PipelineStore.Run r) {
        List<String> urns = new ArrayList<>();
        JsonNode recorded = tree(r.assets());
        if (recorded != null) {
            recorded.forEach(n -> urns.add(n.asText()));
        }
        JsonNode result = tree(r.result());
        if (result != null) {
            result.path("read").forEach(n -> urns.add(n.asText()));
            result.path("written").forEach(n -> urns.add(n.path("urn").asText()));
        }
        return urns.stream().filter(u -> u.startsWith("urn:kilnmark:")).distinct().toList();
    }

    private PipelineViews.RunSummary summary(PipelineStore.Run r, Sights sights) {
        if (r == null) {
            return null;
        }
        JsonNode result = tree(r.result());
        Long rows = result != null && result.hasNonNull("rowsWritten") ? result.get("rowsWritten").asLong() : null;
        // Only a failed run's error can come from the engine; "Cancelled by ..." and the like are the server's.
        String withheld = r.error() == null || !"failed".equals(r.status()) ? null : withheld(r, sights);
        return new PipelineViews.RunSummary(r.id(), r.pipeline(), r.version(), r.status(), r.requestedBy(),
            r.requestedAt(), r.startedAt(), r.finishedAt(), r.cancelRequested(),
            withheld == null ? r.error() : "The run failed. " + withheld, rows);
    }

    // ---- the step catalogue ----

    /** Every step type with its ports and settings schema, by type. */
    public List<PipelineViews.StepType> catalogue() {
        List<PipelineViews.StepType> out = new ArrayList<>();
        for (StepFactory f : steps.all()) {
            List<String> inputs = f.inputPorts().stream().map(p -> p.endsWith("*") ? p.substring(0, p.length() - 1) : p)
                .toList();
            List<String> many = f.inputPorts().stream().filter(p -> p.endsWith("*"))
                .map(p -> p.substring(0, p.length() - 1)).toList();
            List<PipelineViews.SettingView> settings = new ArrayList<>();
            for (SettingSpec s : f.settings()) {
                settings.add(new PipelineViews.SettingView(s.key(), s.label(), s.kind().name().toLowerCase(Locale.ROOT),
                    s.required(), s.defaultValue(), s.help(), s.choices()));
            }
            out.add(new PipelineViews.StepType(f.type(), f.kind().name().toLowerCase(Locale.ROOT), f.label(), f.help(),
                inputs, many, f.outputPorts(), settings));
        }
        return out;
    }

    // ---- the job ----

    /** Marks failed the runs whose worker stopped without finishing them; called by workers' recovery. */
    public void failOrphanedRuns() {
        try {
            int n = store.failOrphanedRuns();
            if (n > 0) {
                LOG.infof("%d pipeline runs were left by a stopped worker and are now marked failed", n);
            }
        } catch (RuntimeException e) {
            LOG.debugf(e, "Could not check for pipeline runs left by a stopped worker");
        }
    }

    /** Runs the pipeline run behind job {@code jobId}; called by a worker. */
    public ScanService.Result execute(long jobId, JobQueue.ScanLease lease, BooleanSupplier shutdown) {
        store.failOrphanedRuns();
        Optional<Long> runId = store.runForJob(jobId);
        if (runId.isEmpty()) {
            return new ScanService.Result(ScanService.Outcome.SKIPPED, "Job " + jobId + " names no pipeline run");
        }
        long id = runId.get();
        Optional<PipelineStore.Run> found = store.run(id);
        if (found.isEmpty()) {
            return new ScanService.Result(ScanService.Outcome.SKIPPED, "Pipeline run " + id + " no longer exists");
        }
        PipelineStore.Run run = found.get();
        if (run.finished()) {
            return new ScanService.Result(ScanService.Outcome.SKIPPED, "Pipeline run " + id + " had already finished");
        }
        if (!store.startRun(id)) {
            if (store.cancelRequested(id)) {
                store.finishRun(id, "cancelled", "Cancelled before it started", null);
            }
            return new ScanService.Result(ScanService.Outcome.SKIPPED, "Pipeline run " + id + " was cancelled");
        }
        RunContext context = new RunContext(run.pipeline(), id, run.requestedBy(), role(run.requestedRole()));
        List<String> held = new ArrayList<>();
        PipelineCompilerPort.Compilation c = null;
        boolean ran = false;
        try {
            PipelineStore.Version v = store.version(run.pipeline(), run.version()).orElse(null);
            if (v == null) {
                store.finishRun(id, "failed", "Version " + run.version() + " of " + run.pipeline() + " no longer exists",
                    null);
                return new ScanService.Result(ScanService.Outcome.FINISHED, null);
            }
            if (stopped(id, shutdown, null)) {
                return new ScanService.Result(ScanService.Outcome.FINISHED, null);
            }
            c = context.run(() -> compiler.compile(v.yaml(), steps));
            if (!c.valid() || c.graph() == null) {
                store.finishRun(id, "failed", context.scrub("The pipeline could not start: "
                    + firstProblems(c.problems())), null);
                return new ScanService.Result(ScanService.Outcome.FINISHED, null);
            }
            if (stopped(id, shutdown, c.graph())) {
                return new ScanService.Result(ScanService.Outcome.FINISHED, null);
            }
            // Each write's asset under the URN its connector worked out (for a prefixed S3 source, the prefix
            // and the path), as a scan of the source records it.
            List<PipelineExecution.WriteTarget> writes = PipelineExecution.writes(c).stream()
                .map(w -> w.withUrn(context.writtenUrn(w.source(), w.target(), w.urn()))).toList();
            java.util.Set<String> assets = new java.util.TreeSet<>(context.reads());
            assets.addAll(PipelineExecution.reads(c).values());
            writes.forEach(w -> assets.add(w.urn()));
            store.setAssets(id, json.writeValueAsString(assets));
            // One run writes a table or file at a time: wait a while for another run writing it. The
            // locks are taken on the job's own connection, so a run never holds more than one.
            BooleanSupplier stop = () -> store.cancelRequested(id) || shutdown.getAsBoolean();
            for (String urn : writes.stream().map(PipelineExecution.WriteTarget::urn).distinct().sorted().toList()) {
                if (!JobQueue.waitFor(() -> lease.tryLockTarget(urn), config.pipelines().waitForTarget(), stop)) {
                    if (stopped(id, shutdown, c.graph())) {
                        return new ScanService.Result(ScanService.Outcome.FINISHED, null);
                    }
                    PipelineExecution.closeSteps(c.graph());
                    store.finishRun(id, "failed", "Another pipeline run is writing " + urn + ". Run this one again "
                        + "when it has finished.", null);
                    return new ScanService.Result(ScanService.Outcome.FINISHED, null);
                }
                held.add(urn);
            }
            ran = true;
            PipelineExecution.Outcome o = execution.run(runner, c.graph(), writes, context,
                () -> store.cancelRequested(id), shutdown);
            store.saveStepRuns(id, o.steps());
            ObjectNode result = json.createObjectNode();
            ArrayNode read = result.putArray("read");
            PipelineExecution.reads(c).values().stream().distinct().forEach(read::add);
            ArrayNode notes = json.createArrayNode();
            ArrayNode written = result.putArray("written");
            long total = 0;
            for (PipelineExecution.WriteTarget w : writes) {
                Long rows = o.rowsIn().get(w.step());
                total += rows == null ? 0 : rows;
                boolean registered = false;
                if (o.status().equals("succeeded")) {
                    // Writers profile what they write; one that could not says so on its ROW_COUNT, never by
                    // failing. The reason may quote the data, so it is the note's detail, held back via Sights.
                    String profileFailure = profileFailure(o.events(), w.urn());
                    if (profileFailure != null) {
                        profileFailureNote(notes, w, context.scrub(profileFailure));
                    }
                    registered = register(w, rows, o.events(), profileFailure == null, c, context, notes, lease,
                        shutdown);
                }
                ObjectNode n = written.addObject();
                n.put("step", w.step());
                n.put("urn", w.urn());
                n.put("source", w.source());
                n.put("target", w.target());
                n.put("mode", w.mode());
                if (rows == null) {
                    n.putNull("rows");
                } else {
                    n.put("rows", rows);
                }
                n.put("registered", registered);
            }
            result.put("rowsWritten", total);
            result.set("notes", notes);
            store.finishRun(id, o.status(), o.error(), result.toString());
            LOG.infof("Run %d of pipeline %s: %s", id, run.pipeline(), o.status());
        } catch (InterruptedException e) {
            Thread.interrupted(); // cleared so the run's record can be written
            if (!ran && c != null && c.graph() != null) {
                PipelineExecution.closeSteps(c.graph());
            }
            store.finishRun(id, "failed", "The worker running this pipeline was stopped before it started writing. "
                + "Run it again.", null);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            // The exception's own text may hold a source's secret: log it scrubbed, without the trace.
            LOG.errorf("Run %d of pipeline %s failed: %s", id, run.pipeline(), context.scrub(String.valueOf(e)));
            if (!ran && c != null && c.graph() != null) {
                PipelineExecution.closeSteps(c.graph()); // created, never handed to the runner
            }
            store.finishRun(id, "failed", context.scrub("The run failed: " + e.getMessage()), null);
        } finally {
            for (String urn : held) {
                try {
                    lease.unlockTarget(urn);
                } catch (RuntimeException e) {
                    LOG.debugf("Could not give back the write lock of %s; closing the job's lease does", urn);
                }
            }
        }
        return new ScanService.Result(ScanService.Outcome.FINISHED, null);
    }

    /**
     * Whether the run was asked to stop, or its worker is stopping, before its steps run; if so the
     * run gets its final status and {@code graph}'s steps (when created) are closed.
     */
    private boolean stopped(long id, BooleanSupplier shutdown, PipelineGraph graph) {
        boolean cancelled = store.cancelRequested(id);
        if (!cancelled && !shutdown.getAsBoolean()) {
            return false;
        }
        if (graph != null) {
            PipelineExecution.closeSteps(graph);
        }
        store.finishRun(id, cancelled ? "cancelled" : "failed", cancelled ? "Cancelled before it started"
            : "The worker running this pipeline was stopped before it started. Run it again.", null);
        return true;
    }

    private static AccessRole role(String text) {
        try {
            return AccessRole.parse(text);
        } catch (IllegalArgumentException e) {
            return AccessRole.VIEWER;
        }
    }

    /**
     * Registers what a write wrote as the scan registers what it finds: the asset and its fields,
     * and (when the write replaced the whole target) its row count and column profiles, under a
     * finished run of kind 'pipeline' on the written source; then its lineage from the assets read
     * upstream. Never throws: the run has already succeeded, so problems become notes.
     */
    private boolean register(PipelineExecution.WriteTarget w, Long rows, List<MetadataEvent> events, boolean profiled,
                             PipelineCompilerPort.Compilation c, RunContext context, ArrayNode notes,
                             JobQueue.ScanLease lease, BooleanSupplier shutdown) {
        try {
            Records.Source s = graph.source(w.source()).orElse(null);
            if (s == null) {
                note(notes, w.step() + ": source " + w.source() + " no longer exists, so what it wrote is not catalogued",
                    null);
                return false;
            }
            store.saveLineage(context.pipeline(), context.runId(), w.step(), w.inputs(), w.urn());
            // A scan ending now would mark an asset it did not see as removed: catalogue under the
            // source's scan lock, or leave it to the next scan.
            boolean locked;
            try {
                locked = JobQueue.waitFor(() -> lease.tryLockSource(s.id()), config.pipelines().waitForTarget(),
                    shutdown);
            } catch (InterruptedException e) {
                Thread.interrupted();
                locked = false;
            }
            if (!locked) {
                note(notes, w.step() + ": a scan of " + w.source() + " was running, so " + w.target() + " is not in "
                    + "the catalogue yet. Scan the source to catalogue the written table or file", null);
                return false;
            }
            try {
                catalogue(s, w, rows, events, profiled, c, context, notes);
            } finally {
                lease.unlockSource(s.id());
            }
            return true;
        } catch (RuntimeException e) {
            LOG.warnf("Could not catalogue what step %s of pipeline %s wrote: %s", w.step(), context.pipeline(),
                context.scrub(String.valueOf(e)));
            note(notes, w.step() + ": what it wrote could not be catalogued", context.scrub(e.getMessage()));
            return false;
        }
    }

    /**
     * Why a writer could not profile what it wrote to {@code urn}, from its {@code ROW_COUNT} event
     * ({@code profiled=false} with {@code profile_error}, docs/design/pipelines.md, "What a write
     * reports"); null when it profiled it, or reported no row count.
     */
    static String profileFailure(List<MetadataEvent> events, String urn) {
        for (MetadataEvent e : events) {
            if (e.kind() == MetadataEvent.Kind.ROW_COUNT && e.assetUrn().equals(urn)
                && "false".equals(e.attributes().get(WriteReport.PROFILED))) {
                String why = e.attributes().get(WriteReport.PROFILE_ERROR);
                return why == null || why.isBlank() ? "the writer did not say why" : why;
            }
        }
        return null;
    }

    /** The run's note for a write whose profile failed: the server's words, the writer's reason as its detail. */
    static void profileFailureNote(ArrayNode notes, PipelineExecution.WriteTarget w, String reason) {
        note(notes, w.step() + ": what it wrote could not be profiled; scan " + w.source() + " to profile "
            + w.target(), reason);
    }

    /**
     * A note as a caller sees it: its text, and its detail only when nothing is {@code withheld}
     * (otherwise why it is held back, {@link #withheld}).
     */
    static String noteText(JsonNode n, String withheld) {
        String text = n.isTextual() ? n.asText() : n.path("text").asText();
        String detail = n.hasNonNull("detail") ? n.get("detail").asText() : null;
        return detail == null ? text : withheld == null ? text + " (" + detail + ")" : text + ". " + withheld;
    }

    /**
     * The events a writer reported for {@code urn}, as the catalogue stores them: a {@code ROW_COUNT}
     * loses the profile failure's attributes, whose reason may quote the data and is kept only in the
     * run's note.
     */
    static List<MetadataEvent> reported(List<MetadataEvent> events, String urn) {
        List<MetadataEvent> out = new ArrayList<>();
        for (MetadataEvent e : events) {
            if (!e.assetUrn().equals(urn)) {
                continue;
            }
            if (e.kind() == MetadataEvent.Kind.ROW_COUNT && (e.attributes().containsKey(WriteReport.PROFILED)
                || e.attributes().containsKey(WriteReport.PROFILE_ERROR))) {
                Map<String, String> a = new LinkedHashMap<>(e.attributes());
                a.remove(WriteReport.PROFILED);
                a.remove(WriteReport.PROFILE_ERROR);
                e = new MetadataEvent(e.assetUrn(), e.kind(), a, e.observedAt());
            }
            out.add(e);
        }
        return out;
    }

    /**
     * A note on the run: {@code text} is the server's own words, shown to everyone; {@code detail},
     * an engine's or database's message, is shown only to those who may read the run's messages.
     */
    private static void note(ArrayNode notes, String text, String detail) {
        ObjectNode n = notes.addObject();
        n.put("text", text);
        if (detail != null) {
            n.put("detail", detail);
        }
    }

    /**
     * Catalogues one write from what its writer reported (docs/design/pipelines.md, section 5, "What
     * a write reports"). A write that leaves the asset holding exactly what was written (a table
     * created or replaced, a file) reports {@code SCHEMA}, {@code ROW_COUNT} and the profile: the
     * asset's fields, row count and profile are replaced, as a scan replaces them. An append or
     * upsert reports only {@code ROW_COUNT} with {@code appended=true}: the asset keeps the fields,
     * row count and profile of its last scan.
     */
    private void catalogue(Records.Source s, PipelineExecution.WriteTarget w, Long rows, List<MetadataEvent> events,
                           boolean profiled, PipelineCompilerPort.Compilation c, RunContext context, ArrayNode notes) {
        long recordRun = store.recordWrite(s.id(), rows == null ? 0 : rows, "Written by pipeline "
            + context.pipeline() + " (run " + context.runId() + ", step " + w.step() + ")");
        Records.AssetDetail known = graph.asset(w.urn()).orElse(null);
        List<MetadataEvent> mine = reported(events, w.urn());
        MetadataEvent schema = mine.stream().filter(e -> e.kind() == MetadataEvent.Kind.SCHEMA).findFirst().orElse(null);
        boolean appended = mine.stream().anyMatch(e -> e.kind() == MetadataEvent.Kind.ROW_COUNT
            && "true".equals(e.attributes().get("appended"))) || schema == null && !w.wholeTarget();
        if (appended && known != null) {
            // Rows added to a table the catalogue knows: keep what its last scan found.
            graph.saveAsset(s.id(), recordRun, new Records.Asset(w.urn(), known.kind(), known.container(), known.name(),
                known.description(), known.primaryKey()), known.fields().stream().map(f -> new Records.Field(f.name(),
                f.position(), f.sourceType(), f.kind(), f.nullable(), f.description())).toList());
            note(notes, w.step() + ": the " + w.mode() + " added " + (rows == null ? "rows" : rows + " rows") + " to "
                + w.target() + "; its columns, row count and profile stay as its last scan found them. Scan "
                + w.source() + " to refresh them", null);
            return;
        }
        List<Records.Field> fields = schema == null ? List.of() : schemaFields(schema);
        AssetInfo info = fields.isEmpty() ? describe(w, context) : null;
        if (fields.isEmpty()) {
            fields = info != null && !info.fields().isEmpty() ? fields(info) : fieldsFromColumns(w, c, mine);
        }
        boolean files = sources.connector(s.kind()).map(k -> "files".equals(k.category())).orElse(false);
        String kind = info != null ? info.kind() : files ? "file" : "table";
        String container = info != null && info.container() != null ? info.container() : container(w.target());
        String name = info != null && info.name() != null ? info.name() : lastPart(w.target());
        String description = info != null && info.description() != null ? info.description()
            : known == null ? null : known.description();
        graph.saveAsset(s.id(), recordRun, new Records.Asset(w.urn(), kind, container, name, description,
            info != null ? info.primaryKey() : known != null ? known.primaryKey() : w.key()), fields);
        if (appended) {
            note(notes, w.step() + ": the " + w.mode() + " added rows to " + w.target() + ", which Kilnmark had not "
                + "catalogued; scan " + w.source() + " for its row count and profile", null);
            return;
        }
        List<MetadataEvent> toStore = new ArrayList<>(mine.stream().filter(e -> e.kind() != MetadataEvent.Kind.SCHEMA)
            .filter(e -> profiled || e.kind() == MetadataEvent.Kind.ROW_COUNT).toList());
        if (toStore.stream().noneMatch(e -> e.kind() == MetadataEvent.Kind.ROW_COUNT) && rows != null) {
            toStore.add(0, new MetadataEvent(w.urn(), MetadataEvent.Kind.ROW_COUNT,
                Map.of("rows", Long.toString(rows), "complete", "true"), Instant.now()));
        }
        int failed = 0;
        for (MetadataEvent e : toStore) {
            try {
                scans.recordEvent(e, recordRun);
            } catch (RuntimeException ex) {
                failed++;
                LOG.debugf("Could not store %s for %s: %s", e.kind(), w.urn(), context.scrub(ex.getMessage()));
            }
            quality.observe(e, recordRun, s); // samples only when the source keeps them
        }
        if (failed > 0) {
            note(notes, w.step() + ": " + failed + " column profiles could not be stored; scan " + w.source()
                + " to profile " + w.target(), null);
        }
        quality.afterScan(s, recordRun);
    }

    /** The written columns a writer's {@code SCHEMA} event lists: {@code [{name, kind, type}]}. */
    private List<Records.Field> schemaFields(MetadataEvent schema) {
        List<Records.Field> out = new ArrayList<>();
        JsonNode columns = tree(schema.attributes().get("columns"));
        if (columns == null || !columns.isArray()) {
            return out;
        }
        int i = 0;
        for (JsonNode col : columns) {
            String name = col.path("name").asText(null);
            if (name == null || name.isBlank()) {
                continue;
            }
            String kind = col.path("kind").asText("text").toLowerCase(Locale.ROOT);
            out.add(new Records.Field(name, ++i, col.path("type").asText(kind), kind, true, null));
        }
        return out;
    }

    /** The written asset as its connector now describes it, or null when it cannot. */
    private AssetInfo describe(PipelineExecution.WriteTarget w, RunContext context) {
        try {
            PipelineSources.Resolved r = sources.resolve(w.step(), w.source(), context);
            return r.connector().asset(r.config(), w.target());
        } catch (StepException | io.kilnmark.kiln.connect.ConnectorException | RuntimeException e) {
            LOG.debugf("Could not describe %s after writing it: %s", w.urn(), context.scrub(e.getMessage()));
            return null;
        }
    }

    private static List<Records.Field> fields(AssetInfo a) {
        return a.fields().stream().map(f -> new Records.Field(f.name(), f.position(), f.sourceType(), f.kind(),
            f.nullable(), f.description())).toList();
    }

    /** The write's columns as validation worked them out, else as the profile step saw them. */
    private static List<Records.Field> fieldsFromColumns(PipelineExecution.WriteTarget w,
                                                         PipelineCompilerPort.Compilation c,
                                                         List<MetadataEvent> events) {
        for (PipelineCompilerPort.EdgeOutline e : c.edges()) {
            if (!e.toStep().equals(w.step())) {
                continue;
            }
            for (PipelineCompilerPort.StepOutline s : c.steps()) {
                if (s.id().equals(e.fromStep()) && s.columns().get(e.fromPort()) != null) {
                    List<Records.Field> out = new ArrayList<>();
                    int i = 0;
                    for (PipelineCompilerPort.Column col : s.columns().get(e.fromPort())) {
                        out.add(new Records.Field(col.name(), ++i, col.type(), col.type(), true, null));
                    }
                    return out;
                }
            }
        }
        Map<String, String> seen = new LinkedHashMap<>();
        for (MetadataEvent e : events) {
            if (e.kind() == MetadataEvent.Kind.PROFILE && e.attributes().get("field") != null) {
                seen.putIfAbsent(e.attributes().get("field"), kindOfShape(e.attributes().get("shape")));
            }
        }
        List<Records.Field> out = new ArrayList<>();
        int i = 0;
        for (Map.Entry<String, String> f : seen.entrySet()) {
            out.add(new Records.Field(f.getKey(), ++i, f.getValue(), f.getValue(), true, null));
        }
        return out;
    }

    private static String kindOfShape(String shape) {
        if (shape == null) {
            return "text";
        }
        return switch (shape) {
            case "integer" -> "int64";
            case "number" -> "float64";
            case "boolean" -> "boolean";
            case "date" -> "date";
            case "time" -> "time";
            case "timestamp" -> "timestamp";
            case "binary" -> "binary";
            default -> "text";
        };
    }

    private static String container(String target) {
        int slash = target.lastIndexOf('/');
        if (slash > 0) {
            return target.substring(0, slash);
        }
        int dot = target.indexOf('.');
        return dot > 0 && !target.contains("/") && target.indexOf('.', dot + 1) < 0 && !looksLikeFile(target)
            ? target.substring(0, dot) : "";
    }

    private static String lastPart(String target) {
        int slash = target.lastIndexOf('/');
        if (slash >= 0) {
            return target.substring(slash + 1);
        }
        int dot = target.indexOf('.');
        return dot > 0 && !looksLikeFile(target) ? target.substring(dot + 1) : target;
    }

    private static boolean looksLikeFile(String target) {
        String t = target.toLowerCase(Locale.ROOT);
        return t.endsWith(".csv") || t.endsWith(".parquet") || t.endsWith(".json") || t.endsWith(".txt");
    }
}

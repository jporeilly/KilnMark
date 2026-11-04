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
import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.NodeFactories;
import io.kilnmark.kiln.NodeFactory;
import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.graph.GraphRunner;
import io.kilnmark.kiln.graph.PipelineGraph;
import io.kilnmark.kiln.plugins.write.WriteReport;
import io.kilnmark.kiln.yaml.Parameters;
import io.kilnmark.kiln.yaml.PipelineDefinition;
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
 * recording each node's part, the assets it wrote and their lineage.
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
    private final NodeFactories nodes;
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

    /** Notifications: a run that fails is told, once until a run of the pipeline succeeds (docs/design/notifications.md). */
    @Inject
    io.kilnmark.server.notify.NotificationPublisher notifications;

    /** The catalogue's metadata, for predicted quality (docs/design/data-quality.md section 8). */
    @Inject
    PredictionCatalogue predictions;

    /** The AI model for suggestions, and each source's AI rule. */
    @Inject
    io.kilnmark.server.ai.AiGateway ai;

    @Inject
    io.kilnmark.server.ai.EgressPolicy egress;

    /** The server's variables (${vars.name}); null in tests that build the service by hand. */
    @Inject
    io.kilnmark.server.variables.VariableService variables;


    @Inject
    public PipelineService(PipelineStore store, PipelineCompilerPort compiler, NodeFactories nodes, GraphRunner runner,
                           GraphStore graph, PipelineSources sources, KilnmarkConfig config, ObjectMapper json) {
        this.store = store;
        this.compiler = compiler;
        this.nodes = nodes;
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
     * Checks YAML without running anything: problems with lines, each node's ports and output
     * columns. {@code expectedName}, when given, is the pipeline's name, which the YAML's
     * {@code name} must match.
     */
    public PipelineViews.Validation validate(String yaml, String expectedName) {
        checkSize(yaml);
        PipelineCompilerPort.Compilation c = compiler.check(yaml, nodes, Map.of(), variables());
        List<PipelineCompilerPort.Problem> problems = new ArrayList<>(c.problems());
        if (expectedName != null && c.name() != null && !c.name().equals(expectedName)) {
            problems.add(0, new PipelineCompilerPort.Problem(lineOf(yaml, "name"), 1, null, "name: this pipeline is "
                + "called " + expectedName + "; a pipeline's name cannot change. Write name: " + expectedName));
        }
        return new PipelineViews.Validation(problems.isEmpty(), c.name(), c.description(), problems, c.notes(),
            c.nodes(), c.edges());
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

    // ---- predicted quality and suggestions ----

    /**
     * The predicted quality of pipeline YAML as it is being edited: each write's and gate's score
     * and the rows on each port, estimated from the catalogue's metadata about what it reads. Never
     * reads a source.
     */
    public PredictionViews.Prediction predict(String yaml, Sights sights) {
        checkSize(yaml);
        PipelineCompilerPort.Compilation c = compiler.check(yaml, nodes, Map.of(), variables());
        PredictionCatalogue.Remembering catalogue = predictions.open(sights);
        QualityPredictor.Plan plan = QualityPredictor.Plan.of(c, designReads(c, catalogue));
        return PredictionViews.of(new QualityPredictor(catalogue).predict(plan), c.valid());
    }

    /**
     * The assets read, by node, before a run resolves them: as the settings name them, or, when the
     * catalogue has nothing under that name and the path is a file's, under the path as the catalogue
     * escapes it ({@code #} and {@code %}), as a run's read node finds it.
     */
    static Map<String, String> designReads(PipelineCompilerPort.Compilation c, QualityPredictor.Catalogue catalogue) {
        Map<String, String> out = new LinkedHashMap<>(PipelineExecution.reads(c));
        for (Map.Entry<String, String> r : out.entrySet()) {
            Map<String, Object> config = c.configs().getOrDefault(r.getKey(), Map.of());
            String source = PipelineSources.text(config, "source");
            String asset = PipelineSources.text(config, "asset");
            if (asset == null || catalogue.facts(r.getValue()) != null) {
                continue;
            }
            String escaped = PipelineSources.urn(source, ReadNodeFactory.escapedFilePath(asset));
            if (escaped != null && !escaped.equals(r.getValue()) && catalogue.facts(escaped) != null) {
                r.setValue(escaped);
            }
        }
        return out;
    }

    /** Most calls to the suggestions model per person in {@link #MODEL_WINDOW}. */
    static final int MODEL_CALLS = 6;
    static final java.time.Duration MODEL_WINDOW = java.time.Duration.ofMinutes(1);
    private final Map<String, java.util.ArrayDeque<Long>> modelCalls = new java.util.concurrent.ConcurrentHashMap<>();

    /** Whether {@code caller} may ask the suggestions model now; counts the call when so. */
    boolean mayAskModel(String caller) {
        long now = System.currentTimeMillis();
        java.util.ArrayDeque<Long> calls = modelCalls.computeIfAbsent(caller == null ? "" : caller,
            k -> new java.util.ArrayDeque<>());
        synchronized (calls) {
            while (!calls.isEmpty() && calls.peekFirst() < now - MODEL_WINDOW.toMillis()) {
                calls.pollFirst();
            }
            if (calls.size() >= MODEL_CALLS) {
                return false;
            }
            calls.addLast(now);
            return true;
        }
    }

    /** The predicted quality of a saved version, as {@link #predict} works it out now. */
    public PredictionViews.Prediction predictVersion(String name, int n, Sights sights) {
        find(name);
        PipelineStore.Version v = store.version(name, n).orElseThrow(() -> fail(Response.Status.NOT_FOUND,
            name + " has no version " + n));
        return predict(v.yaml(), sights);
    }

    /**
     * Suggestions to improve the predicted quality of pipeline YAML: Kilnmark's own, then, when
     * {@code useAi} and a model is routed to pipeline suggestions, ranked, explained and added to by
     * it, from metadata its sources' AI rules let it see. The model being off or failing never fails
     * the request: the suggestions are then Kilnmark's own, and {@code ai} says why.
     */
    public PredictionViews.Advice suggest(String yaml, boolean useAi, String caller, Sights sights) {
        PredictionCatalogue.Remembering catalogue = suggestionCatalogue(predictions, useAi, sights);
        checkSize(yaml);
        PipelineCompilerPort.Compilation c = compiler.check(yaml, nodes, Map.of(), variables());
        QualityPredictor.Plan plan = QualityPredictor.Plan.of(c, designReads(c, catalogue));
        PipelineAdvisor advisor = new PipelineAdvisor(catalogue, nodes, json);
        PipelineAdvisor.Model model = null;
        PipelineAdvisor.AiStatus status;
        io.kilnmark.server.ai.AiFeature feature = io.kilnmark.server.ai.AiFeature.PIPELINE_SUGGESTIONS;
        Optional<io.kilnmark.server.ai.ModelProfile> profile = ai == null ? Optional.empty() : ai.profileFor(feature);
        if (!useAi) {
            status = new PipelineAdvisor.AiStatus("not_asked", "The suggestions are Kilnmark's own.", null);
        } else if (profile.isEmpty()) {
            status = new PipelineAdvisor.AiStatus("off", "No AI model is set for pipeline suggestions, so they are "
                + "Kilnmark's own. An admin can choose one under AI models.", null);
        } else if (!mayAskModel(caller)) {
            status = new PipelineAdvisor.AiStatus("not_asked", "The model has been asked " + MODEL_CALLS + " times in "
                + "the last minute, so these are Kilnmark's own. Ask again in a minute.", profile.get().name());
        } else {
            io.kilnmark.server.ai.ModelProfile p = profile.get();
            status = null;
            model = new PipelineAdvisor.Model() {
                @Override
                public String name() {
                    return p.name();
                }

                @Override
                public String refusal(Records.Source source) {
                    return egress.refusal(p, source).orElse(null);
                }

                @Override
                public String chat(Records.Source source, io.kilnmark.server.ai.ModelClient.ChatCall call) {
                    return ai.chatWith(p, feature.id(), source, call, caller, () -> false,
                        io.kilnmark.server.ai.AiGateway.Budget.INTERACTIVE).answer().text();
                }
            };
        }
        return PredictionViews.of(advisor.advise(plan, model, status, n -> graph.source(n).orElse(null)));
    }

    /**
     * The catalogue suggestions read. With {@code useAi} the caller's sights are told first that the
     * request answers a model, so what the model is shown is never unmasked, whoever asks, a designer
     * with the unmask grant included (ADR 0027). A model is never asked without a caller's sights.
     *
     * @throws IllegalStateException for {@code useAi} with no sights: a programming error
     */
    static PredictionCatalogue.Remembering suggestionCatalogue(PredictionCatalogue predictions, boolean useAi,
                                                               Sights sights) {
        if (useAi) {
            if (sights == null) {
                throw new IllegalStateException("AI pipeline suggestions need the caller's sights, so that what the "
                    + "model is shown is masked");
            }
            sights.speakingToModel();
        }
        return predictions.open(sights);
    }

    /**
     * Each write's and gate's predicted quality for a run about to start, from the catalogue as it is
     * now and the reads as the run resolved them. Never fails the run: a prediction that cannot be
     * made is left out.
     */
    List<RunQuality.NodeQuality> predictRun(PipelineCompilerPort.Compilation c, RunContext context) {
        List<RunQuality.NodeQuality> out = new ArrayList<>();
        try {
            QualityPredictor.Plan plan = QualityPredictor.Plan.of(c, PipelineExecution.reads(c, context));
            for (QualityPredictor.Predicted p : new QualityPredictor(predictions.open()).predict(plan).outputs()) {
                ObjectNode detail = json.createObjectNode();
                detail.set("reasons", json.valueToTree(p.reasons()));
                detail.set("unknown", json.valueToTree(p.unknown()));
                detail.set("assumptions", json.valueToTree(p.assumptions()));
                if (p.rows() == null) {
                    detail.putNull("rows");
                } else {
                    detail.put("rows", p.rows());
                }
                if (p.gate() != null) {
                    detail.set("gate", json.valueToTree(PredictionViews.of(p.gate())));
                }
                RunQuality.Score score = p.overall() == null ? null : new RunQuality.Score(p.overall(), p.dimensions(),
                    p.reasons());
                out.add(new RunQuality.NodeQuality(p.node(), p.role(), p.asset(), null, score,
                    "Not measured yet: the run has just started", null, json.writeValueAsString(detail)));
            }
        } catch (RuntimeException | JsonProcessingException e) {
            LOG.warnf("Could not predict the quality of run %d of %s: %s", context.runId(), context.pipeline(),
                context.scrub(String.valueOf(e.getMessage())));
        }
        return out;
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

    /**
     * Refuses YAML with a read that runs a query unless the caller may see unmasked values: a query
     * can read any column of its source, so only a designer holding the unmask grant writes one
     * (ADR 0028). Runs are not refused: what a query reads is masked as marked for everyone else.
     * Called by {@link #create} and {@link #save} themselves, so no caller can skip it; without
     * {@code sights} (no person asking) a query read is refused.
     */
    void allowQueries(String yaml, Sights sights) {
        if (yaml == null || (sights != null && sights.mayUnmask())) {
            return;
        }
        checkSize(yaml);
        if (PipelinePreviews.hasQueryRead(compiler.check(yaml, nodes, Map.of(), variables()))) {
            throw fail(Response.Status.FORBIDDEN, PipelinePreviews.QUERY_NEEDS_UNMASK);
        }
    }

    /**
     * Adds a pipeline with its first version; an invalid one is saved as a draft. A read that runs a
     * query is refused unless {@code sights} may see unmasked values ({@link #allowQueries}).
     */
    public PipelineViews.PipelineDetail create(PipelineViews.NewPipeline body, String by, Sights sights) {
        if (body == null) {
            throw new IllegalArgumentException("Send the pipeline as JSON with a name and its YAML");
        }
        allowQueries(body.yaml(), sights);
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
        allowQueries(body.yaml(), sights);
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
        return queueRun(name, version, Map.of(), by, role);
    }

    /**
     * As {@link #queueRun(String, Integer, String, AccessRole)}, with values for the pipeline's
     * parameters (the others take their defaults). The run records them and the server's variables as
     * they are now, and reads those however the variables change before it runs.
     */
    public PipelineViews.RunSummary queueRun(String name, Integer version, Map<String, Object> parameters, String by,
                                             AccessRole role) {
        Map<String, Object> vars = variables();
        RunnableVersion checked = runnableVersion(name, version, parameters, vars);
        int n = checked.version();
        PipelineStore.Run run;
        try {
            run = store.queueRun(name, n, by, role.id(), null, null, writeJson(checked.parameters()), writeJson(vars));
        } catch (PipelineStore.Busy e) {
            throw fail(Response.Status.CONFLICT, "A run of " + name + " is already queued or running. Wait for it to "
                + "finish, or cancel it, then run the pipeline again.");
        }
        LOG.infof("Run %d of pipeline %s (version %d) queued by %s", run.id(), name, n, by);
        return summary(run, null); // just queued: no error yet
    }

    /**
     * Queues a run for a node of a flow (docs/design/flows.md): the same checks as {@link #queueRun}, and
     * the run records which flow run and node started it. A run of the pipeline already queued or running
     * is not an error here: the flow waits and asks again.
     *
     * @throws PipelineStore.Busy when the pipeline has a run queued or running
     * @throws WebApplicationException (404, 409) when the pipeline or version is missing or cannot run
     */
    public PipelineStore.Run queueRunForFlow(String name, Integer version, String by, AccessRole role,
                                             long flowRunId, String flowNode, Map<String, Object> parameters,
                                             Map<String, Object> variables) {
        RunnableVersion checked = runnableVersion(name, version, parameters, variables);
        int n = checked.version();
        PipelineStore.Run run = store.queueRun(name, n, by, role.id(), flowRunId, flowNode,
            writeJson(checked.parameters()), writeJson(variables));
        LOG.infof("Run %d of pipeline %s (version %d) queued by %s for node %s of flow run %d", run.id(), name, n, by,
            flowNode, flowRunId);
        return run;
    }

    /** A version that can run, and its parameters' values for the run, defaults filled in. */
    private record RunnableVersion(int version, Map<String, Object> parameters) {
    }

    /**
     * The version to run (the latest by default), checked again now with the run's parameters and
     * variables, since sources may have changed since it was saved: one with problems cannot run.
     * A wrong parameter value is the caller's mistake (400); a version with problems is a 409.
     */
    private RunnableVersion runnableVersion(String name, Integer version, Map<String, Object> parameters,
                                     Map<String, Object> vars) {
        PipelineStore.Pipeline p = find(name);
        int n = version == null ? p.latestVersion() : version;
        PipelineStore.Version v = store.version(name, n).orElseThrow(() -> fail(Response.Status.NOT_FOUND,
            name + " has no version " + n));
        PipelineDefinition def = PipelineDefinition.parse(v.yaml()).definition();
        Map<String, Object> values;
        try {
            values = Parameters.resolve(name, def == null ? List.of() : def.parameters(), parameters, vars);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("parameters: " + e.getMessage());
        }
        checkSize(v.yaml());
        PipelineCompilerPort.Compilation c = compiler.check(v.yaml(), nodes, values, vars);
        if (!c.valid() || c.name() == null || !c.name().equals(name)) {
            List<PipelineCompilerPort.Problem> problems = c.problems().isEmpty() ? validate(v.yaml(), name).problems()
                : c.problems();
            throw fail(Response.Status.CONFLICT, "Version " + n + " of " + name + " cannot run until its problems are "
                + "fixed: " + firstProblems(problems));
        }
        if (PipelinePreviews.hasQueryRead(c) && !store.queriesChecked(name, n)) {
            throw fail(Response.Status.CONFLICT, "Version " + n + " of " + name + " reads with a query and was saved "
                + "before queries were checked. A designer who may see unmasked values saves it again, then it runs.");
        }
        return new RunnableVersion(n, values);
    }

    /** The server's variables now, as a run reads them; none in tests without them. */
    private Map<String, Object> variables() {
        return variables == null ? Map.of() : variables.snapshot();
    }

    private String writeJson(Map<String, Object> values) {
        try {
            return json.writeValueAsString(values == null ? Map.of() : values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not record a run's values", e);
        }
    }

    private Map<String, Object> jsonMap(String text) {
        try {
            Map<String, Object> m = json.readValue(text == null ? "{}" : text,
                new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {
                });
            m.replaceAll((k, v) -> v instanceof Number num ? num.doubleValue() : v);
            return m;
        } catch (JsonProcessingException e) {
            return null; // the caller fails the run
        }
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
        List<PipelineViews.NodeRunView> nodeViews = store.nodeRuns(id).stream().map(s -> new PipelineViews.NodeRunView(
            s.nodeId(), s.type(), s.position(), s.rowsIn(), s.rowsOut(), tree(s.portRows()), s.peakMemory(),
            s.durationMs(), s.error() == null || withheld == null ? s.error() : "This node failed. " + withheld,
            s.outcome(), s.startedAt(), s.firstRowAt(), s.lastRowAt(), s.finishedAt(), s.waitingForRowsMs(),
            s.heldUpMs())).toList();
        JsonNode result = tree(r.result());
        List<PipelineViews.WrittenAsset> written = new ArrayList<>();
        List<String> read = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (result != null) {
            for (JsonNode w : result.path("written")) {
                written.add(new PipelineViews.WrittenAsset(w.path("node").asText(), w.path("urn").asText(),
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
            .map(l -> new PipelineViews.LineageLink(l.nodeId(), l.inputUrn(), l.outputUrn())).toList();
        List<PipelineViews.NodeQualityView> quality = store.quality(id).stream().map(this::qualityView).toList();
        return new PipelineViews.RunDetail(summary(r, sights), nodeViews, written, read, lineage, notes, quality,
            tree(store.progress(id)));
    }

    /**
     * A run's rows a second over time: its points after {@code after} (ms from the worker's start).
     * Counts only. When {@code stride} has changed since the caller last read, it reads again from 0:
     * the points were merged.
     */
    public PipelineViews.RunSeries series(long id, long after) {
        store.run(id).orElseThrow(() -> fail(Response.Status.NOT_FOUND, "No pipeline run " + id));
        PipelineStore.Series s = store.series(id, Math.max(-1, after));
        List<PipelineViews.SeriesPoint> points = s.points().stream()
            .map(p -> new PipelineViews.SeriesPoint(p.t(), tree(p.rates()), tree(p.fills()))).toList();
        return new PipelineViews.RunSeries(tree(s.nodes()), s.stride(), points);
    }

    /** Events per page of a run's log, at most. */
    public static final int MAX_EVENTS_PAGE = 1000;

    /**
     * A page of a run's log: events after {@code after}, about {@code node} (null for all), at least
     * as serious as {@code level} (info, warn or error; null for all). Details are held back as the
     * run's other messages are.
     */
    public PipelineViews.RunEvents events(long id, String node, String level, int after, int limit, Sights sights) {
        PipelineStore.Run r = store.run(id).orElseThrow(() -> fail(Response.Status.NOT_FOUND, "No pipeline run " + id));
        if (level != null && !level.isBlank() && !List.of("info", "warn", "error").contains(level)) {
            throw new IllegalArgumentException("level: info, warn or error");
        }
        String min = level == null || level.isBlank() ? null : level;
        String which = node == null || node.isBlank() ? null : node;
        int n = Math.max(1, Math.min(limit, MAX_EVENTS_PAGE));
        String withheld = withheld(r, sights);
        List<PipelineStore.Event> rows = store.events(id, which, min, Math.max(0, after), n + 1);
        boolean more = rows.size() > n;
        List<PipelineViews.RunEvent> out = rows.stream().limit(n).map(e -> event(e, withheld)).toList();
        return new PipelineViews.RunEvents(out, more ? out.getLast().seq() : null);
    }

    /**
     * The whole of a run's log, with the same filters and rules as {@link #events}, handed to
     * {@code each} a page at a time as it is read. Who may read the details is decided first, so the
     * reading can go on after the request's own work (a streamed download).
     *
     * @return the reader, which throws nothing but what {@code each} throws
     */
    public LogReader allEvents(long id, String node, String level, Sights sights) {
        PipelineStore.Run r = store.run(id).orElseThrow(() -> fail(Response.Status.NOT_FOUND, "No pipeline run " + id));
        if (level != null && !level.isBlank() && !List.of("info", "warn", "error").contains(level)) {
            throw new IllegalArgumentException("level: info, warn or error");
        }
        String min = level == null || level.isBlank() ? null : level;
        String which = node == null || node.isBlank() ? null : node;
        String withheld = withheld(r, sights);
        return each -> {
            int after = 0;
            while (true) {
                List<PipelineStore.Event> rows = store.events(id, which, min, after, MAX_EVENTS_PAGE);
                for (PipelineStore.Event e : rows) {
                    each.accept(event(e, withheld));
                }
                if (rows.size() < MAX_EVENTS_PAGE) {
                    return;
                }
                after = rows.getLast().seq();
            }
        };
    }

    /** Reads a run's log, handing each event to a consumer that may fail with an I/O error. */
    @FunctionalInterface
    public interface LogReader {
        void read(EventSink each) throws java.io.IOException;
    }

    /** Takes one event of a run's log. */
    @FunctionalInterface
    public interface EventSink {
        void accept(PipelineViews.RunEvent e) throws java.io.IOException;
    }

    /** A query number: a whole number at least 0, or {@code fallback} when not given; else a 400. */
    public static int count(String value, String name, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int n = Integer.parseInt(value.strip());
            if (n >= 0) {
                return n;
            }
        } catch (NumberFormatException e) {
            // said below
        }
        throw new IllegalArgumentException(name + ": a whole number, 0 or more");
    }

    /** One event as a line of the downloaded log: time, level, node, words and detail (or why it is held back). */
    public static String logLine(PipelineViews.RunEvent e) {
        String detail = e.detail() != null ? " (" + e.detail().replaceAll("\\R", " ") + ")"
            : e.withheld() != null ? " [detail held back: " + e.withheld() + "]" : "";
        return e.at() + "  " + String.format(Locale.ROOT, "%-5s", e.level().toUpperCase(Locale.ROOT)) + "  "
            + (e.node() == null ? "-" : e.node()) + "  " + e.text() + detail;
    }

    private static PipelineViews.RunEvent event(PipelineStore.Event e, String withheld) {
        boolean hide = e.detail() != null && withheld != null;
        return new PipelineViews.RunEvent(e.seq(), e.at(), e.nodeId(), e.level(), e.kind(), e.text(),
            hide ? null : e.detail(), hide ? withheld : null);
    }

    /** A node's stored quality as the API gives it; scores and gate results hold words and counts only. */
    private PipelineViews.NodeQualityView qualityView(PipelineStore.QualityRow q) {
        JsonNode detail = tree(q.predictedDetail());
        String reasons = detail != null && detail.has("reasons") ? detail.get("reasons").toString() : null;
        JsonNode prediction = null;
        if (detail != null && detail.isObject()) {
            ObjectNode p = ((ObjectNode) detail).deepCopy();
            p.remove("reasons");
            prediction = p;
        }
        return new PipelineViews.NodeQualityView(q.nodeId(), q.role(), q.assetUrn(),
            scoreView(q.measured(), q.measuredDimensions(), q.measuredReasons()),
            scoreView(q.predicted(), q.predictedDimensions(), reasons), q.note(), tree(q.gate()), prediction);
    }

    private PipelineViews.ScoreView scoreView(Double overall, String dimensions, String reasons) {
        if (overall == null) {
            return null;
        }
        Map<String, Double> dims = new LinkedHashMap<>();
        JsonNode d = tree(dimensions);
        if (d != null) {
            d.properties().forEach(e -> dims.put(e.getKey(), e.getValue().asDouble()));
        }
        List<String> words = new ArrayList<>();
        JsonNode r = tree(reasons);
        if (r != null) {
            r.forEach(n -> words.add(n.asText()));
        }
        return new PipelineViews.ScoreView(overall, dims, words);
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
            throw new IllegalArgumentException("urn: an asset reference, such as urn:kilnmark:sales-db:crm.customers");
        }
        List<PipelineViews.LineageEntry> up = new ArrayList<>();
        List<PipelineViews.LineageEntry> down = new ArrayList<>();
        for (PipelineStore.Lineage l : store.lineageOfAsset(urn)) {
            if (l.outputUrn().equals(urn)) {
                up.add(new PipelineViews.LineageEntry(l.inputUrn(), l.pipeline(), l.runId(), l.nodeId(), l.recordedAt()));
            } else {
                down.add(new PipelineViews.LineageEntry(l.outputUrn(), l.pipeline(), l.runId(), l.nodeId(),
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

    /** Recorded with a run's assets when it reads with a query, so its messages are withheld as for marked data. */
    static final String QUERY_READ = "kilnmark:query-read";

    static final String FOR_STEWARDS = "Stewards can see why: the message may hold values from the data.";
    static final String FOR_DESIGNERS = "Only designers who see unmasked values can see why: the message may hold values of "
        + "columns marked sensitive.";

    /**
     * Why the run's messages from the engine are held back from this caller, or null when they are
     * not: a failed run's error, its nodes' errors and the details of its notes, whatever its status.
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
        JsonNode recorded = tree(r.assets());
        if (recorded != null) {
            for (JsonNode n : recorded) {
                if (QUERY_READ.equals(n.asText())) {
                    return FOR_DESIGNERS; // a query may have read any column, marked or not
                }
            }
        }
        List<String> urns = assets(r);
        sights.load(urns); // a no-op for assets already loaded by prepare()
        return urns.stream().anyMatch(u -> !sights.marks(u).isEmpty()) ? FOR_DESIGNERS : null;
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
        JsonNode q = result == null ? null : result.path("quality");
        Double score = q != null && q.hasNonNull("score") ? q.get("score").asDouble() : null;
        Double threshold = q != null && q.hasNonNull("threshold") ? q.get("threshold").asDouble() : null;
        return new PipelineViews.RunSummary(r.id(), r.pipeline(), r.version(), r.status(), r.requestedBy(),
            r.requestedAt(), r.startedAt(), r.finishedAt(), r.cancelRequested(),
            withheld == null ? r.error() : "The run failed. " + withheld, rows, score, threshold);
    }

    // ---- the node catalogue ----

    /** Every node type with its ports and settings schema, by type. */
    public List<PipelineViews.NodeType> catalogue() {
        List<PipelineViews.NodeType> out = new ArrayList<>();
        for (NodeFactory f : nodes.all()) {
            List<String> inputs = f.inputPorts().stream().map(p -> p.endsWith("*") ? p.substring(0, p.length() - 1) : p)
                .toList();
            List<String> many = f.inputPorts().stream().filter(p -> p.endsWith("*"))
                .map(p -> p.substring(0, p.length() - 1)).toList();
            List<PipelineViews.SettingView> settings = new ArrayList<>();
            for (SettingSpec s : f.settings()) {
                settings.add(new PipelineViews.SettingView(s.key(), s.label(), s.kind().name().toLowerCase(Locale.ROOT),
                    s.required(), s.defaultValue(), s.help(), s.choices()));
            }
            out.add(new PipelineViews.NodeType(f.type(), f.kind().name().toLowerCase(Locale.ROOT), f.label(), f.help(),
                inputs, many, f.outputPorts(), settings,
                f instanceof WriteNodeFactory w ? w.writerSettingsByKind() : null, f.category()));
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

    /** What a run waiting for a free place on a source says: the source's name, never its settings. */
    public static String waitingFor(String source) {
        return "Waiting for a free place on source " + source + ". The run starts by itself when another run "
            + "using the source ends.";
    }

    /**
     * The sources a compiled pipeline reads or writes that limit runs at once: every saved source a
     * node's source setting names (read and write nodes), with its Most runs at once.
     */
    List<JobQueue.SourceLimit> sourceLimits(PipelineCompilerPort.Compilation c) {
        Map<Long, JobQueue.SourceLimit> out = new java.util.TreeMap<>();
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        for (PipelineCompilerPort.NodeOutline n : c.nodes()) {
            NodeFactory f = n.type() == null ? null : nodes.get(n.type()).orElse(null);
            if (f == null) {
                continue;
            }
            Map<String, Object> config = c.configs().getOrDefault(n.id(), Map.of());
            for (SettingSpec spec : f.settings()) {
                String name = spec.kind() == SettingSpec.Kind.SOURCE ? PipelineSources.text(config, spec.key()) : null;
                if (name != null) {
                    names.add(name);
                }
            }
        }
        for (String name : names) {
            sources.source(name).ifPresent(src -> {
                Integer max = io.kilnmark.server.sources.CommonSettings.maxRuns(src.settings());
                if (max != null) {
                    out.put(src.id(), new JobQueue.SourceLimit(src.id(), src.name(), max));
                }
            });
        }
        return List.copyOf(out.values());
    }

    /**
     * Takes a place on each limited source the run's version uses, on the job's lease, where they stay
     * until the lease closes. Null when every place was taken (or none was needed), else the first source
     * that had none free, with nothing held. A version that cannot be read or checked takes none: the run
     * then fails as it starts, saying why.
     */
    private JobQueue.SourceLimit takePlaces(PipelineStore.Run run, JobQueue.ScanLease lease) {
        List<JobQueue.SourceLimit> limits;
        try {
            PipelineStore.Version v = store.version(run.pipeline(), run.version()).orElse(null);
            Map<String, Object> parameters = jsonMap(run.parameters());
            Map<String, Object> variables = jsonMap(run.variables());
            if (v == null || parameters == null || variables == null) {
                return null;
            }
            limits = sourceLimits(compiler.check(v.yaml(), nodes, parameters, variables));
        } catch (RuntimeException e) {
            LOG.debugf("Could not tell which sources run %d uses before it starts; it takes no places", run.id());
            return null;
        }
        return limits.isEmpty() ? null : lease.takePlaces(limits).full();
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
        // A place on every source it reads or writes that limits runs at once, before it starts (ADR 0040).
        // A run asked to stop ends below without one.
        JobQueue.SourceLimit full = run.cancelRequested() || lease == null ? null : takePlaces(run, lease);
        if (full != null) {
            store.waiting(id, waitingFor(full.source()));
            return new ScanService.Result(ScanService.Outcome.WAITING, null);
        }
        if (!store.startRun(id)) {
            if (store.cancelRequested(id)) {
                store.finishRun(id, "cancelled", "Cancelled before it started", null);
            }
            return new ScanService.Result(ScanService.Outcome.SKIPPED, "Pipeline run " + id + " was cancelled");
        }
        RunContext context = new RunContext(run.pipeline(), id, run.requestedBy(), role(run.requestedRole()));
        RunLog log = new RunLog(store, id, context, json);
        log.server(null, "info", "run_started", "The run started: version " + run.version() + " of " + run.pipeline()
            + ", asked for by " + run.requestedBy(), null);
        log.flush();
        List<String> held = new ArrayList<>();
        List<RunQuality.NodeQuality> predicted = new ArrayList<>();
        boolean measuredSaved = false;
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
            Map<String, Object> runParameters = jsonMap(run.parameters());
            Map<String, Object> runVariables = jsonMap(run.variables());
            if (runParameters == null || runVariables == null) {
                // Never run on defaults in place of the values the run was started with.
                ended(log, "failed", "The pipeline could not start: the values it was started with could not be read");
                store.finishRun(id, "failed", "The pipeline could not start: the values it was started with could not "
                    + "be read", null);
                return new ScanService.Result(ScanService.Outcome.FINISHED, null);
            }
            // Setting a query read up asks the database to plan the query, and its errors may quote it: the
            // run is recorded as reading with a query before anything is set up (ADR 0028).
            if (PipelinePreviews.hasQueryRead(compiler.check(v.yaml(), nodes, runParameters, runVariables))) {
                if (!store.queriesChecked(run.pipeline(), run.version())) {
                    String why = "The pipeline could not start: this version reads with a query and was saved before "
                        + "queries were checked. A designer who may see unmasked values saves it again, then it runs.";
                    ended(log, "failed", why);
                    store.finishRun(id, "failed", why, null);
                    return new ScanService.Result(ScanService.Outcome.FINISHED, null);
                }
                store.setAssets(id, json.writeValueAsString(List.of(QUERY_READ)));
            }
            c = context.run(() -> compiler.compile(v.yaml(), nodes, runParameters, runVariables));
            if (!c.valid() || c.graph() == null) {
                ended(log, "failed", "The pipeline could not start: " + firstProblems(c.problems()));
                store.finishRun(id, "failed", context.scrub("The pipeline could not start: "
                    + firstProblems(c.problems())), null);
                return new ScanService.Result(ScanService.Outcome.FINISHED, null);
            }
            if (stopped(id, shutdown, c.graph())) {
                return new ScanService.Result(ScanService.Outcome.FINISHED, null);
            }
            // Each write's asset under the URN its connector worked out (for a prefixed S3 source, the prefix
            // and the path), as a scan of the source records it.
            log.gates(c.nodes().stream().filter(st -> "gate".equals(st.kind()) || "gate".equals(st.type()))
                .map(PipelineCompilerPort.NodeOutline::id).collect(java.util.stream.Collectors.toSet()));
            List<PipelineExecution.WriteTarget> writes = PipelineExecution.writes(c, context);
            java.util.Set<String> assets = new java.util.TreeSet<>(context.reads());
            assets.addAll(PipelineExecution.reads(c, context).values());
            writes.forEach(w -> assets.add(w.urn()));
            if (PipelinePreviews.hasQueryRead(c)) {
                assets.add(QUERY_READ); // its messages may quote any value the query read (ADR 0028)
            }
            store.setAssets(id, json.writeValueAsString(assets));
            // One run writes a table or file at a time: wait a while for another run writing it. The
            // locks are taken on the job's own connection, so a run never holds more than one.
            BooleanSupplier stop = () -> store.cancelRequested(id) || shutdown.getAsBoolean();
            for (String urn : writes.stream().map(PipelineExecution.WriteTarget::urn).distinct().sorted().toList()) {
                if (!JobQueue.waitFor(() -> lease.tryLockTarget(urn), config.pipelines().waitForTarget(), stop)) {
                    if (stopped(id, shutdown, c.graph())) {
                        return new ScanService.Result(ScanService.Outcome.FINISHED, null);
                    }
                    PipelineExecution.closeNodes(c.graph());
                    store.finishRun(id, "failed", "Another pipeline run is writing " + urn + ". Run this one again "
                        + "when it has finished.", null);
                    return new ScanService.Result(ScanService.Outcome.FINISHED, null);
                }
                held.add(urn);
            }
            markQueryWrites(c, writes, context); // the targets are this run's now, and no row has moved yet
            // What the catalogue predicts for each write and gate, stored before a row moves, so the run
            // shows its estimate beside what it measures (docs/design/data-quality.md section 8).
            predicted.addAll(predictRun(c, context));
            saveQuality(id, predicted, context);
            ran = true;
            PipelineExecution.Outcome o = execution.run(runner, c.graph(), writes, context,
                () -> store.cancelRequested(id), shutdown, log);
            store.saveNodeRuns(id, o.nodes());
            List<RunQuality.NodeQuality> measured = RunQuality.withPredicted(measure(c, writes, o), predicted,
                o.status());
            ObjectNode result = json.createObjectNode();
            ArrayNode read = result.putArray("read");
            java.util.Set<String> reads = new java.util.LinkedHashSet<>(PipelineExecution.reads(c, context).values());
            PipelineExecution.queryReads(c, context).values().forEach(reads::addAll);
            reads.forEach(read::add);
            ArrayNode notes = json.createArrayNode();
            ArrayNode written = result.putArray("written");
            long total = 0;
            for (PipelineExecution.WriteTarget w : writes) {
                Long rows = o.rowsIn().get(w.node());
                total += rows == null ? 0 : rows;
                boolean registered = false;
                // Marks follow the data for every write, whatever the run's outcome: a writer may have
                // written before a later node failed or the run was cancelled.
                carryMarks(w, c, context, notes);
                if (o.status().equals("succeeded")) {
                    // Writers profile what they write; one that could not says so on its ROW_COUNT, never by
                    // failing. The reason may quote the data, so it is the note's detail, held back via Sights.
                    String profileFailure = profileFailure(o.events(), w.urn());
                    if (profileFailure != null) {
                        profileFailureNote(notes, w, context.scrub(profileFailure));
                    }
                    String warning = writeWarning(o.events(), w.urn());
                    if (warning != null) {
                        note(notes, w.node() + ": " + context.scrub(warning), null);
                    }
                    registered = register(w, rows, o.events(), profileFailure == null, c, context, notes, lease,
                        shutdown);
                }
                ObjectNode n = written.addObject();
                n.put("node", w.node());
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
            Double threshold = threshold(v.yaml());
            Double score = RunQuality.lowestOutput(measured);
            ObjectNode q = result.putObject("quality");
            putScore(q, "score", score);
            putScore(q, "threshold", threshold);
            saveQuality(id, measured, context);
            measuredSaved = true;
            for (RunQuality.NodeQuality sq : measured) {
                log.gate(sq.node(), sq.gate());
            }
            for (JsonNode n : notes) {
                log.server(null, n.path("level").asText("warn"), "run_note", n.path("text").asText(), n.hasNonNull("detail")
                    ? n.get("detail").asText() : null);
            }
            ended(log, o.status(), o.error());
            store.finishRun(id, o.status(), o.error(), result.toString());
            RunQuality.NodeQuality lowest = RunQuality.lowestOutputNode(measured);
            finished(run.pipeline(), id, o.status(), score, threshold, lowest == null ? null : lowest.node());
            LOG.infof("Run %d of pipeline %s: %s", id, run.pipeline(), o.status());
        } catch (InterruptedException e) {
            Thread.interrupted(); // cleared so the run's record can be written
            if (!ran && c != null && c.graph() != null) {
                PipelineExecution.closeNodes(c.graph());
            }
            if (!measuredSaved && !predicted.isEmpty()) {
                saveQuality(id, RunQuality.withPredicted(List.of(), predicted, "failed"), context);
            }
            ended(log, "failed", "The worker running this pipeline was stopped before it started writing.");
            store.finishRun(id, "failed", "The worker running this pipeline was stopped before it started writing. "
                + "Run it again.", null);
            finished(run.pipeline(), id, "failed");
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            // The exception's own text may hold a source's secret: log it scrubbed, without the trace.
            LOG.errorf("Run %d of pipeline %s failed: %s", id, run.pipeline(), context.scrub(String.valueOf(e)));
            if (!ran && c != null && c.graph() != null) {
                PipelineExecution.closeNodes(c.graph()); // created, never handed to the runner
            }
            if (!measuredSaved && !predicted.isEmpty()) {
                // The predictions stay; they must not say the run has just started.
                saveQuality(id, RunQuality.withPredicted(List.of(), predicted, "failed"), context);
            }
            ended(log, "failed", "The run failed: " + e.getMessage());
            store.finishRun(id, "failed", context.scrub("The run failed: " + e.getMessage()), null);
            finished(run.pipeline(), id, "failed");
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
     * The log's last event: how the run ended, the error (which may quote the engine) as its detail.
     * Then writes what the log still holds.
     */
    private static void ended(RunLog log, String status, String error) {
        switch (status) {
            case "succeeded" -> log.server(null, "info", "run_ended", "The run succeeded", null);
            case "cancelled" -> log.server(null, "warn", "run_ended", "The run was cancelled", null);
            default -> log.server(null, "error", "run_ended", "The run failed", error);
        }
        log.finish();
    }

    /**
     * Whether the run was asked to stop, or its worker is stopping, before its nodes run; if so the
     * run gets its final status and {@code graph}'s nodes (when created) are closed.
     */
    private boolean stopped(long id, BooleanSupplier shutdown, PipelineGraph graph) {
        boolean cancelled = store.cancelRequested(id);
        if (!cancelled && !shutdown.getAsBoolean()) {
            return false;
        }
        if (graph != null) {
            PipelineExecution.closeNodes(graph);
        }
        store.finishRun(id, cancelled ? "cancelled" : "failed", cancelled ? "Cancelled before it started"
            : "The worker running this pipeline was stopped before it started. Run it again.", null);
        return true;
    }

    /** Tells of a run that finished without measuring anything (it failed before its nodes ran). */
    void finished(String pipeline, long id, String status) {
        finished(pipeline, id, status, null, null, null);
    }

    /**
     * Tells of a finished run: a failure, or a success whose lowest output score (what it wrote,
     * as measured) is below the pipeline's {@code quality.min_score}, which tells
     * {@code pipeline_low_quality} (once per run; the publisher dedupes). {@code output} is the write
     * node with that score, for the notification's words.
     */
    void finished(String pipeline, long id, String status, Double score, Double threshold, String output) {
        if (notifications != null) {
            notifications.pipelineRunFinished(pipeline, id, status, score, threshold, output, "#/pipeline-runs/" + id);
        }
    }

    /** The pipeline's {@code quality.min_score} in this YAML, or null. */
    static Double threshold(String yaml) {
        try {
            io.kilnmark.kiln.yaml.PipelineDefinition d = io.kilnmark.kiln.yaml.PipelineDefinition.parse(yaml).definition();
            return d == null || d.quality() == null ? null : d.quality().minScore();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void putScore(ObjectNode n, String key, Double v) {
        if (v == null) {
            n.putNull(key);
        } else {
            n.put(key, v);
        }
    }

    /**
     * The run's measured quality: each gate's outcome (also when it stopped the run), and, when the
     * run succeeded, each write's score from the profile its writer made while writing.
     */
    List<RunQuality.NodeQuality> measure(PipelineCompilerPort.Compilation c, List<PipelineExecution.WriteTarget> writes,
                                         PipelineExecution.Outcome o) {
        List<RunQuality.NodeQuality> out = new ArrayList<>();
        try {
            for (io.kilnmark.kiln.graph.GraphNode s : c.graph().nodes()) {
                if (s.node() instanceof QualityGate g && g.outcome() != null) {
                    out.add(RunQuality.gate(s.id(), g.outcome()));
                }
            }
            if (o.status().equals("succeeded")) {
                for (PipelineExecution.WriteTarget w : writes) {
                    out.add(RunQuality.output(w, o.events(), scans::profileJson, json));
                }
            }
        } catch (RuntimeException e) {
            LOG.warnf("Could not measure the quality of a run: %s", e.getMessage());
        }
        return out;
    }

    private void saveQuality(long id, List<RunQuality.NodeQuality> nodes, RunContext context) {
        if (nodes.isEmpty()) {
            return;
        }
        try {
            List<PipelineStore.QualityRow> rows = new ArrayList<>();
            for (RunQuality.NodeQuality q : nodes) {
                RunQuality.Score m = q.measured();
                RunQuality.Score p = q.predicted();
                String gate = q.gate() == null ? null : context.scrub(json.writeValueAsString(q.gate()));
                rows.add(new PipelineStore.QualityRow(q.node(), q.role(), q.asset(), m == null ? null : m.overall(),
                    m == null ? null : json.writeValueAsString(m.dimensions()),
                    m == null ? null : json.writeValueAsString(m.reasons()), p == null ? null : p.overall(),
                    p == null ? null : json.writeValueAsString(p.dimensions()), q.note(), gate,
                    q.predictedDetail() == null ? null : context.scrub(q.predictedDetail())));
            }
            store.saveQuality(id, rows);
        } catch (JsonProcessingException | RuntimeException e) {
            LOG.warnf("Could not store the quality of run %d: %s", id, context.scrub(e.getMessage()));
        }
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
                note(notes, w.node() + ": source " + w.source() + " no longer exists, so what it wrote is not catalogued",
                    null);
                return false;
            }
            store.saveLineage(context.pipeline(), context.runId(), w.node(), w.inputs(), w.urn());
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
                note(notes, w.node() + ": a scan of " + w.source() + " was running, so " + w.target() + " is not in "
                    + "the catalogue yet. Scan the source to catalogue the written table or file", null);
                return false;
            }
            try {
                catalogue(s, w, rows, events, profiled, c, context, notes);
                carryToCatalogued(w, c, context);
            } finally {
                lease.unlockSource(s.id());
            }
            return true;
        } catch (RuntimeException e) {
            LOG.warnf("Could not catalogue what node %s of pipeline %s wrote: %s", w.node(), context.pipeline(),
                context.scrub(String.valueOf(e)));
            note(notes, w.node() + ": what it wrote could not be catalogued", context.scrub(e.getMessage()));
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

    /**
     * What a writer that succeeded left for a person to attend to, from its {@code ROW_COUNT} event
     * ({@code write_warning}: a table it could not tidy away, say), in the writer's own words, which
     * name the asset and never quote the data; null when there is none.
     */
    static String writeWarning(List<MetadataEvent> events, String urn) {
        for (MetadataEvent e : events) {
            if (e.kind() == MetadataEvent.Kind.ROW_COUNT && e.assetUrn().equals(urn)) {
                String warning = e.attributes().get(WriteReport.WRITE_WARNING);
                if (warning != null && !warning.isBlank()) {
                    return warning;
                }
            }
        }
        return null;
    }

    /** The run's note for a write whose profile failed: the server's words, the writer's reason as its detail. */
    static void profileFailureNote(ArrayNode notes, PipelineExecution.WriteTarget w, String reason) {
        note(notes, w.node() + ": what it wrote could not be profiled; scan " + w.source() + " to profile "
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
                || e.attributes().containsKey(WriteReport.PROFILE_ERROR)
                || e.attributes().containsKey(WriteReport.WRITE_WARNING))) {
                Map<String, String> a = new LinkedHashMap<>(e.attributes());
                a.remove(WriteReport.PROFILED);
                a.remove(WriteReport.PROFILE_ERROR);
                a.remove(WriteReport.WRITE_WARNING);
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

    /** A note that only informs (what an append did): the run's log shows it as information, not a warning. */
    private static void infoNote(ArrayNode notes, String text, String detail) {
        note(notes, text, detail);
        ((ObjectNode) notes.get(notes.size() - 1)).put("level", "info");
    }

    /** Marks of sensitive columns, for marks that follow the data (unset in tests that build this by hand). */
    @jakarta.inject.Inject
    io.kilnmark.server.quality.QualityStore qualityStore;

    /**
     * Marks follow the data: every column this write writes that was made from a column marked
     * sensitive upstream (passed on, renamed, looked up or worked out from it; see
     * {@link NodePreview#marked}) is marked on the written asset, before it is catalogued, so it is
     * masked there for everyone but designers as it was where it came from. When the written columns
     * cannot be told before the run, those catalogued are used.
     */
    private void carryMarks(PipelineExecution.WriteTarget w, PipelineCompilerPort.Compilation c, RunContext context,
                            ArrayNode notes) {
        if (qualityStore == null) {
            return;
        }
        Map<String, java.util.Set<String>> readMarks = PipelinePreviews.readMarks(c, context, qualityStore::sensitive);
        if (readMarks.values().stream().allMatch(java.util.Set::isEmpty)) {
            return;
        }
        NodePreview.Hidden marked = NodePreview.marked(c, readMarks, w.node());
        if (!marked.any()) {
            return;
        }
        List<String> written = writtenColumns(w, c);
        java.util.Set<String> carried = new java.util.LinkedHashSet<>();
        for (String name : written) {
            if (marked.hides(name)) {
                carried.add(name);
            }
        }
        if (written.isEmpty()) {
            // not known before the run: the names marked so far (marks compare ignoring case), or, when
            // what reached the write could not be followed, every column; the catalogued columns are
            // marked by name too once the write is catalogued (carryToCatalogued)
            carried.addAll(marked.all() ? List.of(io.kilnmark.server.quality.QualityStore.EVERY_COLUMN)
                : marked.names());
        }
        String from = queryFeeds(c, w) ? QUERY_READ : readMarks.entrySet().stream()
            .filter(e -> !e.getValue().isEmpty()).map(Map.Entry::getKey).sorted().findFirst()
            .map(id -> PipelinePreviews.readUrnsOf(c.configs().getOrDefault(id, Map.of()), context).stream()
                .findFirst().orElse(id)).orElse("");
        qualityStore.carry(w.urn(), carried, from, context.pipeline(), context.runId());
        if (marked.all() && written.isEmpty()) {
            note(notes, w.node() + (queryFeeds(c, w)
                ? ": it wrote rows a read's query read, so every column it wrote is marked sensitive"
                : ": it wrote columns made from columns marked sensitive in ways Kilnmark could not follow, so every "
                    + "column it wrote is marked sensitive"), null);
        }
    }

    /** Whether a read's query feeds {@code w}: what it reads reaches the write, marked on every column. */
    private static boolean queryFeeds(PipelineCompilerPort.Compilation c, PipelineExecution.WriteTarget w) {
        Map<String, java.util.Set<String>> queries = new java.util.HashMap<>();
        for (PipelineCompilerPort.NodeOutline s : c.nodes()) {
            if (ReadNodeFactory.TYPE.equals(s.type()) && ReadNodeFactory.isQuery(c.configs().getOrDefault(s.id(),
                Map.of()))) {
                queries.put(s.id(), java.util.Set.of(io.kilnmark.server.quality.QualityStore.EVERY_COLUMN));
            }
        }
        return !queries.isEmpty() && NodePreview.marked(c, queries, w.node()).all();
    }

    /**
     * Marks every column of each write that a read's query feeds, before any row moves (ADR 0028):
     * every column a query reads is marked, so this is known before the run, and the rows it writes
     * stay masked even if the run never reaches its end, where {@link #carryMarks} records the rest.
     */
    private void markQueryWrites(PipelineCompilerPort.Compilation c, List<PipelineExecution.WriteTarget> writes,
                                 RunContext context) {
        if (qualityStore == null || !PipelinePreviews.hasQueryRead(c)) {
            return;
        }
        for (PipelineExecution.WriteTarget w : writes) {
            if (queryFeeds(c, w)) {
                qualityStore.carry(w.urn(), List.of(io.kilnmark.server.quality.QualityStore.EVERY_COLUMN), QUERY_READ,
                    context.pipeline(), context.runId());
            }
        }
    }

    /**
     * The second half of {@link #carryMarks}, once the write is catalogued: when the written columns
     * could not be told before the run, the catalogued ones made from marked columns are marked (every
     * one, when what reached the write could not be followed).
     */
    private void carryToCatalogued(PipelineExecution.WriteTarget w, PipelineCompilerPort.Compilation c,
                                   RunContext context) {
        if (qualityStore == null || !writtenColumns(w, c).isEmpty()) {
            return;
        }
        Map<String, java.util.Set<String>> readMarks = PipelinePreviews.readMarks(c, context, qualityStore::sensitive);
        NodePreview.Hidden marked = NodePreview.marked(c, readMarks, w.node());
        if (!marked.any()) {
            return;
        }
        List<String> fields = graph.asset(w.urn()).map(a -> a.fields().stream().map(f -> f.name()).toList())
            .orElse(List.of());
        java.util.Set<String> carried = new java.util.LinkedHashSet<>();
        fields.stream().filter(marked::hides).forEach(carried::add);
        qualityStore.carry(w.urn(), carried, "", context.pipeline(), context.runId());
    }

    /** The columns arriving at a write node, as the check knew them; empty when not known. */
    private static List<String> writtenColumns(PipelineExecution.WriteTarget w, PipelineCompilerPort.Compilation c) {
        for (PipelineCompilerPort.EdgeOutline e : c.edges()) {
            if (!e.toNode().equals(w.node())) {
                continue;
            }
            for (PipelineCompilerPort.NodeOutline n : c.nodes()) {
                if (n.id().equals(e.fromNode()) && n.columns() != null && n.columns().get(e.fromPort()) != null) {
                    return n.columns().get(e.fromPort()).stream().map(PipelineCompilerPort.Column::name).toList();
                }
            }
        }
        return List.of();
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
            + context.pipeline() + " (run " + context.runId() + ", node " + w.node() + ")");
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
            infoNote(notes, w.node() + ": the " + w.mode() + " added " + (rows == null ? "rows" : rows + " rows") + " to "
                + w.target() + "; its columns, row count and profile stay as its last scan found them. Scan "
                + w.source() + " to refresh them", null);
            return;
        }
        // The connector's own view of what was written gives its kind, container (for a file in an S3
        // source: bucket, prefix and folder), name and key, as a scan records them.
        AssetInfo info = describe(w, context);
        List<Records.Field> fields = schema == null ? List.of() : schemaFields(schema);
        if (fields.isEmpty()) {
            fields = info != null && !info.fields().isEmpty() ? fields(info) : fieldsFromColumns(w, c, mine);
        }
        String path = assetPath(w);
        boolean files = sources.connector(s.kind()).map(k -> "files".equals(k.category())).orElse(false);
        String kind = info != null ? info.kind() : files ? "file" : "table";
        String container = info != null && info.container() != null ? info.container() : container(path);
        String name = info != null && info.name() != null ? info.name() : lastPart(path);
        String description = info != null && info.description() != null ? info.description()
            : known == null ? null : known.description();
        // A table created or replaced, or a file, has no key but the one its connector now reports; an
        // upsert into a table not catalogued yet has its key.
        List<String> key = info != null ? info.primaryKey() : appended ? w.key() : List.of();
        graph.saveAsset(s.id(), recordRun, new Records.Asset(w.urn(), kind, container, name, description, key),
            fields);
        if (appended) {
            infoNote(notes, w.node() + ": the " + w.mode() + " added rows to " + w.target() + ", which Kilnmark had not "
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
            note(notes, w.node() + ": " + failed + " column profiles could not be stored; scan " + w.source()
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
            PipelineSources.Resolved r = sources.resolve(w.node(), w.source(), context);
            return r.connector().asset(r.config(), assetPath(w));
        } catch (NodeException | io.kilnmark.kiln.connect.ConnectorException | RuntimeException e) {
            LOG.debugf("Could not describe %s after writing it: %s", w.urn(), context.scrub(e.getMessage()));
            return null;
        }
    }

    /** The written asset's path as the catalogue records it: its URN's path (prefix and escapes included). */
    private static String assetPath(PipelineExecution.WriteTarget w) {
        try {
            return io.kilnmark.graph.Urn.parse(w.urn()).path();
        } catch (IllegalArgumentException e) {
            return w.target();
        }
    }

    private static List<Records.Field> fields(AssetInfo a) {
        return a.fields().stream().map(f -> new Records.Field(f.name(), f.position(), f.sourceType(), f.kind(),
            f.nullable(), f.description())).toList();
    }

    /** The write's columns as validation worked them out, else as the profile node saw them. */
    private static List<Records.Field> fieldsFromColumns(PipelineExecution.WriteTarget w,
                                                         PipelineCompilerPort.Compilation c,
                                                         List<MetadataEvent> events) {
        for (PipelineCompilerPort.EdgeOutline e : c.edges()) {
            if (!e.toNode().equals(w.node())) {
                continue;
            }
            for (PipelineCompilerPort.NodeOutline s : c.nodes()) {
                if (s.id().equals(e.fromNode()) && s.columns().get(e.fromPort()) != null) {
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

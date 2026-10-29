package io.kilnmark.server.flows;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kilnmark.kiln.flow.FlowEngine;
import io.kilnmark.kiln.yaml.FlowCondition;
import io.kilnmark.kiln.yaml.FlowDefinition;
import io.kilnmark.kiln.yaml.Position;
import io.kilnmark.kiln.yaml.Problem;
import io.kilnmark.server.api.ApiError;
import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.jobs.JobQueue;
import io.kilnmark.server.pipelines.PipelineService;
import io.kilnmark.server.pipelines.PipelineStore;
import io.kilnmark.server.scan.ScanService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Flows (docs/design/flows.md): saving them with every version, checking their YAML, queueing runs
 * on the job queue and, on a worker, deciding which flow node runs next. A flow node that runs a
 * pipeline queues an ordinary pipeline run, so any worker may run it, as the person who started the
 * flow and with their role; the flow only watches those runs and follows the connections.
 */
@ApplicationScoped
public class FlowService {

    private static final Logger LOG = Logger.getLogger(FlowService.class);

    static final int MAX_YAML_CHARS = 262_144;
    static final int MAX_MESSAGE = 500;

    private final FlowStore store;
    private final PipelineService pipelines;
    private final PipelineStore pipelineStore;
    private final ObjectMapper json;

    /** How often a running flow looks at its nodes. */
    volatile long pollMillis = 500;

    @Inject
    public FlowService(FlowStore store, PipelineService pipelines, PipelineStore pipelineStore, ObjectMapper json) {
        this.store = store;
        this.pipelines = pipelines;
        this.pipelineStore = pipelineStore;
        this.json = json;
    }

    private static WebApplicationException fail(Response.Status status, String message) {
        return new WebApplicationException(Response.status(status).type(MediaType.APPLICATION_JSON)
            .entity(new ApiError(message)).build());
    }

    private static void requireSteward(AccessRole role, String what) {
        if (role.compareTo(AccessRole.STEWARD) < 0) {
            throw fail(Response.Status.FORBIDDEN, what + " takes the steward role");
        }
    }

    // ---- checking and saving ----

    /** What checking a flow's YAML found. {@code name} is null when the YAML gave none. */
    public record Validation(boolean valid, String name, List<Problem> problems) {
    }

    /**
     * Checks a flow's YAML: its shape, connections and conditions, and that every pipeline it runs exists
     * (and every version it names).
     */
    public Validation validate(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            throw new IllegalArgumentException("yaml: send the flow's YAML");
        }
        if (yaml.length() > MAX_YAML_CHARS) {
            throw new IllegalArgumentException("yaml: the flow is too large, " + yaml.length() + " characters and at "
                + "most " + MAX_YAML_CHARS + " are allowed");
        }
        FlowDefinition.Parsed parsed = FlowDefinition.parse(yaml);
        List<Problem> problems = new ArrayList<>(parsed.problems());
        FlowDefinition def = parsed.definition();
        if (def != null) {
            for (FlowDefinition.Node s : def.nodes()) {
                if (!FlowDefinition.RUN_PIPELINE.equals(s.type()) || s.pipeline() == null) {
                    continue;
                }
                Position at = s.at("pipeline");
                Optional<PipelineStore.Pipeline> p = pipelineStore.pipeline(s.pipeline());
                if (p.isEmpty()) {
                    problems.add(new Problem(at.line(), at.column(), s.id(), "Node " + s.id() + " runs the pipeline '"
                        + s.pipeline() + "', which does not exist: save it under Pipelines first"));
                } else if (s.version() != null && s.version() > p.get().latestVersion()) {
                    Position v = s.at("version");
                    problems.add(new Problem(v.line(), v.column(), s.id(), "Node " + s.id() + " runs version "
                        + s.version() + " of " + s.pipeline() + ", which has only " + p.get().latestVersion()));
                }
            }
        }
        problems.sort(Problem.BY_POSITION);
        return new Validation(problems.isEmpty() && def != null, def == null ? null : def.name(), problems);
    }

    /**
     * Saves a flow's YAML as its next version, creating the flow when it is new. A flow with problems
     * is saved as a draft that cannot run. The flow's name is the YAML's and never changes.
     *
     * @param role     the caller's role: stewards and admins save flows
     * @param expected the latest version the caller read, or null (409 if another has been saved since)
     */
    public FlowStore.Flow save(String yaml, String message, String by, AccessRole role, Integer expected) {
        requireSteward(role, "Saving a flow");
        Validation check = validate(yaml);
        if (check.name() == null || !FlowDefinition.NAME.matcher(check.name()).matches()) {
            throw new IllegalArgumentException("yaml: the flow needs a name, such as name: nightly-customers");
        }
        if (message != null && message.length() > MAX_MESSAGE) {
            throw new IllegalArgumentException("message: at most " + MAX_MESSAGE + " characters");
        }
        FlowDefinition.Parsed parsed = FlowDefinition.parse(yaml);
        String description = parsed.definition() == null ? null : parsed.definition().description();
        if (description != null && description.length() > 2000) {
            throw new IllegalArgumentException("description: at most 2000 characters");
        }
        String problems = problemsJson(check.problems());
        boolean exists = store.find(check.name()).isPresent();
        try {
            return store.save(check.name(), expected, !exists, check.name(), description, yaml, message, by,
                check.valid(), problems);
        } catch (FlowStore.Exists e) {
            throw fail(Response.Status.CONFLICT, "A flow called " + check.name() + " exists already");
        } catch (FlowStore.Changed e) {
            throw fail(Response.Status.CONFLICT, "Someone saved another version of " + check.name()
                + " since you opened it. Reload it, then make your change again.");
        }
    }

    private String problemsJson(List<Problem> problems) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Problem p : problems) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("line", p.line());
            m.put("column", p.column());
            m.put("node", p.nodeId());
            m.put("message", p.message());
            out.add(m);
        }
        try {
            return json.writeValueAsString(out);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    /** Marks failed the runs whose worker stopped; called by workers. */
    public int failOrphanedRuns() {
        return store.failOrphanedRuns();
    }

    public Optional<FlowStore.Flow> find(String name) {
        return store.find(name);
    }

    public Optional<FlowStore.Version> version(String name, int n) {
        return store.version(name, n);
    }

    // ---- runs ----

    /**
     * Queues a run of one version (the latest by default). The version is checked again now, since
     * pipelines may have changed since it was saved. {@code parameters} are the values for the flow's
     * parameters; the others take their defaults.
     */
    public FlowStore.Run queueRun(String name, Integer version, Map<String, Object> parameters, String by,
                                  AccessRole role) {
        requireSteward(role, "Running a flow");
        FlowStore.Flow f = store.find(name).orElseThrow(() -> fail(Response.Status.NOT_FOUND, "No flow called " + name));
        int n = version == null ? f.latestVersion() : version;
        FlowStore.Version v = store.version(name, n).orElseThrow(() -> fail(Response.Status.NOT_FOUND,
            name + " has no version " + n));
        Validation check = validate(v.yaml());
        if (!check.valid()) {
            List<Problem> first = check.problems().subList(0, Math.min(3, check.problems().size()));
            throw fail(Response.Status.CONFLICT, "Version " + n + " of " + name + " cannot run until its problems are "
                + "fixed: " + String.join("; ", first.stream().map(p -> "line " + p.line() + ": " + p.message()).toList()));
        }
        FlowDefinition def = FlowDefinition.parse(v.yaml()).definition();
        Map<String, Object> resolved = resolveParameters(def, parameters);
        try {
            return store.queueRun(name, n, by, role.id(), json.writeValueAsString(resolved));
        } catch (FlowStore.Busy e) {
            throw fail(Response.Status.CONFLICT, "A run of " + name + " is already queued or running. Wait for it to "
                + "finish, or cancel it, then run the flow again.");
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("parameters: could not be read");
        }
    }

    /** The values a run starts with: each parameter as given or by default, checked against its type. */
    static Map<String, Object> resolveParameters(FlowDefinition def, Map<String, Object> given) {
        Map<String, Object> in = given == null ? Map.of() : given;
        for (String key : in.keySet()) {
            if (def.parameter(key) == null) {
                throw new IllegalArgumentException("parameters: " + def.name() + " has no parameter '"
                    + (key.length() > 80 ? key.substring(0, 80) : key) + "'"
                    + (def.parameters().isEmpty() ? "" : "; it takes "
                    + String.join(", ", def.parameters().stream().map(FlowDefinition.Parameter::name).toList())));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (FlowDefinition.Parameter p : def.parameters()) {
            Object v = in.containsKey(p.name()) ? in.get(p.name()) : p.defaultValue();
            if (v == null) {
                throw new IllegalArgumentException("parameters: " + p.name() + " needs a value (it has no default)");
            }
            out.put(p.name(), typed(p, v));
        }
        return out;
    }

    private static Object typed(FlowDefinition.Parameter p, Object v) {
        boolean fits = switch (p.type()) {
            case TEXT -> v instanceof String s && s.length() <= 1_000;
            case NUMBER -> v instanceof Number n && !Double.isNaN(n.doubleValue()) && !Double.isInfinite(n.doubleValue());
            case BOOLEAN -> v instanceof Boolean;
        };
        if (!fits) {
            throw new IllegalArgumentException("parameters: " + p.name() + " must be a "
                + p.type().name().toLowerCase() + (p.type() == FlowCondition.Type.TEXT ? " of at most 1,000 characters" : ""));
        }
        return p.type() == FlowCondition.Type.NUMBER ? (Object) ((Number) v).doubleValue() : v;
    }

    public Optional<FlowStore.Run> run(long id) {
        return store.run(id);
    }

    public List<FlowStore.NodeRun> nodeRuns(long id) {
        return store.nodeRuns(id);
    }

    public List<FlowStore.Run> runs(String flow, int limit) {
        return store.runs(flow, Math.max(1, Math.min(limit, 200)));
    }

    /** Asks a run to stop: a queued run is cancelled at once, a running one within a moment. */
    public FlowStore.Run cancel(long id, String by, AccessRole role) {
        requireSteward(role, "Cancelling a flow run");
        FlowStore.Run before = store.run(id).orElseThrow(() -> fail(Response.Status.NOT_FOUND, "No flow run " + id));
        if (before.finished()) {
            throw fail(Response.Status.CONFLICT, "Run " + id + " has already finished (" + before.status() + ")");
        }
        FlowStore.Run r = store.requestCancel(id, by).orElseThrow(() -> fail(Response.Status.NOT_FOUND,
            "No flow run " + id));
        if (r.status().equals("running") && !store.jobActive(id)) {
            // Its worker stopped without finishing it: nothing will notice the request.
            store.finishRun(id, "cancelled", "Cancelled by " + by + "; the worker running it had stopped");
            r = store.run(id).orElse(r);
        }
        LOG.infof("Flow run %d of %s cancelled by %s", id, r.flow(), by);
        return r;
    }

    // ---- on a worker ----

    /** Runs the flow run behind job {@code jobId}; called by a worker. */
    public ScanService.Result execute(long jobId, JobQueue.ScanLease lease, BooleanSupplier shutdown) {
        store.failOrphanedRuns();
        Optional<Long> runId = store.runForJob(jobId);
        if (runId.isEmpty()) {
            return new ScanService.Result(ScanService.Outcome.SKIPPED, "Job " + jobId + " names no flow run");
        }
        long id = runId.get();
        Optional<FlowStore.Run> found = store.run(id);
        if (found.isEmpty()) {
            return new ScanService.Result(ScanService.Outcome.SKIPPED, "Flow run " + id + " no longer exists");
        }
        FlowStore.Run run = found.get();
        if (run.finished()) {
            return new ScanService.Result(ScanService.Outcome.SKIPPED, "Flow run " + id + " had already finished");
        }
        if (!store.startRun(id)) {
            if (store.cancelRequested(id)) {
                store.finishRun(id, "cancelled", "Cancelled before it started");
            }
            return new ScanService.Result(ScanService.Outcome.SKIPPED, "Flow run " + id + " was cancelled");
        }
        try {
            new Execution(run, shutdown).run();
        } catch (RuntimeException e) {
            // Not the exception's text: it could quote anything. The class is enough for the log.
            LOG.errorf("Flow run %d failed with %s", id, e.getClass().getName());
            store.finishRun(id, "failed", "The flow could not be run to the end: an internal error. "
                + "Look at the server's log.");
        }
        return new ScanService.Result(ScanService.Outcome.FINISHED, null);
    }

    /** One flow run on a worker: the engine, and the pipeline runs its nodes started. */
    private final class Execution {
        private final FlowStore.Run run;
        private final BooleanSupplier shutdown;
        private final Map<String, Instant> started = new HashMap<>();
        private final Map<String, Long> pipelineRuns = new HashMap<>();
        private final Map<String, Instant> waitingSince = new HashMap<>();
        private final Set<String> cancelAsked = new HashSet<>();
        private boolean nodeWasCancelled;

        Execution(FlowStore.Run run, BooleanSupplier shutdown) {
            this.run = run;
            this.shutdown = shutdown;
        }

        void run() {
            FlowStore.Version version = store.version(run.flow(), run.version()).orElse(null);
            FlowDefinition.Parsed parsed = version == null ? null : FlowDefinition.parse(version.yaml());
            FlowDefinition def = parsed == null ? null : parsed.definition();
            if (def == null || !parsed.problems().isEmpty()) {
                store.finishRun(run.id(), "failed", "The flow could not start: version " + run.version() + " of "
                    + run.flow() + " no longer reads as a flow");
                return;
            }
            Map<String, Object> params = readParameters(run.parameters());
            List<String> ids = def.nodes().stream().map(FlowDefinition.Node::id).toList();
            FlowEngine engine = new FlowEngine(ids, def.engineConnections(), params);
            AccessRole role = role(run.requestedRole());
            boolean cancelling = false;
            while (true) {
                if (shutdown.getAsBoolean()) {
                    for (String s : engine.running()) {
                        Long prId = pipelineRuns.get(s);
                        if (prId != null) {
                            try {
                                pipelines.cancel(prId, run.requestedBy());
                            } catch (RuntimeException e) {
                                // already finished
                            }
                        }
                        save(def, s, "failed", prId, Instant.now(), "The worker running this flow stopped", Map.of());
                    }
                    recordSkipped(def);
                    store.finishRun(run.id(), "failed", "The worker running this flow was stopped before it finished. "
                        + "Run it again; check what the pipelines it had started had written first.");
                    return;
                }
                if (!cancelling && store.cancelRequested(run.id())) {
                    cancelling = true;
                    engine.cancel();
                }
                if (cancelling) {
                    cancelRunning(engine, def, role);
                }
                startReady(def, engine, role);
                for (String node : engine.running()) {
                    poll(def, engine, node, role, cancelling);
                }
                if (!cancelling && engine.running().isEmpty()) {
                    // Nodes that just ended may have made others ready; look once more before deciding.
                    startReady(def, engine, role);
                }
                if (!cancelling && nodeWasCancelled && !engine.halted()) {
                    // Someone cancelled a node's pipeline run: the whole flow stops.
                    cancelling = true;
                    engine.cancel();
                }
                if (engine.halted()) {
                    cancelRunning(engine, def, role); // a stop node ends what is still running
                }
                if (engine.done()) {
                    break;
                }
                pause();
            }
            finish(def, engine);
        }

        private void pause() {
            try {
                Thread.sleep(pollMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted");
            }
        }

        /**
         * Starts what is ready. Nodes that became ready in the same moment as a stop node are not started:
         * the stop has already ended the flow.
         */
        private void startReady(FlowDefinition def, FlowEngine engine, AccessRole role) {
            for (String node : engine.ready()) {
                if (engine.halted() && !FlowDefinition.STOP.equals(def.node(node).type())) {
                    end(def, engine, node, FlowEngine.State.CANCELLED, null, "Not started: the flow was stopped by a "
                        + "stop node", Map.of());
                } else {
                    begin(def, engine, node, role);
                }
            }
        }

        private void begin(FlowDefinition def, FlowEngine engine, String node, AccessRole role) {
            FlowDefinition.Node s = def.node(node);
            Instant now = Instant.now();
            started.put(node, now);
            if (FlowDefinition.STOP.equals(s.type())) {
                boolean failed = "failed".equals(s.status());
                engine.halt(failed, s.message() == null ? "" : s.message());
                engine.finish(node, FlowEngine.State.SUCCEEDED, Map.of());
                save(def, node, "succeeded", null, now, s.message(), Map.of());
                return;
            }
            save(def, node, "running", null, null, null, Map.of());
            tryQueue(def, engine, s, role);
        }

        /** Queues the node's pipeline run, or notes that it must wait for the pipeline's current one. */
        private void tryQueue(FlowDefinition def, FlowEngine engine, FlowDefinition.Node s, AccessRole role) {
            Optional<PipelineStore.Run> existing = pipelineStore.flowNodeRun(run.id(), s.id());
            if (existing.isPresent()) {
                pipelineRuns.put(s.id(), existing.get().id());
                return;
            }
            try {
                PipelineStore.Run queued = pipelines.queueRunForFlow(s.pipeline(), s.version(), run.requestedBy(), role,
                    run.id(), s.id());
                pipelineRuns.put(s.id(), queued.id());
                waitingSince.remove(s.id());
                save(def, s.id(), "running", queued.id(), null, null, Map.of());
            } catch (PipelineStore.Busy busy) {
                boolean first = !waitingSince.containsKey(s.id());
                Instant since = waitingSince.computeIfAbsent(s.id(), k -> Instant.now());
                if (Duration.between(since, Instant.now()).compareTo(def.queueTimeout()) > 0) {
                    waitingSince.remove(s.id());
                    end(def, engine, s.id(), FlowEngine.State.FAILED, null, "Waited " + pretty(def.queueTimeout())
                        + " for " + s.pipeline() + " to finish its current run, and gave up", Map.of());
                } else if (first) {
                    save(def, s.id(), "running", null, null, "Waiting for " + s.pipeline()
                        + " to finish its current run", Map.of());
                }
            } catch (WebApplicationException e) {
                // Why can name sources and fields the flow's readers may not see: the pipeline's own page says.
                end(def, engine, s.id(), FlowEngine.State.FAILED, null, "The pipeline " + s.pipeline()
                    + " could not be run; its page says why", Map.of());
            }
        }

        private void poll(FlowDefinition def, FlowEngine engine, String node, AccessRole role, boolean cancelling) {
            if (engine.state(node) != FlowEngine.State.RUNNING) {
                return;
            }
            FlowDefinition.Node s = def.node(node);
            Long prId = pipelineRuns.get(node);
            if (prId == null) {
                if (cancelling || engine.halted()) {
                    end(def, engine, node, FlowEngine.State.CANCELLED, null, "Not started: the flow was stopped",
                        Map.of());
                } else {
                    tryQueue(def, engine, s, role);
                }
                return;
            }
            Optional<PipelineStore.Run> pr = pipelineStore.run(prId);
            if (pr.isPresent() && !pr.get().finished()) {
                return;
            }
            if (pr.isEmpty()) {
                end(def, engine, node, FlowEngine.State.FAILED, prId, "The pipeline run no longer exists", Map.of());
                return;
            }
            Optional<PipelineStore.FlowReport> report = pipelineStore.flowReport(prId);
            Map<String, Object> reported = new LinkedHashMap<>();
            report.ifPresent(r -> {
                reported.put("score", r.score());
                reported.put("quarantined_share", r.quarantinedShare());
                reported.put("rows_written", r.rowsWritten() == null ? null : r.rowsWritten().doubleValue());
                reported.put("duration_s", r.durationSeconds());
            });
            String status = pr.get().status();
            // Why a pipeline run failed may quote data; its own page shows it to those who may see it.
            switch (status) {
                case "succeeded" -> end(def, engine, node, FlowEngine.State.SUCCEEDED, prId, null, reported);
                case "cancelled" -> end(def, engine, node, FlowEngine.State.CANCELLED, prId,
                    "The pipeline run was cancelled", reported);
                default -> end(def, engine, node, FlowEngine.State.FAILED, prId,
                    "The pipeline run failed; its run page says why", reported);
            }
        }

        private void cancelRunning(FlowEngine engine, FlowDefinition def, AccessRole role) {
            for (String node : engine.running()) {
                Long prId = pipelineRuns.get(node);
                if (prId != null && cancelAsked.add(node)) {
                    try {
                        pipelines.cancel(prId, run.requestedBy());
                    } catch (RuntimeException e) {
                        // already finished: the poll below sees how it ended
                    }
                }
            }
        }

        private void end(FlowDefinition def, FlowEngine engine, String node, FlowEngine.State state, Long prId,
                         String message, Map<String, Object> reported) {
            engine.finish(node, state, reported);
            nodeWasCancelled |= state == FlowEngine.State.CANCELLED;
            save(def, node, state.name().toLowerCase(), prId, Instant.now(), message, reported);
        }

        private void save(FlowDefinition def, String node, String status, Long prId, Instant finished, String message,
                          Map<String, Object> reported) {
            FlowDefinition.Node s = def.node(node);
            int position = def.nodes().indexOf(s);
            String values = "{}";
            try {
                values = json.writeValueAsString(reported);
            } catch (JsonProcessingException e) {
                // keep {}
            }
            store.saveNode(run.id(), new FlowStore.NodeRun(node, s.type(), position, status, started.get(node), finished,
                prId, message, values));
        }

        private void finish(FlowDefinition def, FlowEngine engine) {
            recordSkipped(def);
            FlowEngine.Outcome outcome = engine.outcome();
            switch (outcome) {
                case SUCCEEDED -> store.finishRun(run.id(), "succeeded", engine.halted() && !engine.haltMessage().isBlank()
                    ? engine.haltMessage() : null);
                case CANCELLED -> store.finishRun(run.id(), "cancelled", "Cancelled");
                default -> store.finishRun(run.id(), "failed", failure(def, engine));
            }
            LOG.infof("Flow run %d of %s %s", run.id(), run.flow(), outcome.name().toLowerCase());
        }

        /** Nodes that never ran are recorded, so a run shows every node of its version. */
        private void recordSkipped(FlowDefinition def) {
            Set<String> recorded = new HashSet<>();
            store.nodeRuns(run.id()).forEach(r -> recorded.add(r.nodeId()));
            for (FlowDefinition.Node s : def.nodes()) {
                if (!recorded.contains(s.id())) {
                    store.saveNode(run.id(), new FlowStore.NodeRun(s.id(), s.type(), def.nodes().indexOf(s), "skipped",
                        null, null, null, null, "{}"));
                }
            }
        }

        private String failure(FlowDefinition def, FlowEngine engine) {
            if (engine.halted()) {
                return engine.haltMessage().isBlank() ? "The flow stopped at a stop node" : engine.haltMessage();
            }
            for (FlowDefinition.Node s : def.nodes()) {
                if (engine.state(s.id()) == FlowEngine.State.FAILED && !engine.handled(s.id())) {
                    return "Node " + s.id() + " failed" + (s.pipeline() == null ? "" : " (the pipeline " + s.pipeline() + ")");
                }
            }
            return "The flow failed";
        }
    }

    private static String pretty(Duration d) {
        long m = d.toMinutes();
        return m >= 1 && d.toSeconds() % 60 == 0 ? m + " minutes" : d.toSeconds() + " seconds";
    }

    private Map<String, Object> readParameters(String text) {
        try {
            return json.readValue(text == null ? "{}" : text, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private static AccessRole role(String text) {
        try {
            return AccessRole.parse(text);
        } catch (IllegalArgumentException e) {
            return AccessRole.VIEWER;
        }
    }
}

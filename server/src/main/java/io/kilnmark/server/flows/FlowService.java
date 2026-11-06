package io.kilnmark.server.flows;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
import io.kilnmark.kiln.yaml.Parameters;
import io.kilnmark.kiln.yaml.PipelineDefinition;
import io.kilnmark.kiln.yaml.Position;
import io.kilnmark.kiln.yaml.Problem;
import io.kilnmark.server.api.ApiError;
import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.jobs.JobQueue;
import io.kilnmark.server.notify.NotificationPublisher;
import io.kilnmark.server.pipelines.PipelineService;
import io.kilnmark.server.pipelines.PipelineStore;
import io.kilnmark.server.pipelines.WriteNodeFactory;
import io.kilnmark.server.scan.ScanService;
import io.kilnmark.server.variables.VariableService;
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

    /** Notifications: notify nodes and failed runs; null in tests that build the service by hand. */
    @Inject
    NotificationPublisher notifications;

    /** The server's variables (${vars.name}); null in tests that build the service by hand. */
    @Inject
    VariableService variables;

    /** Saved sources, for sql nodes (ADR 0028); null in tests that build the service by hand. */
    @Inject
    io.kilnmark.server.pipelines.PipelineSources sqlSources;

    /** For how long a sql node's statement may run; null in tests that build the service by hand. */
    @Inject
    io.kilnmark.server.KilnmarkConfig config;

    /** Each sql node run is recorded here (ADR 0029); null in tests that build the service by hand. */
    @Inject
    io.kilnmark.server.audit.AuditLog audit;

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
            Map<String, Object> vars = variables();
            for (FlowDefinition.Node s : def.nodes()) {
                variablesSet(s, vars, problems);
                if (FlowDefinition.SQL.equals(s.type())) {
                    sqlProblems(s, problems);
                    continue;
                }
                if (FlowDefinition.WAIT_FOR.equals(s.type())) {
                    if (s.source() != null && (s.until().file() != null || s.until().query() != null)) {
                        String why = WaitFor.refusal(s, sqlSources);
                        if (why != null) {
                            Position at = s.at(s.until().query() != null ? "query" : "source");
                            problems.add(new Problem(at.line(), at.column(), s.id(), why));
                        }
                    }
                    continue;
                }
                if (FlowDefinition.CHECK_SOURCE.equals(s.type())) {
                    if (s.source() != null) {
                        String why = CheckSource.sourceRefusal(s, sqlSources);
                        if (why != null) {
                            Position at = s.at("source");
                            problems.add(new Problem(at.line(), at.column(), s.id(), why));
                        }
                    }
                    continue;
                }
                if (!runsPipeline(s) || s.pipeline() == null) {
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
                } else {
                    int n = s.version() == null ? p.get().latestVersion() : s.version();
                    pipelineStore.version(s.pipeline(), n).ifPresent(v -> {
                        PipelineDefinition pd = PipelineDefinition.parse(v.yaml()).definition();
                        withProblems(def, s, pd, vars, problems);
                        if (s.each() != null) {
                            eachProblems(def, s, pd, problems);
                        }
                        String unsafe = s.retries() > 0 ? unsafeWrite(pd) : null;
                        if (unsafe != null) {
                            Position r = s.at("retries");
                            problems.add(new Problem(r.line(), r.column(), s.id(), "Node " + s.id() + " is tried "
                                + "again after a failure, so every write in " + s.pipeline() + " must replace or "
                                + "upsert: " + unsafe));
                        }
                    });
                }
            }
        }
        problems.sort(Problem.BY_POSITION);
        return new Validation(problems.isEmpty() && def != null, def == null ? null : def.name(), problems);
    }

    /**
     * The first write node of {@code pipeline} that trying a run again could write twice, as "write-x
     * appends", or null when every write replaces or upserts (ADR 0041). A mode set by a parameter is
     * not known until the run, so it counts as unsafe.
     */
    static String unsafeWrite(PipelineDefinition pipeline) {
        if (pipeline == null) {
            return "it could not be read";
        }
        for (PipelineDefinition.NodeDefinition n : pipeline.nodes()) {
            if (!WriteNodeFactory.TYPE.equals(n.type())) {
                continue;
            }
            String mode = WriteNodeFactory.mode(n.config());
            if (!"replace".equals(mode) && !"upsert".equals(mode)) {
                return n.id() + (mode == null ? " has a mode set by a value" : " " + (mode.equals("create")
                    ? "creates its target" : mode.equals("append") ? "appends" : "uses " + mode));
            }
        }
        return null;
    }

    /** Whether a node runs a pipeline: a run-pipeline node, or a for-each node once per item. */
    static boolean runsPipeline(FlowDefinition.Node s) {
        return FlowDefinition.RUN_PIPELINE.equals(s.type()) || FlowDefinition.FOR_EACH.equals(s.type());
    }

    /**
     * What is wrong with a for-each node against the pipeline it runs: the pipeline must take
     * {@code as}, as one value of the items' kind (ADR 0040).
     */
    private static void eachProblems(FlowDefinition def, FlowDefinition.Node s, PipelineDefinition pipeline,
                                     List<Problem> problems) {
        FlowDefinition.Each each = s.each();
        if (pipeline == null || each.as() == null) {
            return;
        }
        Position at = s.at("as");
        Parameters.Parameter p = pipeline.parameters().stream().filter(x -> x.name().equals(each.as())).findFirst()
            .orElse(null);
        if (p == null) {
            List<String> takes = pipeline.parameters().stream().map(Parameters.Parameter::name).toList();
            problems.add(new Problem(at.line(), at.column(), s.id(), "Node " + s.id() + " fills " + each.as() + " with "
                + "each item, but " + s.pipeline() + " takes no such parameter" + (takes.isEmpty() ? ": it takes none"
                : ": it takes " + String.join(", ", takes))));
            return;
        }
        String given;
        if (each.itemsParameter() != null) {
            Parameters.Parameter list = def.parameter(each.itemsParameter());
            given = list == null ? null : list.type().name().toLowerCase(Locale.ROOT);
        } else {
            given = each.items().isEmpty() ? null : each.items().getFirst() instanceof Double ? "number" : "text";
        }
        if (given != null && !given.equals(p.kind())) {
            problems.add(new Problem(at.line(), at.column(), s.id(), "Node " + s.id() + " fills " + each.as() + " with "
                + "each item, a " + given + ", but " + s.pipeline() + " takes a " + p.kind() + " for it"));
        }
        String shared = each.maxParallel() > 1 ? sharedTarget(pipeline, each.as()) : null;
        if (shared != null) {
            // A run waits only a while for another writing its target, then fails: items at once must not share one.
            Position mp = s.at("max_parallel");
            problems.add(new Problem(mp.line(), mp.column(), s.id(), "Every item would write " + shared + ", so they "
                + "would fail waiting for each other: put ${" + each.as() + "} in the write's target, or set "
                + "max_parallel: 1"));
        }
    }

    /**
     * The target of the first write node of {@code pipeline} that every item would share: one whose
     * target (or table or path) and source do not name the parameter {@code as}. Null when each item
     * writes its own.
     */
    static String sharedTarget(PipelineDefinition pipeline, String as) {
        for (PipelineDefinition.NodeDefinition n : pipeline.nodes()) {
            if (!WriteNodeFactory.TYPE.equals(n.type())) {
                continue;
            }
            String target = null;
            for (String key : List.of("target", "table", "path")) {
                if (n.configText().get(key) instanceof String t) {
                    target = t;
                    break;
                }
            }
            String source = n.configText().get("source") instanceof String t ? t : null;
            if (target != null && !Parameters.names(target).contains(as)
                && (source == null || !Parameters.names(source).contains(as))) {
                return target;
            }
        }
        return null;
    }

    /**
     * What is wrong with a sql node on this server: its source must exist, be PostgreSQL and allow SQL
     * and writing, and each statement must be one a sql node runs (ADR 0028).
     */
    private void sqlProblems(FlowDefinition.Node s, List<Problem> problems) {
        if (s.source() != null) {
            Position at = s.at("source");
            Optional<io.kilnmark.graph.store.Records.Source> src = sqlSources == null ? Optional.empty()
                : sqlSources.source(s.source());
            String why = src.isEmpty() ? "Node " + s.id() + " runs SQL on the source '" + s.source() + "', which does "
                + "not exist" : SqlAction.sourceRefusal(s.id(), src.get());
            if (why != null) {
                problems.add(new Problem(at.line(), at.column(), s.id(), why));
            }
        }
        Position at = s.at("statements");
        for (String p : SqlAction.statementProblems(s.statements())) {
            problems.add(new Problem(at.line(), at.column(), s.id(), "Node " + s.id() + " " + p));
        }
    }

    /** Whether a flow has a sql node. */
    static boolean hasSql(FlowDefinition def) {
        // A wait-for node's query as written, even one the parser refused: a draft may become runnable later.
        return def != null && def.nodes().stream().anyMatch(n -> FlowDefinition.SQL.equals(n.type())
            || (FlowDefinition.WAIT_FOR.equals(n.type()) && n.keys().containsKey("query")));
    }

    /** The refusal when someone who may not see unmasked values saves a flow with a sql node (ADR 0028). */
    static final String SQL_NEEDS_UNMASK = "A flow with a sql node is saved only by a designer whose token sees "
        + "unmasked values: SQL can read and change any column its source's login can (ADR 0028)";

    /**
     * What is wrong with a run-pipeline node's {@code with:} against the parameters of the pipeline it
     * runs: a parameter the pipeline does not take, or a value of another kind.
     */
    private static void withProblems(FlowDefinition def, FlowDefinition.Node s, PipelineDefinition pipeline,
                                     Map<String, Object> vars, List<Problem> problems) {
        if (pipeline == null) {
            return;
        }
        Map<String, Parameters.Parameter> takes = new LinkedHashMap<>();
        pipeline.parameters().forEach(p -> takes.put(p.name(), p));
        Position at = s.at("with");
        for (Parameters.Parameter p : pipeline.parameters()) {
            Parameters.Parameter mine = def.parameter(p.name());
            boolean fromItem = s.each() != null && p.name().equals(s.each().as());
            if (!fromItem && !s.with().containsKey(p.name()) && mine != null && !mine.kind().equals(p.kind())) {
                problems.add(new Problem(s.position().line(), s.position().column(), s.id(), "Node " + s.id()
                    + " passes the flow's " + p.name() + " (a " + mine.kind() + ") to " + s.pipeline() + ", which takes "
                    + "a " + p.kind() + " for it: give it with: or rename one of them"));
            }
        }
        for (Map.Entry<String, Object> e : s.with().entrySet()) {
            Parameters.Parameter p = takes.get(e.getKey());
            if (p == null) {
                problems.add(new Problem(at.line(), at.column(), s.id(), "Node " + s.id() + " gives " + s.pipeline()
                    + " the parameter '" + e.getKey() + "', which it does not take" + (takes.isEmpty()
                    ? ": it takes none" : ": it takes " + String.join(", ", takes.keySet()))));
                continue;
            }
            String given = kindOf(e.getValue(), def, vars);
            if (given != null && !given.equals(p.kind())) {
                problems.add(new Problem(at.line(), at.column(), s.id(), "Node " + s.id() + " gives " + e.getKey()
                    + " a " + given + " value, but " + s.pipeline() + " takes a " + p.kind() + " for it"));
            }
        }
    }

    /**
     * The kind of a with: value, as a parameter's type is written (text, number, boolean, text list,
     * number list): its own, or that of the one parameter, variable or node value it is; null if unknown.
     */
    private static String kindOf(Object v, FlowDefinition def, Map<String, Object> vars) {
        if (v instanceof Double) {
            return "number";
        }
        if (v instanceof Boolean) {
            return "boolean";
        }
        String whole = Parameters.whole((String) v);
        if (whole == null) {
            return "text"; // text, or text with values put in
        }
        if (whole.startsWith(Parameters.VARS)) {
            Object value = vars.get(whole.substring(Parameters.VARS.length()));
            return value instanceof Double ? "number" : value instanceof Boolean ? "boolean" : value == null ? null
                : "text";
        }
        Parameters.Parameter p = def.parameter(whole);
        if (p != null) {
            return p.kind();
        }
        FlowCondition.Type reported = FlowDefinition.nodeValueType(def, whole);
        return reported == null ? null : reported.name().toLowerCase(Locale.ROOT);
    }

    /** A problem for each server variable a node names that is not set on this server. */
    private static void variablesSet(FlowDefinition.Node s, Map<String, Object> vars, List<Problem> problems) {
        List<String> texts = new ArrayList<>();
        s.with().values().forEach(v -> {
            if (v instanceof String t) {
                texts.add(t);
            }
        });
        if (s.title() != null) {
            texts.add(s.title());
        }
        if (s.message() != null) {
            texts.add(s.message());
        }
        for (String t : texts) {
            for (String name : Parameters.names(t)) {
                if (name.startsWith(Parameters.VARS) && !vars.containsKey(name.substring(Parameters.VARS.length()))) {
                    problems.add(new Problem(s.position().line(), s.position().column(), s.id(), "Node " + s.id()
                        + " names the server variable '" + name.substring(Parameters.VARS.length())
                        + "', which is not set on this server"));
                    return;
                }
            }
        }
    }

    /** The server's variables now; none in tests without them. */
    private Map<String, Object> variables() {
        return variables == null ? Map.of() : variables.snapshot();
    }

    /**
     * Saves a flow's YAML as its next version, creating the flow when it is new. A flow with problems
     * is saved as a draft that cannot run. The flow's name is the YAML's and never changes.
     *
     * @param role     the caller's role: designers and admins save flows
     * @param expected the latest version the caller read, or null (409 if another has been saved since)
     */
    public FlowStore.Flow save(String yaml, String message, String by, AccessRole role, Integer expected) {
        return save(yaml, message, by, role, false, expected);
    }

    /**
     * As {@link #save(String, String, String, AccessRole, Integer)}, for a caller who may see unmasked
     * values when {@code mayUnmask}: only such a caller saves a flow with a sql node, draft or not.
     */
    public FlowStore.Flow save(String yaml, String message, String by, AccessRole role, boolean mayUnmask,
                               Integer expected) {
        return save(yaml, message, by, role, mayUnmask, expected, null, false, null);
    }

    /** Saves a new flow as version 1; 409 when a flow with the YAML's name exists. */
    public FlowStore.Flow create(String yaml, String message, String by, AccessRole role, boolean mayUnmask) {
        return save(yaml, message, by, role, mayUnmask, null, null, true, null);
    }

    /**
     * As {@link #create}, in the library {@code library} ({@code sample}, ADR 0043), marked in the
     * same transaction. Only the samples call it: the flows API never sets the mark.
     */
    public FlowStore.Flow createInLibrary(String yaml, String message, String by, AccessRole role, boolean mayUnmask,
                                          String library) {
        return save(yaml, message, by, role, mayUnmask, null, null, true, library);
    }

    /**
     * Saves the next version of the flow {@code name}: 404 when there is none, and the YAML must keep
     * the flow's name.
     */
    public FlowStore.Flow update(String name, String yaml, String message, String by, AccessRole role,
                                 boolean mayUnmask, Integer expected) {
        if (!role.atLeast(AccessRole.DESIGNER)) {
            throw fail(Response.Status.FORBIDDEN, "Saving a flow takes the designer role");
        }
        if (store.find(name).isEmpty()) {
            throw fail(Response.Status.NOT_FOUND, "No flow called " + name);
        }
        return save(yaml, message, by, role, mayUnmask, expected, name, false, null);
    }

    private FlowStore.Flow save(String yaml, String message, String by, AccessRole role, boolean mayUnmask,
                                Integer expected, String named, boolean create, String library) {
        if (!role.atLeast(AccessRole.DESIGNER)) {
            throw fail(Response.Status.FORBIDDEN, "Saving a flow takes the designer role");
        }
        if (!mayUnmask && yaml != null && yaml.length() <= MAX_YAML_CHARS
            && hasSql(FlowDefinition.parse(yaml).definition())) {
            throw fail(Response.Status.FORBIDDEN, SQL_NEEDS_UNMASK);
        }
        Validation check = validate(yaml);
        if (check.name() == null || !FlowDefinition.NAME.matcher(check.name()).matches()) {
            throw new IllegalArgumentException("yaml: the flow needs a name, such as name: nightly-customers");
        }
        if (named != null && !named.equals(check.name())) {
            throw new IllegalArgumentException("yaml: the name is " + check.name() + ", but this flow is " + named
                + " and a flow's name cannot change");
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
        boolean exists = !create && store.find(check.name()).isPresent();
        try {
            // sql_checked records whether the saver may write a person's SQL: a version saved without the grant
            // never runs SQL, even if a later Kilnmark reads a node in it that this one did not know.
            return store.save(check.name(), expected, !exists, check.name(), description, yaml, message, by,
                check.valid(), problems, library, mayUnmask);
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

    /** Marks failed the runs whose worker stopped, and tells of each; called by workers. */
    public int failOrphanedRuns() {
        List<FlowStore.Run> failed = store.failOrphanedRuns();
        failed.forEach(r -> told(r.flow(), r.id(), "failed", r.error()));
        return failed.size();
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
        return queueChecked(checkRun(name, version, parameters, role), by, role);
    }

    /**
     * A run checked and ready to queue: its version, and the values it starts with as JSON. Checking
     * reads the flow's pipelines on connections of their own, so it is done before any transaction
     * that queues the run (ADR 0047: a request key and its run commit together).
     */
    public record CheckedRun(String name, int version, String parameters, String variables) {
    }

    /**
     * The checks of {@link #queueRun}, queueing nothing.
     *
     * @throws WebApplicationException (403, 404, 409) and IllegalArgumentException (400) as queueRun does
     */
    public CheckedRun checkRun(String name, Integer version, Map<String, Object> parameters, AccessRole role) {
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
        if (!sqlChecked(def, name, n)) {
            throw fail(Response.Status.CONFLICT, SQL_NOT_CHECKED.formatted(n, name));
        }
        Map<String, Object> vars = variables();
        Map<String, Object> resolved = resolveParameters(def, parameters, vars);
        try {
            return new CheckedRun(name, n, json.writeValueAsString(resolved), json.writeValueAsString(vars));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("parameters: could not be read");
        }
    }

    /**
     * Queues a run checked by {@link #checkRun}: one transaction on the run's tables only, which a
     * caller's own transaction ({@code Db.together}) joins.
     *
     * @throws WebApplicationException (409) while a run of the flow is queued or running
     */
    public FlowStore.Run queueChecked(CheckedRun checked, String by, AccessRole role) {
        try {
            return store.queueRun(checked.name(), checked.version(), by, role.id(), checked.parameters(),
                checked.variables());
        } catch (FlowStore.Busy e) {
            throw fail(Response.Status.CONFLICT, "A run of " + checked.name() + " is already queued or running. Wait "
                + "for it to finish, or cancel it, then run the flow again.");
        }
    }

    /** Why a version saved before sql nodes were checked (V29) does not run. */
    static final String SQL_NOT_CHECKED = "Version %d of %s has SQL (a sql node, or a wait-for node's query) and was "
        + "saved before Kilnmark checked who writes them. A designer whose token sees unmasked values saves it again, "
        + "then it can run";

    /**
     * Whether a version may run the SQL it holds: none, or saved by someone who may write it. A wait-for
     * query also needs {@code query_checked} (V34): the versions saved before it may hold one nobody
     * with the grant wrote.
     */
    private boolean sqlChecked(FlowDefinition def, String name, int n) {
        if (!hasSql(def)) {
            return true;
        }
        boolean query = def.nodes().stream().anyMatch(x -> FlowDefinition.WAIT_FOR.equals(x.type())
            && x.keys().containsKey("query"));
        return store.sqlChecked(name, n) && (!query || store.queryChecked(name, n));
    }

    /** The values a run starts with: each parameter as given or by default, checked against its type. */
    static Map<String, Object> resolveParameters(FlowDefinition def, Map<String, Object> given) {
        return resolveParameters(def, given, Map.of());
    }

    static Map<String, Object> resolveParameters(FlowDefinition def, Map<String, Object> given,
                                                 Map<String, Object> vars) {
        try {
            return Parameters.resolve(def.name(), def.parameters(), given, vars);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("parameters: " + e.getMessage());
        }
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

    // ---- views, for the API ----

    /**
     * Every flow by name, with whether its latest version can run and its latest run. {@code values}:
     * whether the caller may see the values runs started with (stewards and above, {@code Sights.data()}).
     */
    public List<FlowViews.FlowSummary> list(boolean values) {
        List<FlowStore.Summary> all = store.summaries();
        Map<Long, PipelineStore.FlowTotals> totals = pipelineStore.flowTotals(all.stream()
            .filter(s -> s.lastRun() != null).map(s -> s.lastRun().id()).toList());
        return all.stream().map(s -> new FlowViews.FlowSummary(s.flow().name(), s.flow().displayName(),
            s.flow().description(), s.flow().latestVersion(), s.valid(), s.flow().updatedBy(), s.flow().updatedAt(),
            s.lastRun() == null ? null : view(s.lastRun(), values, totals), s.flow().library())).toList();
    }

    /** A flow with its latest version and latest run; 404 when there is none. {@code values} as for list. */
    public FlowViews.FlowDetail detail(String name, boolean values) {
        FlowStore.Flow f = flow(name);
        List<FlowStore.Run> last = store.runs(name, 1);
        return new FlowViews.FlowDetail(f.name(), f.displayName(), f.description(), f.latestVersion(), f.createdBy(),
            f.createdAt(), f.updatedBy(), f.updatedAt(), versionDetail(name, f.latestVersion()),
            last.isEmpty() ? null : view(last.get(0), values), f.library());
    }

    /** Every version of a flow, newest first; 404 when there is no such flow. */
    public List<FlowViews.VersionSummary> versions(String name) {
        flow(name);
        return store.versions(name);
    }

    /** One saved version with its YAML, problems and parameters; 404 when there is none. */
    public FlowViews.VersionDetail versionDetail(String name, int n) {
        FlowStore.Version v = store.version(name, n).orElseThrow(() -> fail(Response.Status.NOT_FOUND,
            store.find(name).isEmpty() ? "No flow called " + name : name + " has no version " + n));
        return new FlowViews.VersionDetail(v.n(), v.yaml(), v.message(), v.author(), v.createdAt(), v.valid(),
            savedProblems(v.problems()), parameters(FlowDefinition.parse(v.yaml()).definition()));
    }

    /**
     * Checks YAML without saving it. With {@code name}, for a saved flow (404 when there is none), a
     * YAML naming another flow is a problem: a flow's name cannot change.
     */
    public FlowViews.Validation check(String yaml, String name) {
        if (name != null) {
            flow(name);
        }
        Validation v = validate(yaml);
        List<FlowViews.Problem> problems = new ArrayList<>();
        if (name != null && v.name() != null && !name.equals(v.name())) {
            problems.add(new FlowViews.Problem(1, 1, null, "The name is " + v.name() + ", but this flow is " + name
                + " and a flow's name cannot change"));
        }
        v.problems().forEach(p -> problems.add(new FlowViews.Problem(p.line(), p.column(), p.nodeId(), p.message())));
        return new FlowViews.Validation(v.valid() && problems.size() == v.problems().size(), v.name(), problems,
            parameters(FlowDefinition.parse(yaml).definition()));
    }

    /** A flow's runs as the API shows them, newest first; 404 when there is no such flow. */
    public List<FlowViews.RunSummary> runViews(String name, int limit, boolean values) {
        flow(name);
        List<FlowStore.Run> runs = runs(name, limit);
        Map<Long, PipelineStore.FlowTotals> totals = pipelineStore.flowTotals(runs.stream().map(FlowStore.Run::id)
            .toList());
        return runs.stream().map(r -> view(r, values, totals)).toList();
    }

    /**
     * A flow run with every node of its version: those that have not started yet are pending, each
     * with the pipeline it runs and the pipeline run it started. Without {@code values} (viewers), the
     * values the run started with, and messages that quote values (a notice's title, the values a
     * pipeline refused), are withheld: they can hold server variables, which only stewards read.
     */
    public FlowViews.RunDetail runDetail(long id, boolean values) {
        FlowStore.Run r = store.run(id).orElseThrow(() -> fail(Response.Status.NOT_FOUND, "No flow run " + id));
        FlowDefinition def = store.version(r.flow(), r.version()).map(v -> FlowDefinition.parse(v.yaml()).definition())
            .orElse(null);
        Map<String, FlowDefinition.Node> declared = new LinkedHashMap<>();
        if (def != null) {
            def.nodes().forEach(n -> declared.put(n.id(), n));
        }
        Map<String, FlowStore.NodeRun> recorded = new LinkedHashMap<>();
        store.nodeRuns(id).forEach(n -> recorded.put(n.nodeId(), n));
        Map<Long, PipelineStore.RunTimes> times = pipelineStore.flowRunTimes(id);
        List<FlowViews.NodeRunView> nodes = new ArrayList<>();
        int position = 0;
        for (FlowDefinition.Node n : declared.values()) {
            FlowStore.NodeRun s = recorded.remove(n.id());
            List<FlowViews.ItemRunView> items = n.each() == null ? null : items(r, n, s, values);
            nodes.add(s == null
                ? new FlowViews.NodeRunView(n.id(), n.type(), position, "pending", null, null, null, n.pipeline(), null,
                    null, items, null)
                : nodeView(s, n.pipeline(), values, items, pipelineRunTimes(s, times), runsPipeline(n) && n.retries() > 0
                    ? tries(r.id(), n.id()) : null));
            position++;
        }
        recorded.values().forEach(s -> nodes.add(nodeView(s, null, values, null, pipelineRunTimes(s, times), null)));
        return new FlowViews.RunDetail(view(r, values, pipelineStore.flowTotals(List.of(r.id()))), nodes);
    }

    /**
     * A for-each node's items in a run, each with its pipeline run. Items written in the flow are
     * shown to everyone who reads the flow; items from a parameter of the run only with
     * {@code values}, since a run's values can hold server variables.
     */
    private List<FlowViews.ItemRunView> items(FlowStore.Run r, FlowDefinition.Node n, FlowStore.NodeRun s,
                                              boolean values) {
        FlowDefinition.Each each = n.each();
        List<Object> list = each.itemsParameter() == null ? each.items()
            : each.itemsIn(numbers(Optional.ofNullable(readParameters(r.parameters())).orElse(Map.of())));
        boolean show = values || each.itemsParameter() == null;
        java.util.SortedMap<Integer, PipelineStore.Run> runs = pipelineStore.flowItemRuns(r.id(), n.id());
        boolean ended = s != null && !"running".equals(s.status()) && !"pending".equals(s.status());
        Set<Integer> notStarted = indexes(s, NOT_STARTED);
        // An item stopped for its timeout counts as failed, as the node counts it, though its run was cancelled.
        Set<Integer> timedOut = indexes(s, TIMED_OUT_ITEMS);
        // An item waiting to be tried again shows pending: its next try has not started (ADR 0041).
        Set<Integer> retrying = s != null && "running".equals(s.status()) ? indexes(s, RETRYING_ITEMS) : Set.of();
        List<FlowViews.ItemRunView> out = new ArrayList<>();
        int count = Math.max(list.size(), runs.isEmpty() ? 0 : runs.lastKey() + 1);
        for (int i = 0; i < count; i++) {
            PipelineStore.Run pr = runs.get(i);
            Object value = show && i < list.size() ? list.get(i) : null;
            String unrun = notStarted.contains(i) ? "failed" : ended ? "skipped" : "pending";
            out.add(pr == null || retrying.contains(i)
                ? new FlowViews.ItemRunView(i, value, pr == null ? unrun : "pending", null, null, null, null)
                : new FlowViews.ItemRunView(i, value, timedOut.contains(i) && pr.finished()
                    && !"succeeded".equals(pr.status()) ? "failed" : pr.status(), pr.id(), pr.requestedAt(),
                    pr.startedAt(), pr.finishedAt()));
        }
        return out;
    }

    /** Every pipeline run a node started, when one was a try again (ADR 0041); null when none was. */
    private List<FlowViews.TryView> tries(long runId, String node) {
        List<PipelineStore.Try> all = pipelineStore.flowTries(runId, node);
        if (all.stream().noneMatch(t -> t.number() > 0)) {
            return null;
        }
        return all.stream().map(t -> new FlowViews.TryView(t.item(), t.number() + 1, t.run().id(), t.run().status(),
            t.run().startedAt(), t.run().finishedAt())).toList();
    }

    /** Where a for-each node's row lists the items that failed without a run (its node message says why). */
    static final String NOT_STARTED = "not_started";

    /** Where a run-pipeline or sql node's row says it took longer than its timeout and was stopped (ADR 0041). */
    static final String TIMED_OUT = "timed_out";
    /** Where a running for-each node's row lists the items waiting to be tried again (ADR 0041). */
    static final String RETRYING_ITEMS = "retrying_items";
    /** Where a for-each node's row lists the items whose runs took longer than its timeout and were stopped. */
    static final String TIMED_OUT_ITEMS = "timed_out_items";
    private static final Map<String, Object> TIMED_OUT_RECORD = Map.of(TIMED_OUT, true);
    /** The most the watcher's clock and the database's are taken to differ by, when judging a run found again. */
    private static final Duration SKEW = Duration.ofSeconds(5);

    /** What a node, or an item, that took longer than its timeout says. */
    static String tookTooLong(Duration timeout) {
        return "Took longer than " + FlowDefinition.written(timeout) + " and was stopped";
    }

    /** The items a for-each node's row lists under {@code key}: those failed without a run, or timed out. */
    private Set<Integer> indexes(FlowStore.NodeRun s, String key) {
        Set<Integer> out = new HashSet<>();
        com.fasterxml.jackson.databind.JsonNode reported = s == null ? null : tree(s.reported());
        if (reported != null && reported.get(key) instanceof com.fasterxml.jackson.databind.JsonNode list
            && list.isArray()) {
            list.forEach(x -> out.add(x.asInt()));
        }
        return out;
    }

    /** Values read back from JSON with every number, in lists too, as a Double, as a run holds them. */
    private static Map<String, Object> numbers(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(k, v instanceof List<?> l
            ? l.stream().map(x -> x instanceof Number num ? (Object) num.doubleValue() : x).toList()
            : v instanceof Number num ? num.doubleValue() : v));
        return out;
    }

    /** Asks a run to stop, as {@link #cancel(long, String, AccessRole)}, answering as the API shows runs. */
    public FlowViews.RunSummary cancelView(long id, String by, AccessRole role) {
        return view(cancel(id, by, role), true);
    }

    /**
     * One run as the API lists runs; without {@code values} (viewers), the values it started with are
     * left out. 404 when there is no such run.
     */
    public FlowViews.RunSummary runView(long id, boolean values) {
        return view(store.run(id).orElseThrow(() -> fail(Response.Status.NOT_FOUND, "No flow run " + id)), values);
    }

    private FlowStore.Flow flow(String name) {
        return store.find(name).orElseThrow(() -> fail(Response.Status.NOT_FOUND, "No flow called " + name));
    }

    private FlowViews.NodeRunView nodeView(FlowStore.NodeRun s, String pipeline, boolean values,
                                           List<FlowViews.ItemRunView> items, FlowViews.PipelineRunTimes times,
                                           List<FlowViews.TryView> tries) {
        return new FlowViews.NodeRunView(s.nodeId(), s.type(), s.position(), s.status(), s.startedAt(), s.finishedAt(),
            s.pipelineRunId(), pipeline, values ? s.message() : withoutValues(s), tree(s.reported()), items, times,
            tries);
    }

    /**
     * The times of the pipeline run a node started, from the flow run's pipeline-run times; null when
     * it started none, or that run is gone.
     */
    private static FlowViews.PipelineRunTimes pipelineRunTimes(FlowStore.NodeRun s,
                                                               Map<Long, PipelineStore.RunTimes> times) {
        PipelineStore.RunTimes t = s.pipelineRunId() == null ? null : times.get(s.pipelineRunId());
        return t == null ? null : new FlowViews.PipelineRunTimes(t.requestedAt(), t.startedAt(), t.finishedAt());
    }

    /** What a message from which values are left out says instead of them. */
    static final String VALUES_WITHHELD = "the values are shown to stewards";

    /**
     * A node's message with the values it quotes left out: a notice's title, which has the run's
     * values put in, and the values a pipeline refused or would not take. Other messages are in
     * Kilnmark's words and quote none.
     */
    static String withoutValues(FlowStore.NodeRun s) {
        String m = s.message();
        if (m == null) {
            return null;
        }
        if (FlowDefinition.NOTIFY.equals(s.type())) {
            return m.startsWith("Sent: ") ? "Sent a notice (" + VALUES_WITHHELD + ")" : m;
        }
        int at = m.indexOf(" with these values");
        return at < 0 ? m : m.substring(0, at) + " with the values this run gave it (" + VALUES_WITHHELD + ")";
    }

    private FlowViews.RunSummary view(FlowStore.Run r, boolean values) {
        return view(r, values, pipelineStore.flowTotals(List.of(r.id())));
    }

    /** A run as the API shows it, with what its pipeline runs came to, from {@code totals} (by flow run). */
    private FlowViews.RunSummary view(FlowStore.Run r, boolean values, Map<Long, PipelineStore.FlowTotals> totals) {
        PipelineStore.FlowTotals t = totals.getOrDefault(r.id(), new PipelineStore.FlowTotals(0, null, null));
        return new FlowViews.RunSummary(r.id(), r.flow(), r.version(), r.status(), r.requestedBy(), r.requestedRole(),
            r.requestedAt(), r.startedAt(), r.finishedAt(), r.cancelRequested(), r.error(),
            values ? tree(r.parameters()) : null, t.pipelineRuns(), t.rowsWritten(), t.lowestScore());
    }

    private com.fasterxml.jackson.databind.JsonNode tree(String text) {
        if (text == null) {
            return null;
        }
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private List<FlowViews.Problem> savedProblems(String text) {
        if (text == null) {
            return List.of();
        }
        try {
            return json.readValue(text, new TypeReference<List<FlowViews.Problem>>() { });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    /** The parameters a flow declares, as the API shows them; none when the YAML could not be read. */
    static List<FlowViews.Parameter> parameters(FlowDefinition def) {
        if (def == null) {
            return List.of();
        }
        return def.parameters().stream().map(p -> {
            String variable = p.defaultValue() instanceof Parameters.VarRef v ? v.variable() : null;
            Object value = variable == null ? p.defaultValue() : null;
            return new FlowViews.Parameter(p.name(), p.kind(), value, variable, value == null && variable == null,
                p.description());
        }).toList();
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
        failOrphanedRuns();
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
        Execution execution = new Execution(run, shutdown, lease);
        try {
            execution.run();
        } catch (RuntimeException e) {
            // A sql node still running must not commit after the run is recorded as failed.
            execution.sqlControls.values().forEach(SqlAction.Control::stop);
            execution.checkThreads.values().forEach(Thread::interrupt);
            execution.lookThreads.values().forEach(Thread::interrupt);
            // Not the exception's text: it could quote anything. The class is enough for the log.
            LOG.errorf("Flow run %d failed with %s", id, e.getClass().getName());
            String why = "The flow could not be run to the end: an internal error. Look at the server's log.";
            store.finishRun(id, "failed", why);
            told(run.flow(), id, "failed", why);
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
        /** Run-pipeline and sql nodes stopped for taking longer than their timeout (ADR 0041). */
        private final Set<String> timedOut = new HashSet<>();
        /** Each run-pipeline and sql node's try, from 0 (ADR 0041). */
        private final Map<String, Integer> tries = new HashMap<>();
        /** Run-pipeline and sql nodes waiting to be tried again, and when. */
        private final Map<String, Instant> retryAt = new HashMap<>();
        /** When each sql node's statements started: its timeout counts from then, not while it waited for a place. */
        private final Map<String, Instant> sqlStartedAt = new HashMap<>();
        /** The sql nodes running, each on a thread of its own so the flow keeps watching its other nodes. */
        private final Map<String, java.util.concurrent.CompletableFuture<SqlAction.Outcome>> sqlRuns = new HashMap<>();
        private final Map<String, SqlAction.Control> sqlControls = new HashMap<>();
        /** The flow job's lease: a sql node takes its place on its source here (ADR 0040); null in some tests. */
        private final JobQueue.ScanLease lease;
        /** Places on their sources held by running sql nodes, given back as each ends. */
        private final Map<String, List<Long>> sqlPlaces = new HashMap<>();
        /** Sql nodes waiting for a free place on their source. */
        private final Set<String> sqlWaiting = new HashSet<>();
        /** The check-source nodes running, each on a thread of its own (ADR 0041). */
        private final Map<String, java.util.concurrent.CompletableFuture<CheckSource.Outcome>> checkRuns = new HashMap<>();
        private final Map<String, Thread> checkThreads = new HashMap<>();
        /** Check-source nodes stopped because the flow was cancelled or stopped. */
        private final Set<String> checksStopped = new HashSet<>();
        /** Each wait-for node's look in progress, on a thread of its own, and when it looks next (ADR 0041). */
        private final Map<String, java.util.concurrent.CompletableFuture<WaitFor.Look>> looks = new HashMap<>();
        private final Map<String, Thread> lookThreads = new HashMap<>();
        private final Map<String, Instant> nextLook = new HashMap<>();
        /** What each wait-for node found at its last look. */
        private final Map<String, Integer> lastFound = new HashMap<>();
        private boolean nodeWasCancelled;
        /** What ${...} reads: the run's parameters and the variables it started with (vars.name). */
        private Map<String, Object> scope = Map.of();
        private Map<String, Object> runVariables = Map.of();
        /** The values the run started with, numbers in lists too as Doubles: a for-each node's items may be one. */
        private Map<String, Object> runParameters = Map.of();
        private final Map<String, List<String>> declared = new HashMap<>();
        /** The for-each nodes started, each with its items' runs. */
        private final Map<String, Fan> fans = new HashMap<>();

        Execution(FlowStore.Run run, BooleanSupplier shutdown, JobQueue.ScanLease lease) {
            this.run = run;
            this.shutdown = shutdown;
            this.lease = lease;
        }

        void run() {
            FlowStore.Version version = store.version(run.flow(), run.version()).orElse(null);
            FlowDefinition.Parsed parsed = version == null ? null : FlowDefinition.parse(version.yaml());
            FlowDefinition def = parsed == null ? null : parsed.definition();
            if (def == null || !parsed.problems().isEmpty()) {
                String why = "The flow could not start: version " + run.version() + " of " + run.flow()
                    + " no longer reads as a flow";
                store.finishRun(run.id(), "failed", why);
                told(run.flow(), run.id(), "failed", why);
                return;
            }
            if (!sqlChecked(def, run.flow(), run.version())) {
                String why = "The flow could not start: " + SQL_NOT_CHECKED.formatted(run.version(), run.flow());
                store.finishRun(run.id(), "failed", why);
                told(run.flow(), run.id(), "failed", why);
                return;
            }
            Map<String, Object> params = readParameters(run.parameters());
            runVariables = readParameters(run.variables());
            if (params == null || runVariables == null) {
                // Never run on defaults in place of the values the run was started with.
                String why = "The flow could not start: the values it was started with could not be read";
                store.finishRun(run.id(), "failed", why);
                told(run.flow(), run.id(), "failed", why);
                return;
            }
            scope = Parameters.scope(params, runVariables);
            runParameters = numbers(params);
            List<String> ids = def.nodes().stream().map(FlowDefinition.Node::id).toList();
            // max_parallel (ADR 0040): a for-each node counts once; stop nodes are never held, so a flow can always end.
            Set<String> stops = new HashSet<>();
            def.nodes().stream().filter(n -> FlowDefinition.STOP.equals(n.type())).forEach(n -> stops.add(n.id()));
            FlowEngine engine = new FlowEngine(ids, def.engineConnections(), params,
                def.maxParallel() == null ? 0 : def.maxParallel(), stops);
            AccessRole role = role(run.requestedRole());
            boolean cancelling = false;
            while (true) {
                if (shutdown.getAsBoolean()) {
                    sqlControls.values().forEach(SqlAction.Control::stop);
                    checkThreads.values().forEach(Thread::interrupt);
                    lookThreads.values().forEach(Thread::interrupt);
                    for (String s : engine.running()) {
                        if (sqlRuns.containsKey(s)) {
                            save(def, s, "failed", null, Instant.now(), stoppedSql(s), Map.of());
                            continue;
                        }
                        Fan fan = fans.get(s);
                        if (fan != null) {
                            fan.running().forEach(this::cancelPipelineRun);
                            save(def, s, "failed", null, Instant.now(), "The worker running this flow stopped",
                                recorded(fan, Map.of()));
                            continue;
                        }
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
                    String why = "The worker running this flow was stopped before it finished. Run it again; check "
                        + "what the pipelines it had started had written first.";
                    store.finishRun(run.id(), "failed", why);
                    told(run.flow(), run.id(), "failed", why);
                    return;
                }
                if (!cancelling && store.cancelRequested(run.id())) {
                    cancelling = true;
                    engine.cancel();
                }
                if (cancelling) {
                    cancelRunning(engine);
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
                    cancelRunning(engine); // a stop node ends what is still running
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
                sqlControls.values().forEach(SqlAction.Control::stop);
                throw new IllegalStateException("interrupted");
            }
        }

        /**
         * What a sql node did when the worker stopped: it is told to stop, and given a few seconds to
         * roll back, or to say that it had committed just before.
         */
        private String stoppedSql(String node) {
            try {
                SqlAction.Outcome o = sqlRuns.get(node).get(10, java.util.concurrent.TimeUnit.SECONDS);
                return o.succeeded() ? "The worker running this flow stopped just after this node committed: "
                    + o.message() : "The worker running this flow stopped; " + o.message();
            } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException e) {
                return "The worker running this flow stopped while this node ran; its statement was cancelled, and "
                    + "nothing it ran was committed";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "The worker running this flow stopped while this node ran; nothing it ran was committed";
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
            if (FlowDefinition.NOTIFY.equals(s.type())) {
                String title = words(engine, s.title());
                String message = s.message() == null ? null : words(engine, s.message());
                if (notifications != null) {
                    notifications.flowNotice(run.flow(), run.id(), node, s.severity(), title, message);
                }
                engine.finish(node, FlowEngine.State.SUCCEEDED, Map.of());
                save(def, node, "succeeded", null, now, "Sent: " + title, Map.of());
                return;
            }
            if (FlowDefinition.WAIT.equals(s.type())) {
                save(def, node, "running", null, null, "Waiting " + pretty(s.waitFor()), Map.of());
                return;
            }
            if (FlowDefinition.SQL.equals(s.type())) {
                trySql(def, s);
                return;
            }
            if (FlowDefinition.CHECK_SOURCE.equals(s.type())) {
                startCheck(def, s);
                return;
            }
            if (FlowDefinition.WAIT_FOR.equals(s.type())) {
                save(def, node, "running", null, null, "Waiting for " + waitingFor(s), Map.of());
                if (s.until().query() != null && audit != null) {
                    // A person's SQL runs on a schedule (ADR 0028, 0029): who, the flow and run, the source; never the query.
                    audit.record(new io.kilnmark.server.audit.AuditLog.Actor(run.requestedBy(), null),
                        io.kilnmark.server.audit.AuditLog.FLOW_SQL, run.flow() + "/" + run.id() + "/" + s.id(),
                        Map.of("source", s.source(), "query", true, "version", run.version()));
                }
                startLook(s);
                return;
            }
            if (FlowDefinition.FOR_EACH.equals(s.type())) {
                startEach(def, engine, s, role);
                return;
            }
            save(def, node, "running", null, null, null, Map.of());
            tryQueue(def, engine, s, role);
        }

        // ---- for-each (ADR 0040) ----

        /**
         * A for-each node in a run: its items, and the pipeline run each started (by item, from 0).
         * Items start in order while fewer than the node's max_parallel run; every item runs, and the
         * node ends when the last one does.
         */
        private static final class Fan {
            final FlowDefinition.Node node;
            final List<Object> items;
            final java.util.SortedMap<Integer, Long> runs = new java.util.TreeMap<>();
            /** How each item ended: succeeded, failed or cancelled; failed too for one that could not start. */
            final java.util.SortedMap<Integer, String> ended = new java.util.TreeMap<>();
            final Set<Integer> cancelAsked = new HashSet<>();
            /** Items whose run took longer than the node's timeout and was stopped: each counts as failed. */
            final java.util.SortedSet<Integer> timedOut = new java.util.TreeSet<>();
            /** Items that failed without a run: the pipeline refused their values, or could not be run. */
            final java.util.SortedSet<Integer> notStarted = new java.util.TreeSet<>();
            /** Each item's try, from 0, and the items waiting to be tried again and when (ADR 0041). */
            final Map<Integer, Integer> tries = new HashMap<>();
            final java.util.SortedMap<Integer, Instant> retryAt = new java.util.TreeMap<>();
            int next;
            Instant waitingSince;
            /** Nothing more starts: the flow was cancelled or stopped, a run was cancelled by hand, or it gave up waiting. */
            boolean stopping;
            boolean byHand;
            String problem;
            String said;

            Fan(FlowDefinition.Node node, List<Object> items) {
                this.node = node;
                this.items = items;
            }

            /** The items' runs still going, by pipeline run id. */
            List<Long> running() {
                List<Long> out = new ArrayList<>();
                runs.forEach((i, id) -> {
                    if (!ended.containsKey(i)) {
                        out.add(id);
                    }
                });
                return out;
            }

            long count(String status) {
                return ended.values().stream().filter(status::equals).count();
            }

            /** How far it has got, as the run page says it: "3 of 5 items done, 1 failed". */
            String progress() {
                long failed = count("failed");
                return ended.size() + " of " + items.size() + (items.size() == 1 ? " item" : " items") + " done"
                    + (failed > 0 ? ", " + failed + " failed" : "")
                    + (retryAt.isEmpty() ? "" : ", " + retryAt.size() + " to try again");
            }
        }

        /** Starts a for-each node: reads its items, finds any runs it already started, and starts the first items. */
        private void startEach(FlowDefinition def, FlowEngine engine, FlowDefinition.Node s, AccessRole role) {
            List<Object> items = s.each().itemsIn(runParameters);
            if (items.isEmpty() || items.size() > FlowDefinition.MAX_ITEMS) {
                end(def, engine, s.id(), FlowEngine.State.FAILED, null, "Node " + s.id() + " has " + items.size()
                    + " items: a for-each node runs 1 to " + FlowDefinition.MAX_ITEMS, Map.of());
                return;
            }
            Fan fan = new Fan(s, items);
            fans.put(s.id(), fan);
            // Found again by flow run, node and item, so no item starts twice.
            pipelineStore.flowItemRuns(run.id(), s.id()).forEach((i, pr) -> fan.runs.put(i, pr.id()));
            pipelineStore.flowTries(run.id(), s.id()).forEach(t -> {
                if (t.item() != null) {
                    fan.tries.merge(t.item(), t.number(), Math::max);
                }
            });
            save(def, s.id(), "running", null, null, fan.progress(), Map.of());
            fan.said = fan.progress();
            fill(def, engine, fan, role);
        }

        /** Starts the next items while fewer than the node's max_parallel run, in the order they are listed. */
        private void fill(FlowDefinition def, FlowEngine engine, Fan fan, AccessRole role) {
            FlowDefinition.Node s = fan.node;
            Instant now = Instant.now();
            for (Integer i : new ArrayList<>(fan.retryAt.keySet())) {
                if (fan.stopping || fan.running().size() >= s.each().maxParallel()) {
                    break;
                }
                if (now.isBefore(fan.retryAt.get(i))) {
                    continue;
                }
                int t = fan.tries.getOrDefault(i, 0);
                try {
                    PipelineStore.Run queued = pipelines.queueRunForFlow(s.pipeline(), s.version(), run.requestedBy(),
                        role, run.id(), s.id(), i, itemValues(engine, def, s, fan.items.get(i)), runVariables,
                        s.priority(), t);
                    fan.retryAt.remove(i);
                    fan.runs.put(i, queued.id());
                    fan.waitingSince = null;
                } catch (PipelineStore.Busy busy) {
                    // Another run of the pipeline is queued or running: as for a first try, it waits only so long.
                    Instant since = fan.waitingSince == null ? (fan.waitingSince = Instant.now()) : fan.waitingSince;
                    if (Duration.between(since, Instant.now()).compareTo(def.queueTimeout()) > 0) {
                        fan.stopping = true;
                        fan.problem = "Waited " + pretty(def.queueTimeout()) + " for " + s.pipeline() + " to finish its "
                            + "current run, and gave up";
                    }
                    return;
                } catch (IllegalArgumentException | WebApplicationException e) {
                    fan.retryAt.remove(i);
                    fan.ended.put(i, "failed");
                    fan.notStarted.add(i);
                    if (fan.problem == null) {
                        fan.problem = "Item " + (i + 1) + " could not be tried again; " + s.pipeline() + "'s page says why";
                    }
                }
            }
            while (!fan.stopping && fan.next < fan.items.size() && fan.running().size() < s.each().maxParallel()) {
                int i = fan.next;
                if (fan.runs.containsKey(i)) {
                    fan.next++;
                    continue;
                }
                Optional<PipelineStore.Run> again = pipelineStore.flowItemRun(run.id(), s.id(), i);
                if (again.isPresent()) {
                    fan.runs.put(i, again.get().id());
                    fan.next++;
                    continue;
                }
                try {
                    PipelineStore.Run queued = pipelines.queueRunForFlow(s.pipeline(), s.version(), run.requestedBy(),
                        role, run.id(), s.id(), i, itemValues(engine, def, s, fan.items.get(i)), runVariables,
                        s.priority());
                    fan.runs.put(i, queued.id());
                    fan.next++;
                    fan.waitingSince = null;
                } catch (PipelineStore.Busy busy) {
                    // Another run of the pipeline, not one of this node's items, is queued or running.
                    Instant since = fan.waitingSince == null ? (fan.waitingSince = Instant.now()) : fan.waitingSince;
                    if (Duration.between(since, Instant.now()).compareTo(def.queueTimeout()) > 0) {
                        fan.stopping = true;
                        fan.problem = "Waited " + pretty(def.queueTimeout()) + " for " + s.pipeline() + " to finish its "
                            + "current run, and gave up";
                    } else if (fan.running().isEmpty()) {
                        say(def, fan, "Waiting for " + s.pipeline() + " to finish its current run");
                    }
                    return;
                } catch (IllegalArgumentException e) {
                    itemNotStarted(fan, i, "The pipeline " + s.pipeline() + " could not be run with these values: "
                        + e.getMessage());
                } catch (WebApplicationException e) {
                    boolean refused = e.getResponse().getStatus() == Response.Status.CONFLICT.getStatusCode();
                    itemNotStarted(fan, i, refused ? "The pipeline " + s.pipeline() + " has problems with these values ("
                        + itemValuesText(engine, def, s, fan.items.get(i)) + "); check it with them on its page"
                        : "The pipeline " + s.pipeline() + " could not be run; its page says why");
                }
            }
        }

        /**
         * Sets an item whose run failed or timed out to be tried again, when it has tries left and trying
         * again could not write twice; false when it is not (ADR 0041).
         */
        private boolean retryItem(Fan fan, int i, PipelineStore.Run pr) {
            int t = fan.tries.getOrDefault(i, 0);
            if (t >= fan.node.retries() || !mayRetry(pr)) {
                return false;
            }
            fan.tries.put(i, t + 1);
            fan.retryAt.put(i, Instant.now().plus(fan.node.retry().before(t + 1)));
            fan.runs.remove(i);
            fan.cancelAsked.remove(i);
            if (fan.timedOut.contains(i)) {
                untimeItem(fan, i);
            }
            return true;
        }

        /**
         * Whether a failed pipeline run may be tried again: every write of the version it ran replaces or
         * upserts, so a second try writes the same rows (ADR 0041). Checked again here, as the latest
         * version may have changed since the flow was saved. Whether a write had begun is not asked: a
         * run stopped or lost mid-write records none.
         */
        private boolean mayRetry(PipelineStore.Run pr) {
            return pipelineStore.version(pr.pipeline(), pr.version())
                .map(v -> unsafeWrite(PipelineDefinition.parse(v.yaml()).definition()) == null).orElse(false);
        }

        /** "Try 1 of 4 failed; trying again in 1m": what a node waiting to be tried again says. */
        private String tryingAgain(FlowDefinition.Node s, int next) {
            return "Try " + next + " of " + (s.retries() + 1) + " failed; trying again in "
                + FlowDefinition.written(s.retry().before(next));
        }

        /** Records that an item's run took longer than the node's timeout; the node's message names the first. */
        private void timeOutItem(Fan fan, int i) {
            if (fan.timedOut.add(i) && fan.problem == null) {
                fan.problem = "Item " + (i + 1) + " took longer than " + FlowDefinition.written(fan.node.timeout())
                    + " and was stopped";
            }
        }

        /** An item recorded as timed out whose run succeeded all the same: it is not timed out after all. */
        private void untimeItem(Fan fan, int i) {
            fan.timedOut.remove(i);
            if (fan.problem != null && fan.problem.startsWith("Item " + (i + 1) + " took longer than")) {
                fan.problem = null;
                if (!fan.timedOut.isEmpty()) {
                    int next = fan.timedOut.first();
                    fan.timedOut.remove(next);
                    timeOutItem(fan, next);
                }
            }
        }

        private void itemNotStarted(Fan fan, int i, String why) {
            fan.ended.put(i, "failed");
            fan.notStarted.add(i);
            fan.next++;
            if (fan.problem == null) {
                fan.problem = "Item " + (i + 1) + ": " + why;
            }
        }

        /**
         * What the node's row records besides what conditions read: the items that failed without a
         * run, so the run's page shows them as failed rather than not started.
         */
        private Map<String, Object> recorded(Fan fan, Map<String, Object> reported) {
            Map<String, Object> out = new LinkedHashMap<>(reported);
            if (!fan.notStarted.isEmpty()) {
                out.put(NOT_STARTED, List.copyOf(fan.notStarted));
            }
            if (!fan.timedOut.isEmpty()) {
                out.put(TIMED_OUT_ITEMS, List.copyOf(fan.timedOut));
            }
            if (!fan.retryAt.isEmpty()) {
                out.put(RETRYING_ITEMS, List.copyOf(fan.retryAt.keySet()));
            }
            return out;
        }

        /** Saves the node's message when it changes. */
        private void say(FlowDefinition def, Fan fan, String message) {
            String key = message + fan.retryAt.keySet();
            if (!key.equals(fan.said)) {
                fan.said = key;
                save(def, fan.node.id(), "running", null, null, message, recorded(fan, Map.of()));
            }
        }

        /** Ends a for-each node: conditions read the counts; its row also records the items that never started. */
        private void endFan(FlowDefinition def, FlowEngine engine, Fan fan, FlowEngine.State state, String message,
                            Map<String, Object> reported) {
            engine.finish(fan.node.id(), state, reported);
            nodeWasCancelled |= state == FlowEngine.State.CANCELLED;
            save(def, fan.node.id(), state.name().toLowerCase(Locale.ROOT), null, Instant.now(), message,
                recorded(fan, reported));
        }

        /**
         * Looks at a for-each node's items: records those that ended, starts more, and ends the node
         * once nothing more will start and none is running. It succeeds when every item succeeded, fails
         * when any failed (after the others end), and is cancelled with the flow, or when someone
         * cancels one of its runs by hand.
         */
        private void pollEach(FlowDefinition def, FlowEngine engine, Fan fan, AccessRole role, boolean cancelling) {
            boolean stopped = cancelling || engine.halted();
            if (stopped) {
                fan.stopping = true;
            }
            // A copy: an item tried again leaves runs while they are looked through.
            for (Map.Entry<Integer, Long> e : new ArrayList<>(fan.runs.entrySet())) {
                if (fan.ended.containsKey(e.getKey())) {
                    continue;
                }
                Optional<PipelineStore.Run> pr = pipelineStore.run(e.getValue());
                if (pr.isEmpty()) {
                    fan.ended.put(e.getKey(), "failed");
                } else if (pr.get().finished()) {
                    String status = pr.get().status();
                    // Only a run this watcher stopped for its timeout is tried again, never a cancel it did not ask for.
                    boolean ownTimeout = fan.timedOut.contains(e.getKey()) && fan.cancelAsked.contains(e.getKey());
                    if (!status.equals("succeeded") && !fan.cancelAsked.contains(e.getKey())
                        && ranPast(fan.node.timeout(), pr.get())) {
                        timeOutItem(fan, e.getKey()); // found again after the worker running the flow changed
                    }
                    if (!status.equals("succeeded") && !stopped && !fan.byHand
                        && (status.equals("failed") || ownTimeout) && retryItem(fan, e.getKey(), pr.get())) {
                        continue;
                    }
                    if (fan.timedOut.contains(e.getKey())) {
                        if (!status.equals("succeeded")) {
                            fan.ended.put(e.getKey(), "failed");
                            continue;
                        }
                        untimeItem(fan, e.getKey()); // it finished as it was stopped: it succeeded
                    }
                    fan.ended.put(e.getKey(), status.equals("succeeded") || status.equals("cancelled") ? status : "failed");
                    if (status.equals("cancelled") && !fan.cancelAsked.contains(e.getKey()) && !stopped) {
                        // Someone cancelled an item's run: the node, and so the flow, stops.
                        fan.byHand = true;
                        fan.stopping = true;
                    }
                } else if (!stopped && overdue(fan.node.timeout(), pr.get().startedAt())
                    && !fan.timedOut.contains(e.getKey())) {
                    // Took longer than the node's timeout: its run is cancelled, and the item counts as failed.
                    timeOutItem(fan, e.getKey());
                    if (fan.cancelAsked.add(e.getKey())) {
                        cancelPipelineRun(e.getValue());
                    }
                }
            }
            if (fan.byHand || stopped) {
                fan.runs.forEach((i, id) -> {
                    if (!fan.ended.containsKey(i) && fan.cancelAsked.add(i)) {
                        cancelPipelineRun(id);
                    }
                });
            }
            if (fan.stopping) {
                // Items waiting to be tried again are not: their last try failed.
                fan.retryAt.keySet().forEach(i -> fan.ended.put(i, "failed"));
                fan.retryAt.clear();
            }
            fill(def, engine, fan, role);
            if (!fan.running().isEmpty() || (!fan.stopping && (fan.next < fan.items.size() || !fan.retryAt.isEmpty()))) {
                if (!fan.running().isEmpty() || fan.waitingSince == null) {
                    say(def, fan, fan.progress());
                }
                return;
            }
            long succeeded = fan.count("succeeded");
            long failed = fan.count("failed");
            Map<String, Object> reported = new LinkedHashMap<>();
            reported.put("items", (double) fan.items.size());
            reported.put("succeeded", (double) succeeded);
            reported.put("failed", (double) failed);
            fans.remove(fan.node.id());
            int n = fan.items.size();
            String of = " of " + n + (n == 1 ? " item" : " items");
            if (succeeded == n) {
                endFan(def, engine, fan, FlowEngine.State.SUCCEEDED, succeeded + of + " succeeded", reported);
            } else if (fan.byHand || stopped) {
                endFan(def, engine, fan, FlowEngine.State.CANCELLED, (fan.byHand ? "A run of one of its items was "
                    + "cancelled" : "Cancelled") + ": " + fan.ended.size() + of + " had ended, " + succeeded
                    + " succeeded", reported);
            } else {
                String why = failed > 0 ? failed + of + " failed; their pipeline runs say why"
                    : (n - fan.ended.size()) + of + " did not run";
                endFan(def, engine, fan, FlowEngine.State.FAILED, why + (fan.problem == null ? "" : ". " + fan.problem),
                    reported);
            }
        }

        /** The values one item's run is given: the node's, as a run-pipeline node's, with the item in {@code as}. */
        private Map<String, Object> itemValues(FlowEngine engine, FlowDefinition def, FlowDefinition.Node s, Object item) {
            Map<String, Object> out = withValues(engine, def, s);
            out.put(s.each().as(), item);
            return out;
        }

        private String itemValuesText(FlowEngine engine, FlowDefinition def, FlowDefinition.Node s, Object item) {
            try {
                List<String> out = new ArrayList<>();
                itemValues(engine, def, s, item).forEach((k, v) -> out.add(k + "=" + (v instanceof Double d
                    ? Parameters.numberText(d) : v)));
                String text = String.join(", ", out);
                return text.length() > 300 ? text.substring(0, 300) + "…" : text;
            } catch (IllegalArgumentException e) {
                return "values that could not be worked out";
            }
        }

        private void cancelPipelineRun(long id) {
            try {
                pipelines.cancel(id, run.requestedBy());
            } catch (RuntimeException e) {
                // already finished: the next look sees how it ended
            }
        }

        /**
         * Starts a sql node once it has a place on its source, when the source limits runs at once
         * (ADR 0040); until then it waits, saying so, and is tried again on each look at the flow.
         */
        private void trySql(FlowDefinition def, FlowDefinition.Node s) {
            JobQueue.SourceLimit limit = lease == null || sqlSources == null ? null : sqlSources.source(s.source())
                .map(src -> {
                    Integer max = io.kilnmark.server.sources.CommonSettings.maxRuns(src.settings());
                    return max == null ? null : new JobQueue.SourceLimit(src.id(), src.name(), max);
                }).orElse(null);
            if (limit != null) {
                JobQueue.Places places = lease.takePlaces(List.of(limit));
                if (!places.taken()) {
                    if (sqlWaiting.add(s.id())) {
                        save(def, s.id(), "running", null, null, PipelineService.waitingFor(s.source()), Map.of());
                    }
                    return;
                }
                sqlPlaces.put(s.id(), places.keys());
            }
            sqlWaiting.remove(s.id());
            startSql(def, s);
        }

        /**
         * Starts a sql node on a thread of its own, as the person who started the flow, and records that it
         * ran in the audit log: who, the flow and run, the source and how many statements, never their text.
         */
        private void startSql(FlowDefinition def, FlowDefinition.Node s) {
            save(def, s.id(), "running", null, null, "Running " + s.statements().size()
                + (s.statements().size() == 1 ? " statement" : " statements") + " on " + s.source(), Map.of());
            SqlAction.Control control = new SqlAction.Control();
            sqlControls.put(s.id(), control);
            sqlStartedAt.put(s.id(), Instant.now());
            java.util.concurrent.CompletableFuture<SqlAction.Outcome> done = new java.util.concurrent.CompletableFuture<>();
            sqlRuns.put(s.id(), done);
            Duration timeout = config == null ? Duration.ofMinutes(30) : config.pipelines().sqlStatementTimeout();
            Thread.ofVirtual().name("flow-" + run.id() + "-sql-" + s.id()).start(() -> {
                try {
                    done.complete(sqlSources == null ? SqlAction.Outcome.failed("Node " + s.id() + ": this server "
                        + "has no sources to run SQL on") : SqlAction.run(s, sqlSources, timeout, control));
                } catch (RuntimeException e) {
                    LOG.errorf("Flow run %d, sql node %s failed with %s", run.id(), s.id(), e.getClass().getName());
                    done.complete(SqlAction.Outcome.failed("Node " + s.id() + " could not be run: an internal error. "
                        + "Look at the server's log."));
                }
            });
            if (audit != null) {
                audit.record(new io.kilnmark.server.audit.AuditLog.Actor(run.requestedBy(), null),
                    io.kilnmark.server.audit.AuditLog.FLOW_SQL, run.flow() + "/" + run.id() + "/" + s.id(),
                    Map.of("source", s.source(), "statements", s.statements().size(), "version", run.version()));
            }
        }

        /**
         * Starts a check-source node on a thread of its own (ADR 0041): it holds no worker slot, and a
         * connection only while it checks. The connection test is bounded as a person's test is.
         */
        private void startCheck(FlowDefinition def, FlowDefinition.Node s) {
            save(def, s.id(), "running", null, null, "Checking " + s.source(), Map.of());
            Duration connect = config == null ? Duration.ofSeconds(15) : config.sources().checkTimeout();
            java.util.concurrent.CompletableFuture<CheckSource.Outcome> done = new java.util.concurrent.CompletableFuture<>();
            checkRuns.put(s.id(), done);
            checkThreads.put(s.id(), Thread.ofVirtual().name("flow-" + run.id() + "-check-" + s.id()).start(() -> {
                try {
                    done.complete(CheckSource.run(s, sqlSources, connect));
                } catch (RuntimeException e) {
                    LOG.errorf("Flow run %d, check-source node %s failed with %s", run.id(), s.id(),
                        e.getClass().getName());
                    done.complete(CheckSource.Outcome.unreachable("Node " + s.id() + " could not check its source: an "
                        + "internal error. Look at the server's log."));
                }
            }));
        }

        /**
         * Ends a check-source node whose check has finished: succeeded when everything named is there,
         * failed (with {@code missing}) when something is not, or when the source could not be reached.
         * A flow that stops ends it at once, cancelled; so does its timeout (or, with none,
         * {@link CheckSource#DEFAULT_LIMIT}), failed: its thread is interrupted and left to end.
         */
        private void pollCheck(FlowDefinition def, FlowEngine engine, String node, boolean cancelling) {
            java.util.concurrent.CompletableFuture<CheckSource.Outcome> f = checkRuns.get(node);
            Thread thread = checkThreads.get(node);
            if (f != null && !f.isDone() && thread != null) {
                // A source that hangs may not let the thread stop when asked: the node ends now all the same.
                Duration limit = def.node(node).timeout() == null ? CheckSource.DEFAULT_LIMIT : def.node(node).timeout();
                if (cancelling || engine.halted()) {
                    checksStopped.add(node);
                } else if (overdue(limit, started.get(node))) {
                    timedOut.add(node);
                } else {
                    return;
                }
                thread.interrupt();
                checkRuns.remove(node);
                checkThreads.remove(node);
                if (checksStopped.contains(node)) {
                    end(def, engine, node, FlowEngine.State.CANCELLED, null, "Stopped with the flow", Map.of());
                } else {
                    end(def, engine, node, FlowEngine.State.FAILED, null, tookTooLong(limit), Map.of(), TIMED_OUT_RECORD);
                }
                return;
            }
            if (f == null || !f.isDone()) {
                return;
            }
            CheckSource.Outcome o = f.join();
            checkRuns.remove(node);
            checkThreads.remove(node);
            Map<String, Object> reported = o.missing() == null ? Map.of() : Map.of("missing", (double) o.missing());
            end(def, engine, node, o.succeeded() ? FlowEngine.State.SUCCEEDED : FlowEngine.State.FAILED, null,
                o.message(), reported);
        }

        /** What a wait-for node waits for, in the flow's words. */
        private static String waitingFor(FlowDefinition.Node s) {
            return s.until().file() != null
                ? (s.until().atLeast() == 1 ? "a file" : s.until().atLeast() + " files") + " matching " + s.until().file()
                    + " on " + s.source()
                : "a query on " + s.source() + " to be true";
        }

        /** Starts one look by a wait-for node, on a thread of its own: it holds a connection only while it looks. */
        private void startLook(FlowDefinition.Node s) {
            Duration connect = config == null ? Duration.ofSeconds(15) : config.sources().checkTimeout();
            java.util.concurrent.CompletableFuture<WaitFor.Look> done = new java.util.concurrent.CompletableFuture<>();
            looks.put(s.id(), done);
            lookThreads.put(s.id(), Thread.ofVirtual().name("flow-" + run.id() + "-wait-" + s.id()).start(() -> {
                try {
                    done.complete(WaitFor.look(s, sqlSources, connect));
                } catch (RuntimeException e) {
                    LOG.errorf("Flow run %d, wait-for node %s failed with %s", run.id(), s.id(), e.getClass().getName());
                    done.complete(WaitFor.Look.problem("The look could not be made: an internal error. Look at the "
                        + "server's log."));
                }
            }));
        }

        /**
         * A wait-for node: looks at once, then every {@code every}, until it finds what it waits for
         * (succeeded) or its {@code timeout} passes (failed, saying it gave up). A look that could not be
         * made is tried again at the next; one that never can (the source gone, SQL no longer allowed)
         * fails it at once. Cancelling or stopping the flow ends the wait at once. It reports
         * {@code found} and {@code waited_s}.
         */
        private void pollWait(FlowDefinition def, FlowEngine engine, FlowDefinition.Node s, boolean cancelling) {
            String node = s.id();
            Instant now = Instant.now();
            double waited = Duration.between(started.get(node), now).toMillis() / 1000.0;
            java.util.concurrent.CompletableFuture<WaitFor.Look> f = looks.get(node);
            if (cancelling || engine.halted()) {
                stopLooking(node);
                end(def, engine, node, FlowEngine.State.CANCELLED, null, "Stopped waiting: the flow was stopped", Map.of());
                return;
            }
            Map<String, Object> reported = new LinkedHashMap<>();
            reported.put("found", (double) lastFound.getOrDefault(node, 0));
            reported.put("waited_s", Math.floor(waited));
            boolean past = Duration.between(started.get(node), now).compareTo(s.timeout()) >= 0;
            if (f != null && f.isDone()) {
                WaitFor.Look look = f.join();
                looks.remove(node);
                lookThreads.remove(node);
                if (look.refused() != null) {
                    end(def, engine, node, FlowEngine.State.FAILED, null, look.refused(), Map.of());
                    return;
                }
                if (look.problem() == null) {
                    lastFound.put(node, look.found());
                    reported.put("found", (double) look.found());
                    if (look.found() >= (s.until().file() != null ? s.until().atLeast() : 1)) {
                        end(def, engine, node, FlowEngine.State.SUCCEEDED, null, (s.until().file() != null
                            ? look.found() + (look.found() == 1 ? " file matches " : " files match ") + s.until().file()
                            : "The query on " + s.source() + " is true") + " after waiting " + pretty(Duration.ofSeconds(
                                (long) waited)), reported);
                        return;
                    }
                }
                nextLook.put(node, now.plus(s.until().every()));
                String so = look.problem() != null ? look.problem() + "; looking again"
                    : s.until().file() != null ? look.found() + " of " + s.until().atLeast() + " so far"
                    : "Not yet true";
                save(def, node, "running", null, null, "Waiting for " + waitingFor(s) + ". " + so, reported);
            }
            if (past) {
                stopLooking(node);
                end(def, engine, node, FlowEngine.State.FAILED, null, "Waited " + pretty(s.timeout()) + " for "
                    + waitingFor(s) + ", and gave up", reported);
                return;
            }
            if (!looks.containsKey(node) && !now.isBefore(nextLook.getOrDefault(node, now))) {
                startLook(s);
            }
        }

        /** Ends a wait-for node's look in progress, if any: the thread is interrupted and left. */
        private void stopLooking(String node) {
            Thread t = lookThreads.remove(node);
            if (t != null) {
                t.interrupt();
            }
            looks.remove(node);
        }

        /** Ends a sql node whose thread has finished: with the rows it changed, or why it did not. */
        private void pollSql(FlowDefinition def, FlowEngine engine, String node, boolean cancelling) {
            if (retryAt.containsKey(node)) {
                if (cancelling || engine.halted()) {
                    retryAt.remove(node);
                    end(def, engine, node, FlowEngine.State.CANCELLED, null, "Not tried again: the flow was stopped",
                        Map.of(), triesRecord(node));
                } else if (!Instant.now().isBefore(retryAt.get(node))) {
                    retryAt.remove(node);
                    trySql(def, def.node(node));
                }
                return;
            }
            if (sqlWaiting.contains(node)) {
                if (cancelling || engine.halted()) {
                    sqlWaiting.remove(node);
                    end(def, engine, node, FlowEngine.State.CANCELLED, null, "Not started: the flow was stopped",
                        Map.of());
                } else {
                    trySql(def, def.node(node));
                }
                return;
            }
            SqlAction.Control control = sqlControls.get(node);
            java.util.concurrent.CompletableFuture<SqlAction.Outcome> f = sqlRuns.get(node);
            if ((cancelling || engine.halted()) && control != null && !control.stopped()) {
                control.stop();
            } else if (control != null && !control.stopped() && f != null && !f.isDone()
                && overdue(def.node(node).timeout(), sqlStartedAt.get(node))) {
                // Took longer than its timeout: its statement is cancelled and its transaction rolled back. One that
                // has already ended, however, keeps its own outcome.
                timedOut.add(node);
                control.stop();
            }
            if (f == null || !f.isDone()) {
                return;
            }
            SqlAction.Outcome o = f.join();
            sqlRuns.remove(node);
            sqlControls.remove(node);
            sqlStartedAt.remove(node);
            List<Long> places = sqlPlaces.remove(node);
            if (places != null) {
                lease.givePlaces(places);
            }
            FlowDefinition.Node s = def.node(node);
            boolean late = !o.succeeded() && timedOut.contains(node);
            boolean stopped = !late && control != null && control.stopped();
            int t = tries.getOrDefault(node, 0);
            if (!o.succeeded() && !stopped && !o.inDoubt() && !cancelling && !engine.halted() && t < s.retries()) {
                // Its statements run in one transaction, so a failed try changed nothing: it may be tried again.
                tries.put(node, t + 1);
                retryAt.put(node, Instant.now().plus(s.retry().before(t + 1)));
                timedOut.remove(node);
                save(def, node, "running", null, null, (late ? tookTooLong(s.timeout()) : o.message()) + ". "
                    + tryingAgain(s, t + 1), Map.of());
                return;
            }
            Map<String, Object> recorded = new LinkedHashMap<>(triesRecord(node));
            if (o.succeeded()) {
                end(def, engine, node, FlowEngine.State.SUCCEEDED, null, o.message(),
                    Map.of("rows_affected", (double) o.rowsAffected()), recorded);
            } else if (late) {
                recorded.putAll(TIMED_OUT_RECORD);
                end(def, engine, node, FlowEngine.State.FAILED, null, tookTooLong(s.timeout())
                    + "; nothing was changed, as its statements run in one transaction" + afterTries(node), Map.of(),
                    recorded);
            } else if (stopped) {
                end(def, engine, node, FlowEngine.State.CANCELLED, null, o.message(), Map.of(), recorded);
            } else {
                end(def, engine, node, FlowEngine.State.FAILED, null, o.message() + afterTries(node), Map.of(),
                    recorded);
            }
        }

        /** A notification's words with the run's values put in; a name without one stays as written. */
        private String words(FlowEngine engine, String text) {
            Map<String, Object> values = scopeNow(engine);
            for (String name : Parameters.names(text)) {
                values.putIfAbsent(name, "unknown"); // nothing measured it, or its node did not report it
            }
            Parameters.Filled f = Parameters.fill(text, values, Parameters.Use.WORDS);
            return f.problem() == null ? f.text() : text;
        }

        /**
         * What ${...} reads as a node starts: the run's parameters and variables, and each node that has
         * ended by then as node.status and the values it reported (node.rows_written, ...).
         */
        private Map<String, Object> scopeNow(FlowEngine engine) {
            Map<String, Object> out = new LinkedHashMap<>(scope);
            for (String n : started.keySet()) {
                FlowEngine.State st = engine.state(n);
                if (st == FlowEngine.State.SUCCEEDED || st == FlowEngine.State.FAILED || st == FlowEngine.State.CANCELLED) {
                    out.put(n + ".status", st.name().toLowerCase(Locale.ROOT));
                    engine.results(n).forEach((k, v) -> {
                        if (v != null) {
                            out.put(n + "." + k, v);
                        }
                    });
                }
            }
            return out;
        }

        /**
         * The values a run-pipeline node gives its pipeline: each as written, with the run's parameters and
         * variables put in (a value that is one ${name} keeps that value's kind).
         */
        private Map<String, Object> withValues(FlowEngine engine, FlowDefinition def, FlowDefinition.Node s) {
            Map<String, Object> out = new LinkedHashMap<>();
            Map<String, Object> scope = scopeNow(engine);
            // A pipeline parameter the node does not set takes the flow's parameter of the same name, if any.
            for (String name : pipelineParameters(s)) {
                if (!s.with().containsKey(name) && def.parameter(name) != null && this.scope.containsKey(name)) {
                    out.put(name, this.scope.get(name));
                }
            }
            for (Map.Entry<String, Object> e : s.with().entrySet()) {
                Object v = e.getValue();
                if (v instanceof String t) {
                    String whole = Parameters.whole(t);
                    if (whole != null && scope.containsKey(whole)) {
                        v = scope.get(whole);
                    } else {
                        Parameters.Filled f = Parameters.fill(t, scope, Parameters.Use.WORDS);
                        if (f.problem() != null) {
                            // A node value is missing when nothing measured it (a score with no rules).
                            throw new IllegalArgumentException(e.getKey() + " " + f.problem()
                                .replace("which is not declared", "which has no value in this run"));
                        }
                        v = f.text();
                    }
                }
                out.put(e.getKey(), v);
            }
            return out;
        }

        /**
         * The parameters the pipeline a node runs declares (that version's, or its latest), read once
         * per node and run: a node waiting for a busy pipeline asks again and again.
         */
        private List<String> pipelineParameters(FlowDefinition.Node s) {
            return declared.computeIfAbsent(s.id(), k -> pipelineStore.pipeline(s.pipeline())
                .flatMap(p -> pipelineStore.version(s.pipeline(), s.version() == null ? p.latestVersion() : s.version()))
                .map(v -> PipelineDefinition.parse(v.yaml()).definition())
                .map(d -> d.parameters().stream().map(Parameters.Parameter::name).toList())
                .orElse(List.of()));
        }

        /** The values a node gives its pipeline, as name=value, for its message. */
        private String valuesText(FlowEngine engine, FlowDefinition def, FlowDefinition.Node s) {
            try {
                List<String> out = new ArrayList<>();
                withValues(engine, def, s).forEach((k, v) -> out.add(k + "=" + (v instanceof Double d
                    ? Parameters.numberText(d) : v)));
                String text = String.join(", ", out);
                return text.isEmpty() ? "the defaults" : text.length() > 300 ? text.substring(0, 300) + "…" : text;
            } catch (IllegalArgumentException e) {
                return "values that could not be worked out";
            }
        }

        /** Queues the node's pipeline run, or notes that it must wait for the pipeline's current one. */
        private void tryQueue(FlowDefinition def, FlowEngine engine, FlowDefinition.Node s, AccessRole role) {
            // Found again after the worker running the flow changed: the latest try, and so its number.
            int latest = pipelineStore.flowLatestTry(run.id(), s.id(), null);
            int t = Math.max(tries.getOrDefault(s.id(), 0), latest);
            tries.put(s.id(), t);
            Optional<PipelineStore.Run> existing = latest == t ? pipelineStore.flowNodeRun(run.id(), s.id())
                : Optional.empty();
            if (existing.isPresent()) {
                pipelineRuns.put(s.id(), existing.get().id());
                return;
            }
            try {
                PipelineStore.Run queued = pipelines.queueRunForFlow(s.pipeline(), s.version(), run.requestedBy(), role,
                    run.id(), s.id(), null, withValues(engine, def, s), runVariables, s.priority(), t);
                pipelineRuns.put(s.id(), queued.id());
                waitingSince.remove(s.id());
                save(def, s.id(), "running", queued.id(), null, null, Map.of());
            } catch (PipelineStore.Busy busy) {
                boolean first = !waitingSince.containsKey(s.id());
                Instant since = waitingSince.computeIfAbsent(s.id(), k -> Instant.now());
                if (Duration.between(since, Instant.now()).compareTo(def.queueTimeout()) > 0) {
                    waitingSince.remove(s.id());
                    end(def, engine, s.id(), FlowEngine.State.FAILED, null, "Waited " + pretty(def.queueTimeout())
                        + " for " + s.pipeline() + " to finish its current run, and gave up" + afterTries(s.id()),
                        Map.of(), triesRecord(s.id()));
                } else if (first) {
                    save(def, s.id(), "running", null, null, "Waiting for " + s.pipeline()
                        + " to finish its current run", Map.of());
                }
            } catch (IllegalArgumentException e) {
                // The flow's own values were wrong for the pipeline: the flow's words, never the data's.
                end(def, engine, s.id(), FlowEngine.State.FAILED, null, "The pipeline " + s.pipeline()
                    + " could not be run with these values: " + e.getMessage(), Map.of(), triesRecord(s.id()));
            } catch (WebApplicationException e) {
                boolean refused = e.getResponse().getStatus() == Response.Status.CONFLICT.getStatusCode();
                if (refused && (!s.with().isEmpty() || !pipelineParameters(s).isEmpty())) {
                    // No run exists to say why: name the values, which are the flow's own, so it can be checked
                    // on the pipeline's page with them.
                    end(def, engine, s.id(), FlowEngine.State.FAILED, null, "The pipeline " + s.pipeline()
                        + " has problems with these values (" + valuesText(engine, def, s) + "); check it with them "
                        + "on its page", Map.of(), triesRecord(s.id()));
                    return;
                }
                // Why can name sources and fields the flow's readers may not see: the pipeline's own page says.
                end(def, engine, s.id(), FlowEngine.State.FAILED, null, "The pipeline " + s.pipeline()
                    + " could not be run; its page says why", Map.of(), triesRecord(s.id()));
            }
        }

        private void poll(FlowDefinition def, FlowEngine engine, String node, AccessRole role, boolean cancelling) {
            if (engine.state(node) != FlowEngine.State.RUNNING) {
                return;
            }
            FlowDefinition.Node s = def.node(node);
            if (FlowDefinition.SQL.equals(s.type())) {
                pollSql(def, engine, node, cancelling);
                return;
            }
            if (FlowDefinition.CHECK_SOURCE.equals(s.type())) {
                pollCheck(def, engine, node, cancelling);
                return;
            }
            if (FlowDefinition.WAIT_FOR.equals(s.type())) {
                pollWait(def, engine, s, cancelling);
                return;
            }
            if (FlowDefinition.FOR_EACH.equals(s.type())) {
                Fan fan = fans.get(node);
                if (fan != null) {
                    pollEach(def, engine, fan, role, cancelling);
                }
                return;
            }
            if (FlowDefinition.WAIT.equals(s.type())) {
                if (cancelling || engine.halted()) {
                    end(def, engine, node, FlowEngine.State.CANCELLED, null, "Stopped waiting: the flow was stopped",
                        Map.of());
                } else if (Duration.between(started.get(node), Instant.now()).compareTo(s.waitFor()) >= 0) {
                    end(def, engine, node, FlowEngine.State.SUCCEEDED, null, "Waited " + pretty(s.waitFor()), Map.of());
                }
                return;
            }
            Long prId = pipelineRuns.get(node);
            if (prId == null && retryAt.containsKey(node)) {
                if (cancelling || engine.halted()) {
                    retryAt.remove(node);
                    end(def, engine, node, FlowEngine.State.CANCELLED, null, "Not tried again: the flow was stopped",
                        Map.of(), triesRecord(node));
                } else if (!Instant.now().isBefore(retryAt.get(node))) {
                    retryAt.remove(node);
                    tryQueue(def, engine, s, role);
                }
                return;
            }
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
                if (!cancelling && !engine.halted() && !timedOut.contains(node)
                    && overdue(s.timeout(), pr.get().startedAt())) {
                    // Took longer than its timeout: its run is cancelled as a person's cancel would, and the node
                    // fails once the run has stopped, so nothing after it starts while it may still be writing.
                    timedOut.add(node);
                    if (cancelAsked.add(node)) {
                        cancelPipelineRun(prId);
                    }
                    save(def, node, "running", prId, null, tookTooLong(s.timeout()) + "; its pipeline run is being "
                        + "cancelled", Map.of());
                }
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
            // Only a run this watcher stopped for its timeout is tried again, never a cancel it did not ask for.
            boolean ownTimeout = timedOut.contains(node) && cancelAsked.contains(node);
            boolean late = !status.equals("succeeded") && (timedOut.contains(node)
                || (!cancelAsked.contains(node) && ranPast(s.timeout(), pr.get())));
            int t = tries.getOrDefault(node, 0);
            if ((ownTimeout || status.equals("failed")) && !status.equals("succeeded") && !cancelling && !engine.halted() && t < s.retries()
                && mayRetry(pr.get())) {
                // Tried again (ADR 0041): never after a cancel by a person or the flow.
                tries.put(node, t + 1);
                retryAt.put(node, Instant.now().plus(s.retry().before(t + 1)));
                pipelineRuns.remove(node);
                timedOut.remove(node);
                cancelAsked.remove(node);
                save(def, node, "running", prId, null, (late ? tookTooLong(s.timeout()) + ". " : "")
                    + tryingAgain(s, t + 1), Map.of());
                return;
            }
            Map<String, Object> recorded = new LinkedHashMap<>(triesRecord(node));
            if (late) {
                timedOut.add(node);
                recorded.putAll(TIMED_OUT_RECORD);
                end(def, engine, node, FlowEngine.State.FAILED, prId, tookTooLong(s.timeout()) + "; its pipeline run "
                    + "was cancelled" + afterTries(node), reported, recorded);
                return;
            }
            // Why a pipeline run failed may quote data; its own page shows it to those who may see it.
            switch (status) {
                case "succeeded" -> end(def, engine, node, FlowEngine.State.SUCCEEDED, prId, t > 0 ? "Succeeded at "
                    + "try " + (t + 1) + " of " + (s.retries() + 1) : null, reported, recorded);
                case "cancelled" -> end(def, engine, node, FlowEngine.State.CANCELLED, prId,
                    "The pipeline run was cancelled", reported, recorded);
                default -> end(def, engine, node, FlowEngine.State.FAILED, prId,
                    "The pipeline run failed; its run page says why" + afterTries(node), reported, recorded);
            }
        }

        /** What a node's row records of its tries, which conditions never read: {@code tries} once tried again. */
        private Map<String, Object> triesRecord(String node) {
            int t = tries.getOrDefault(node, 0);
            return t == 0 ? Map.of() : Map.of("tries", (double) (t + 1));
        }

        /** ", after 3 tries" for a node that was tried again; empty for one that was not. */
        private String afterTries(String node) {
            int t = tries.getOrDefault(node, 0);
            return t == 0 ? "" : ", after " + (t + 1) + " tries";
        }

        private void cancelRunning(FlowEngine engine) {
            for (String node : engine.running()) {
                Fan fan = fans.get(node);
                if (fan != null) {
                    fan.stopping = true;
                    fan.runs.forEach((i, id) -> {
                        if (!fan.ended.containsKey(i) && fan.cancelAsked.add(i)) {
                            cancelPipelineRun(id);
                        }
                    });
                    continue;
                }
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
            end(def, engine, node, state, prId, message, reported, Map.of());
        }

        /** Ends a node; its row also records {@code recorded}, which conditions never read (that it timed out). */
        private void end(FlowDefinition def, FlowEngine engine, String node, FlowEngine.State state, Long prId,
                         String message, Map<String, Object> reported, Map<String, Object> recorded) {
            engine.finish(node, state, reported);
            nodeWasCancelled |= state == FlowEngine.State.CANCELLED;
            Map<String, Object> row = new LinkedHashMap<>(reported);
            row.putAll(recorded);
            save(def, node, state.name().toLowerCase(Locale.ROOT), prId, Instant.now(), message, row);
        }

        /**
         * Whether a run that started at {@code since} has taken longer than {@code timeout}. A run not yet
         * started (waiting for a worker, a source's place) has not: its timeout counts from its start.
         */
        private boolean overdue(Duration timeout, Instant since) {
            return timeout != null && since != null && Duration.between(since, Instant.now()).compareTo(timeout) > 0;
        }

        /**
         * Whether a pipeline run that ended cancelled ran for at least {@code timeout}: one this flow
         * stopped for its timeout, found again by a worker that took the flow over and so never asked.
         * Callers ask only about runs this watcher did not cancel itself (a flow cancel landing late).
         */
        private boolean ranPast(Duration timeout, PipelineStore.Run pr) {
            if (timeout == null || !"cancelled".equals(pr.status()) || pr.startedAt() == null || pr.finishedAt() == null) {
                return false;
            }
            // The watcher that stopped it read its own clock, these times are the database's: allow for a little skew.
            Duration slack = timeout.dividedBy(10).compareTo(SKEW) < 0 ? timeout.dividedBy(10) : SKEW;
            return Duration.between(pr.startedAt(), pr.finishedAt()).compareTo(timeout.minus(slack)) >= 0;
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
            LOG.infof("Flow run %d of %s %s", run.id(), run.flow(), outcome.name().toLowerCase(Locale.ROOT));
            store.run(run.id()).ifPresent(r -> told(run.flow(), run.id(), r.status(), r.error()));
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
                if (engine.state(s.id()) == FlowEngine.State.FAILED && !engine.handled(s.id()) && timedOut.contains(s.id())) {
                    return "Node " + s.id() + " took longer than " + FlowDefinition.written(s.timeout()) + " and was "
                        + "stopped";
                }
                if (engine.state(s.id()) == FlowEngine.State.FAILED && !engine.handled(s.id())) {
                    return "Node " + s.id() + " failed" + (s.pipeline() == null ? "" : " (the pipeline " + s.pipeline() + ")");
                }
            }
            return "The flow failed";
        }
    }

    /** Tells of a run's end: a failed run notifies, a run that succeeds clears that. */
    private void told(String flow, long runId, String status, String message) {
        if (notifications != null) {
            notifications.flowRunFinished(flow, runId, status, message);
        }
    }

    private static String pretty(Duration d) {
        long m = d.toMinutes();
        return m >= 1 && d.toSeconds() % 60 == 0 ? m + " minutes" : d.toSeconds() + " seconds";
    }

    /** Stored values (JSON), numbers as Doubles; null when they cannot be read. */
    private Map<String, Object> readParameters(String text) {
        try {
            Map<String, Object> m = json.readValue(text == null ? "{}" : text,
                new TypeReference<LinkedHashMap<String, Object>>() {
                });
            m.replaceAll((k, v) -> v instanceof Number n ? n.doubleValue() : v);
            return m;
        } catch (JsonProcessingException e) {
            return null;
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

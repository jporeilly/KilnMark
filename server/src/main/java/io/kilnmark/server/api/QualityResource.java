package io.kilnmark.server.api;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.kilnmark.graph.store.GraphStore;
import io.kilnmark.graph.store.Records;
import io.kilnmark.kiln.connect.Connectors;
import io.kilnmark.kiln.plugins.jdbc.JdbcConnector;
import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.quality.Masking;
import io.kilnmark.server.quality.Patterns;
import io.kilnmark.server.quality.ProfileFacts;
import io.kilnmark.server.quality.QualityService;
import io.kilnmark.server.quality.QualityStore;
import io.kilnmark.server.quality.Recommendations;
import io.kilnmark.server.quality.Rule;
import io.kilnmark.server.quality.RuleDraft;
import io.kilnmark.server.quality.StandardFormats;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Data quality: scores, measures, patterns, rules and their results, drift, samples, the issue
 * list and exports in open formats. Asset endpoints take the asset as {@code ?urn=}, since URNs of
 * files hold slashes.
 *
 * <p>Viewers read scores, measures, shapes, rules and results without values. Seeing values
 * (samples, example values, failing rows, previews) needs {@value #DATA_ROLE}; columns marked
 * sensitive stay masked unless an admin asks for {@code unmask=true}. Stewards change rules,
 * patterns, baselines and issues.
 */
@Path("/api/v1/quality")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AccessRole.VIEWER_ROLE)
public class QualityResource {

    /** The least role that may see data values. */
    public static final String DATA_ROLE = AccessRole.STEWARD_ROLE;
    /** The least role that may see values of columns marked sensitive. */
    public static final String UNMASK_ROLE = AccessRole.ADMIN_ROLE;
    static final String MASK = Masking.MASK;

    private final GraphStore graph;
    private final QualityStore store;
    private final QualityService quality;
    private final Connectors connectors;
    private final SecurityIdentity identity;
    private final ObjectMapper json;
    private final String version;

    public QualityResource(GraphStore graph, QualityStore store, QualityService quality, Connectors connectors,
                           SecurityIdentity identity, ObjectMapper json,
                           @ConfigProperty(name = "quarkus.application.version") String version) {
        this.graph = graph;
        this.store = store;
        this.quality = quality;
        this.connectors = connectors;
        this.identity = identity;
        this.json = json;
        this.version = version;
    }

    // ---- an asset's quality ----

    /** Scores, measures, value patterns and accepted patterns of an asset. */
    @GET
    @Path("asset")
    public ObjectNode asset(@QueryParam("urn") String urn, @QueryParam("unmask") boolean unmask) {
        Records.AssetDetail a = asset(urn);
        ObjectNode out = json.createObjectNode();
        out.put("urn", a.urn());
        boolean data = identity.hasRole(DATA_ROLE);
        out.put("canSeeData", data);
        out.put("canUnmask", identity.hasRole(UNMASK_ROLE));
        Optional<QualityStore.Version> v = store.latestVersion(urn);
        v.ifPresentOrElse(x -> out.set("version", json.valueToTree(x)), () -> out.putNull("version"));
        ObjectNode scores = out.putObject("scores");
        ArrayNode assetScores = scores.putArray("asset");
        ObjectNode fieldScores = scores.putObject("fields");
        for (QualityStore.ScoreRow s : store.scores(urn)) {
            ObjectNode o = json.createObjectNode().put("dimension", s.dimension()).put("score", s.score());
            o.set("reasons", s.reasons());
            if (s.field().isEmpty()) {
                assetScores.add(o);
            } else {
                fieldScores.withArrayProperty(s.field()).add(o);
            }
        }
        ArrayNode measures = out.putArray("measures");
        v.ifPresent(x -> store.measures(x.id()).forEach(m -> {
            ObjectNode o = measures.addObject().put("field", m.field()).put("dimension", m.dimension())
                .put("score", m.score());
            ObjectNode e = m.evidence() instanceof ObjectNode n ? n.deepCopy() : json.createObjectNode();
            o.put("reason", e.path("reason").asText(""));
            e.remove("reason");
            o.set("evidence", Masking.evidence(e, data));
        }));
        Set<String> sensitive = store.sensitive(urn);
        out.set("sensitive", json.valueToTree(sensitive));
        List<QualityStore.Accepted> accepted = store.accepted(urn);
        out.set("accepted", json.valueToTree(accepted));
        JsonNode examples = data && quality.samplesAllowed(urn)
            ? store.sample(urn).map(QualityStore.Sample::examples).orElse(null) : null;
        boolean showSensitive = unmask && identity.hasRole(UNMASK_ROLE);
        ArrayNode patterns = out.putArray("patterns");
        v.flatMap(x -> store.facts(x.id())).ifPresent(facts -> {
            for (ProfileFacts.FieldFacts f : facts.fields()) {
                if (!f.has("patterns") || f.values() <= 0) {
                    continue;
                }
                ObjectNode p = patterns.addObject();
                p.put("field", f.name());
                p.put("values", f.values());
                p.put("other", Math.max(0, f.get("patterns_other")));
                p.put("collapsed", "true".equals(f.text("patterns_collapsed")));
                p.put("exact", "true".equals(f.text("patterns_exact")));
                boolean masked = sensitive.contains(f.name()) && !showSensitive;
                JsonNode fieldExamples = examples == null ? null : examples.get(f.name());
                ArrayNode shapes = p.putArray("shapes");
                for (ProfileFacts.Counted c : f.patterns()) {
                    ObjectNode s = shapes.addObject().put("shape", c.item()).put("count", c.count())
                        .put("share", Math.round(10_000.0 * c.count() / f.values()) / 10_000.0)
                        .put("regex", Patterns.regex(c.item(), "true".equals(f.text("patterns_collapsed"))))
                        .put("accepted", accepted.stream().anyMatch(x -> x.field().equals(f.name())
                            && x.shape().equals(c.item())));
                    if (fieldExamples != null && fieldExamples.has(c.item())) {
                        s.set("examples", mask(fieldExamples.get(c.item()), masked));
                    }
                }
                if (fieldExamples != null && fieldExamples.has("~other")) {
                    p.set("otherExamples", mask(fieldExamples.get("~other"), masked));
                }
                ArrayNode formats = p.putArray("formats");
                f.formats().forEach((k, n) -> formats.addObject().put("format", k).put("count", n));
                Patterns.Suggestion sug = Patterns.suggest(f.patterns(), f.formats(), f.values(),
                    "true".equals(f.text("patterns_collapsed")));
                if (sug != null) {
                    p.set("suggestion", json.valueToTree(sug));
                }
            }
        });
        return out;
    }

    private ArrayNode mask(JsonNode values, boolean masked) {
        ArrayNode out = json.createArrayNode();
        if (values != null) {
            values.forEach(x -> out.add(masked && !x.isNull() ? json.getNodeFactory().textNode(MASK) : x));
        }
        return out;
    }

    /** Drift events, newest first. */
    @GET
    @Path("asset/drift")
    public List<QualityStore.DriftRow> drift(@QueryParam("urn") String urn, @QueryParam("limit") Integer limit) {
        asset(urn);
        boolean data = identity.hasRole(DATA_ROLE);
        return store.drift(urn, limit == null ? 50 : Math.max(1, Math.min(limit, 500))).stream()
            .map(d -> data ? d : new QualityStore.DriftRow(d.id(), d.assetUrn(), d.field(), d.kind(), d.change(),
                d.severity(), d.message(), Masking.evidence(d.evidence(), false), d.versionId(), d.comparedWith(),
                d.against(), d.detectedAt(), null))
            .toList();
    }

    /** Profile versions, newest first. */
    @GET
    @Path("asset/versions")
    public List<QualityStore.Version> versions(@QueryParam("urn") String urn, @QueryParam("limit") Integer limit) {
        asset(urn);
        return store.versions(urn, limit == null ? 30 : Math.max(1, Math.min(limit, 200)));
    }

    public record BaselineBody(Long versionId) {
    }

    /** Makes a profile version the asset's baseline for drift ({@code versionId} null clears it). */
    @PUT
    @Path("asset/baseline")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(AccessRole.STEWARD_ROLE)
    public Response baseline(@QueryParam("urn") String urn, BaselineBody body) {
        asset(urn);
        Long id = body == null ? null : body.versionId();
        if (id != null && store.versions(urn, 1000).stream().noneMatch(x -> x.id() == id)) {
            throw ApiError.of(Response.Status.NOT_FOUND, "No profile version " + id + " of " + urn);
        }
        store.setBaseline(urn, id);
        return Response.noContent().build();
    }

    /** The sample rows; sensitive columns masked unless an admin unmasks them. */
    @GET
    @Path("asset/samples")
    @RolesAllowed(DATA_ROLE)
    public ObjectNode samples(@QueryParam("urn") String urn, @QueryParam("unmask") boolean unmask) {
        asset(urn);
        ObjectNode out = json.createObjectNode().put("urn", urn);
        Optional<QualityStore.Sample> s = quality.samplesAllowed(urn) ? store.sample(urn) : Optional.empty();
        if (s.isEmpty()) {
            out.put("kept", false);
            out.put("message", "No sample is kept for this asset. Its source may have keep_samples off, or it has not "
                + "been scanned since sampling began.");
            return out;
        }
        Set<String> sensitive = store.sensitive(urn);
        boolean showSensitive = unmask && identity.hasRole(UNMASK_ROLE);
        QualityStore.Sample x = s.get();
        out.put("kept", true);
        out.put("method", x.method());
        out.set("keyFields", json.valueToTree(x.keyFields()));
        out.put("seen", x.seen());
        out.put("takenAt", x.takenAt().toString());
        out.set("columns", x.columns());
        List<String> masked = new ArrayList<>();
        x.columns().forEach(c -> {
            if (sensitive.contains(c.asText()) && !showSensitive) {
                masked.add(c.asText());
            }
        });
        out.set("masked", json.valueToTree(masked));
        out.set("rows", maskRows(x.columns(), x.rows(), sensitive, showSensitive));
        return out;
    }

    private ArrayNode maskRows(JsonNode columns, JsonNode rows, Set<String> sensitive, boolean show) {
        ArrayNode out = json.createArrayNode();
        if (rows == null) {
            return out;
        }
        Set<Integer> hide = new HashSet<>();
        for (int i = 0; columns != null && i < columns.size(); i++) {
            if (sensitive.contains(columns.get(i).asText()) && !show) {
                hide.add(i);
            }
        }
        for (JsonNode row : rows) {
            ArrayNode r = out.addArray();
            for (int i = 0; i < row.size(); i++) {
                r.add(hide.contains(i) && !row.get(i).isNull() ? json.getNodeFactory().textNode(MASK) : row.get(i));
            }
        }
        return out;
    }

    public record SensitiveBody(String field, Boolean sensitive) {
    }

    /** Marks a column sensitive (masked in samples, examples and failing rows) or not. */
    @PUT
    @Path("asset/sensitive")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(AccessRole.STEWARD_ROLE)
    public Response sensitive(@QueryParam("urn") String urn, SensitiveBody body) {
        asset(urn);
        if (body == null || body.field() == null || body.sensitive() == null) {
            throw new IllegalArgumentException("Send {\"field\": \"...\", \"sensitive\": true or false}");
        }
        if (!body.sensitive() && !identity.hasRole(UNMASK_ROLE)) {
            throw ApiError.of(Response.Status.FORBIDDEN, "Only an admin can stop treating a column as sensitive.");
        }
        if (!store.setSensitive(urn, body.field(), body.sensitive())) {
            throw ApiError.of(Response.Status.NOT_FOUND, urn + " has no field called " + body.field());
        }
        return Response.noContent().build();
    }

    public record AcceptBody(String field, String shape, String regex) {
    }

    /** Accepts a value shape for a column; its validity rule is created or extended. */
    @POST
    @Path("asset/patterns")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(AccessRole.STEWARD_ROLE)
    public Response accept(@QueryParam("urn") String urn, AcceptBody body) {
        Records.AssetDetail a = asset(urn);
        if (body == null || body.field() == null) {
            throw new IllegalArgumentException("field: required");
        }
        try {
            QualityStore.Accepted x = quality.accept(urn, body.field(), body.shape(), body.regex(), who(), context(a));
            return Response.status(Response.Status.CREATED).entity(x).build();
        } catch (IllegalStateException e) {
            throw ApiError.of(Response.Status.CONFLICT, e.getMessage());
        }
    }

    @DELETE
    @Path("asset/patterns/{id}")
    @RolesAllowed(AccessRole.STEWARD_ROLE)
    public Response unaccept(@QueryParam("urn") String urn, @PathParam("id") long id) {
        Records.AssetDetail a = asset(urn);
        if (!quality.unaccept(urn, id, who(), context(a))) {
            throw ApiError.of(Response.Status.NOT_FOUND, "No accepted pattern " + id + " on " + urn);
        }
        return Response.noContent().build();
    }

    /** Rules worth adding, from the profile alone. */
    @GET
    @Path("asset/recommendations")
    public List<Recommendations.Recommendation> recommendations(@QueryParam("urn") String urn) {
        asset(urn);
        Optional<ProfileFacts> facts = quality.latestFacts(urn);
        if (facts.isEmpty()) {
            return List.of();
        }
        Set<String> existing = new HashSet<>();
        store.rules(urn, null, false).forEach(r -> existing.add(r.type().id() + ":" + (r.field() == null ? "" : r.field())));
        List<Recommendations.Recommendation> all = Recommendations.of(facts.get(), quality.historyOf(facts.get()), existing,
            quality.settings().defaultCadence());
        if (identity.hasRole(DATA_ROLE)) {
            return all;
        }
        // Allowed-value lists and ranges are values: viewers see that a rule is recommended, not its values.
        return all.stream().map(r -> new Recommendations.Recommendation(r.field(), r.type(), r.dimension(), r.name(),
            r.type().equals("allowed_values") || r.type().equals("in_range") ? json.createObjectNode() : r.params(),
            r.type().equals("allowed_values") || r.type().equals("in_range") ? "Ask a steward to review it" : r.reason()))
            .toList();
    }

    /** Runs the asset's enabled rules now, through the job queue. */
    @POST
    @Path("asset/runs")
    @RolesAllowed(AccessRole.STEWARD_ROLE)
    public Response runAsset(@QueryParam("urn") String urn) {
        asset(urn);
        long id = quality.queueManual(urn, null, who());
        return Response.accepted(store.run(id).orElseThrow()).location(URI.create("/api/v1/quality/runs/" + id)).build();
    }

    @GET
    @Path("runs/{id}")
    public QualityStore.Run run(@PathParam("id") long id) {
        store.failOrphanedRuns();
        return store.run(id).orElseThrow(() -> ApiError.of(Response.Status.NOT_FOUND, "No quality run " + id));
    }

    // ---- rules ----

    /** A rule with its latest result. */
    public record RuleView(Rule rule, QualityStore.Result latest) {
    }

    @GET
    @Path("rules")
    public List<RuleView> rules(@QueryParam("urn") String urn, @QueryParam("source") String source) {
        Map<String, Map<Long, QualityStore.Result>> latest = new LinkedHashMap<>();
        List<RuleView> out = new ArrayList<>();
        for (Rule r : store.rules(blank(urn), blank(source), false)) {
            QualityStore.Result res = latest.computeIfAbsent(r.assetUrn(), store::latestResults).get(r.id());
            out.add(view(r, res));
        }
        return out;
    }

    /** A rule and its latest result as the caller may see them: no values or query text for viewers. */
    private RuleView view(Rule r, QualityStore.Result res) {
        boolean data = identity.hasRole(DATA_ROLE);
        return new RuleView(Masking.rule(r, data), withoutRows(res));
    }

    @POST
    @Path("rules")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(AccessRole.STEWARD_ROLE)
    public Response addRule(RuleDraft body) {
        Rule r = check(body);
        Rule saved = store.insertRule(r);
        return Response.created(URI.create("/api/v1/quality/rules/" + saved.id())).entity(saved).build();
    }

    @GET
    @Path("rules/{id}")
    public RuleView rule(@PathParam("id") long id) {
        Rule r = findRule(id);
        return view(r, store.latestResults(r.assetUrn()).get(id));
    }

    /**
     * Replaces a rule's settings; its asset, origin, author and creation time stay. With
     * {@code If-Match} set to the rule's {@code updatedAt} as last read, the change is refused (409)
     * when someone else has changed the rule since, so an edit never undoes theirs unseen.
     */
    @PUT
    @Path("rules/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(AccessRole.STEWARD_ROLE)
    public Rule updateRule(@PathParam("id") long id, @HeaderParam("If-Match") String ifMatch, RuleDraft body) {
        Rule old = findRule(id);
        adminForCustomSql(old.type());
        java.time.Instant expected = readVersion(ifMatch);
        if (expected != null && !expected.equals(old.updatedAt())) {
            throw changedSince();
        }
        if (body != null && body.assetUrn() != null && !body.assetUrn().equals(old.assetUrn())) {
            throw new IllegalArgumentException("assetUrn: a rule cannot move to another asset; add a new rule there");
        }
        RuleDraft d = body == null ? null : new RuleDraft(old.assetUrn(), body.field(), body.name(), body.type(),
            body.dimension(), body.params(), body.thresholdUnit(), body.warnAbove(), body.failAbove(), body.actions(),
            body.enabled());
        Rule r = check(d);
        Rule saved = store.updateRule(id, r, expected).orElseThrow(() -> store.rule(id).isPresent() ? changedSince()
            : ApiError.of(Response.Status.NOT_FOUND, "No rule " + id));
        quality.rescore(old.assetUrn());
        return saved;
    }

    /** The {@code updatedAt} sent in {@code If-Match} (quotes and a weak W/ prefix allowed), or null for none. */
    static java.time.Instant readVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank() || ifMatch.trim().equals("*")) {
            return null;
        }
        String v = ifMatch.trim();
        if (v.startsWith("W/")) {
            v = v.substring(2);
        }
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        try {
            return java.time.Instant.parse(v);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("If-Match: the rule's updatedAt as you last read it, for example "
                + "2026-10-25T09:30:00.123456Z");
        }
    }

    private static jakarta.ws.rs.WebApplicationException changedSince() {
        return ApiError.of(Response.Status.CONFLICT, "Someone else changed this rule after you opened it. Reload "
            + "the page to see their change, then make yours again.");
    }

    @DELETE
    @Path("rules/{id}")
    @RolesAllowed(AccessRole.STEWARD_ROLE)
    public Response deleteRule(@PathParam("id") long id) {
        Rule r = findRule(id);
        adminForCustomSql(r.type());
        store.deleteRule(id);
        quality.rescore(r.assetUrn());
        return Response.noContent().build();
    }

    /** Evaluates a rule against the asset's sample, before it is saved. */
    @POST
    @Path("rules/preview")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(DATA_ROLE)
    public ObjectNode preview(RuleDraft body, @QueryParam("unmask") boolean unmask) {
        Rule r = check(body);
        QualityService.Preview p = quality.preview(r).orElseThrow(() -> ApiError.of(Response.Status.CONFLICT,
            "No sample is kept for this asset, so the rule cannot be previewed. Save it and run it instead, or turn "
                + "on keep_samples for its source and scan again."));
        ObjectNode out = json.valueToTree(p);
        if (p.failingRows() != null) {
            out.set("failingRows", rows(p.failingRows().columns(), p.failingRows().rows(), r.assetUrn(), unmask, false));
        }
        return out;
    }

    @POST
    @Path("rules/{id}/run")
    @RolesAllowed(AccessRole.STEWARD_ROLE)
    public Response runRule(@PathParam("id") long id) {
        Rule r = findRule(id);
        adminForCustomSql(r.type());
        long run = quality.queueManual(r.assetUrn(), id, who());
        return Response.accepted(store.run(run).orElseThrow()).location(URI.create("/api/v1/quality/runs/" + run))
            .build();
    }

    @GET
    @Path("rules/{id}/results")
    public List<QualityStore.Result> results(@PathParam("id") long id, @QueryParam("limit") Integer limit) {
        findRule(id);
        return store.results(id, limit == null ? 20 : Math.max(1, Math.min(limit, 200))).stream()
            .map(this::withoutRows).toList();
    }

    /** Custom SQL rules are the admins' alone: they can read anything the source's login can see. */
    private void adminForCustomSql(io.kilnmark.server.quality.RuleType type) {
        if (type == io.kilnmark.server.quality.RuleType.CUSTOM_SQL && !identity.hasRole(AccessRole.ADMIN_ROLE)) {
            throw ApiError.of(Response.Status.FORBIDDEN, "Only an admin can write, change or run custom SQL rules.");
        }
    }

    /** One result; its failing rows only for roles that may see data, masked where sensitive. */
    @GET
    @Path("results/{id}")
    public ObjectNode result(@PathParam("id") long id, @QueryParam("unmask") boolean unmask) {
        QualityStore.Result r = store.result(id).orElseThrow(() -> ApiError.of(Response.Status.NOT_FOUND,
            "No result " + id));
        ObjectNode out = json.valueToTree(withoutRows(r));
        Rule rule = store.rule(r.ruleId()).orElse(null);
        if (identity.hasRole(DATA_ROLE) && r.failingRows() != null && quality.samplesAllowed(r.assetUrn())) {
            List<String> columns = new ArrayList<>();
            r.failingRows().path("columns").forEach(c -> columns.add(c.asText()));
            List<List<String>> rows = new ArrayList<>();
            r.failingRows().path("rows").forEach(row -> {
                List<String> cells = new ArrayList<>();
                row.forEach(c -> cells.add(c.isNull() ? null : c.asText()));
                rows.add(cells);
            });
            boolean custom = rule != null && rule.type() == io.kilnmark.server.quality.RuleType.CUSTOM_SQL;
            out.set("failingRows", rows(columns, rows, r.assetUrn(), unmask, custom));
        }
        return out;
    }

    /**
     * Failing rows with sensitive columns masked: by name, and by position, since rows from the
     * library's queries ({@code SELECT t.*}) hold the asset's columns in order. Rows from custom
     * SQL can hold anything under any name, so when the asset has a sensitive column they are
     * masked entirely unless an admin unmasks them.
     */
    private ObjectNode rows(List<String> columns, List<List<String>> rows, String urn, boolean unmask, boolean custom) {
        Set<String> sensitive = store.sensitive(urn);
        boolean show = unmask && identity.hasRole(UNMASK_ROLE);
        Set<Integer> hide = new HashSet<>();
        if (!show && !sensitive.isEmpty()) {
            List<String> fields = graph.asset(urn).map(a -> a.fields().stream().map(Records.FieldDetail::name).toList())
                .orElse(List.of());
            for (int i = 0; i < columns.size(); i++) {
                if (custom || sensitive.contains(columns.get(i))
                    || (i < fields.size() && sensitive.contains(fields.get(i)))) {
                    hide.add(i);
                }
            }
        }
        ObjectNode o = json.createObjectNode();
        o.set("columns", json.valueToTree(columns));
        ArrayNode out = o.putArray("rows");
        for (List<String> row : rows) {
            ArrayNode r = out.addArray();
            for (int i = 0; i < row.size(); i++) {
                String v = row.get(i);
                if (v == null) {
                    r.addNull();
                } else {
                    r.add(hide.contains(i) ? MASK : v);
                }
            }
        }
        o.set("masked", json.valueToTree(hide.stream().sorted().map(columns::get).toList()));
        return o;
    }

    /** A result without its failing rows; viewers also get no failing-rows query and no database error text. */
    private QualityStore.Result withoutRows(QualityStore.Result r) {
        if (r == null) {
            return null;
        }
        boolean data = identity.hasRole(DATA_ROLE);
        return new QualityStore.Result(r.id(), r.ruleId(), r.qualityRunId(), r.assetUrn(), r.status(), r.total(),
            r.failing(), r.method(), data ? r.failingQuery() : null, null, r.message(), r.observedAt(), r.origin(),
            data ? r.errorDetail() : null, r.estimate());
    }

    // ---- issues ----

    @GET
    @Path("issues")
    public List<QualityStore.Issue> issues(@QueryParam("status") String status, @QueryParam("urn") String urn,
                                           @QueryParam("limit") Integer limit) {
        String s = blank(status) == null ? "open" : status.trim();
        if (!Set.of("open", "resolved", "dismissed", "all").contains(s)) {
            throw new IllegalArgumentException("status: open, resolved, dismissed or all");
        }
        return store.issues(s.equals("all") ? null : s, blank(urn), limit == null ? 100 : Math.max(1, Math.min(limit, 500)));
    }

    public record IssueBody(String status) {
    }

    @PUT
    @Path("issues/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(AccessRole.STEWARD_ROLE)
    public QualityStore.Issue issue(@PathParam("id") long id, IssueBody body) {
        String s = body == null || body.status() == null ? "" : body.status().trim();
        if (!Set.of("open", "resolved", "dismissed").contains(s)) {
            throw new IllegalArgumentException("status: open, resolved or dismissed");
        }
        return store.setIssueStatus(id, s, who()).orElseThrow(() -> ApiError.of(Response.Status.NOT_FOUND,
            "No issue " + id));
    }

    // ---- sources ----

    /** A source's scores per dimension (the mean over its assets) and each asset's overall score. */
    @GET
    @Path("sources/{name}")
    public ObjectNode source(@PathParam("name") String name) {
        graph.source(name).orElseThrow(() -> ApiError.of(Response.Status.NOT_FOUND, "No source called " + name));
        ObjectNode out = json.createObjectNode().put("source", name);
        out.set("scores", json.valueToTree(store.sourceScores(name)));
        ArrayNode assets = out.putArray("assets");
        store.assetScores(name).forEach((u, s) -> assets.addObject().put("urn", u).put("score", s));
        return out;
    }

    // ---- exports ----

    /** The asset as an OpenLineage run event with schema, data quality metrics and assertions facets. */
    @GET
    @Path("export/openlineage")
    public ObjectNode openLineage(@QueryParam("urn") String urn, @QueryParam("unmask") boolean unmask) {
        return StandardFormats.openLineage(export(urn, unmask));
    }

    /** The asset as an Open Data Contract Standard 3.1 contract: schema and quality rules. */
    @GET
    @Path("export/odcs")
    public ObjectNode odcs(@QueryParam("urn") String urn, @QueryParam("unmask") boolean unmask) {
        return StandardFormats.odcs(export(urn, unmask));
    }

    private StandardFormats.Export export(String urn, boolean unmask) {
        Records.AssetDetail a = asset(urn);
        Records.Source s = graph.source(a.source()).orElseThrow();
        Optional<QualityStore.Version> v = store.latestVersion(urn);
        ProfileFacts facts = v.flatMap(x -> store.facts(x.id())).orElse(null);
        return new StandardFormats.Export(a, s, v.orElse(null), facts, store.rules(urn, null, false),
            store.latestResults(urn), store.sensitive(urn), "urn:kilnmark:server:" + version,
            identity.hasRole(DATA_ROLE), unmask && identity.hasRole(UNMASK_ROLE));
    }

    // ---- helpers ----

    private Records.AssetDetail asset(String urn) {
        if (urn == null || urn.isBlank()) {
            throw new IllegalArgumentException("urn: the asset's URN, for example urn:kilnmark:lab:crm.customers");
        }
        return graph.asset(urn).orElseThrow(() -> ApiError.of(Response.Status.NOT_FOUND, "No asset " + urn));
    }

    private Rule findRule(long id) {
        return store.rule(id).orElseThrow(() -> ApiError.of(Response.Status.NOT_FOUND, "No rule " + id));
    }

    private Rule check(RuleDraft body) {
        if (body == null) {
            throw new IllegalArgumentException("Send the rule as JSON: assetUrn, type, field, params and thresholds");
        }
        if ("custom_sql".equalsIgnoreCase(String.valueOf(body.type()).trim())) {
            adminForCustomSql(io.kilnmark.server.quality.RuleType.CUSTOM_SQL);
        }
        Records.AssetDetail a = asset(body.assetUrn());
        return body.check(context(a), who());
    }

    private RuleDraft.Context context(Records.AssetDetail a) {
        Records.Source s = graph.source(a.source()).orElseThrow();
        boolean sql = connectors.get(s.kind()).map(c -> c instanceof JdbcConnector).orElse(false);
        Map<String, String> fields = new LinkedHashMap<>();
        a.fields().forEach(f -> fields.put(f.name(), f.kind()));
        List<String> allow = quality.settings().webhookAllowList().orElse(List.of());
        return new RuleDraft.Context() {
            @Override
            public Map<String, String> fields() {
                return fields;
            }

            @Override
            public String sourceKind() {
                return s.kind();
            }

            @Override
            public boolean sql() {
                return sql;
            }

            @Override
            public Optional<Map<String, String>> fieldsOf(String urn) {
                return graph.asset(urn).map(o -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    o.fields().forEach(f -> m.put(f.name(), f.kind()));
                    return m;
                });
            }

            @Override
            public List<String> webhookAllowList() {
                return allow;
            }

            @Override
            public boolean customSqlEnabled() {
                return quality.settings().customSql();
            }
        };
    }

    private String who() {
        String name = identity.getPrincipal() == null ? null : identity.getPrincipal().getName();
        return name == null || name.isBlank() ? "unknown" : name;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}

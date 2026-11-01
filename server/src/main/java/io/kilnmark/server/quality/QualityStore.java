package io.kilnmark.server.quality;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.kilnmark.server.Db;
import jakarta.enterprise.context.ApplicationScoped;

/** Data quality's tables (migration V9): profile versions, samples, rules, results, scores, drift and issues. */
@ApplicationScoped
public class QualityStore {

    private final Db db;
    private final ObjectMapper json;

    public QualityStore(Db db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    // ---- records read back ----

    public record Version(long id, String assetUrn, Long runId, Instant observedAt, Long rowCount,
                          Boolean rowCountComplete, boolean estimate, Long sampledRows, Double samplePercent,
                          Long estimatedRows, String origin, String method, boolean baseline) {
    }

    public record Sample(String assetUrn, Long runId, String method, List<String> keyFields, JsonNode columns,
                         JsonNode rows, Long seen, JsonNode examples, Instant takenAt) {
    }

    public record Result(long id, long ruleId, Long qualityRunId, String assetUrn, Rule.Status status, Long total,
                         Long failing, String method, String failingQuery, JsonNode failingRows, String message,
                         Instant observedAt, String origin, String errorDetail, boolean estimate) {
    }

    public record Accepted(long id, String assetUrn, String field, String shape, String regex, Long ruleId,
                           String acceptedBy, Instant acceptedAt) {
    }

    public record DriftRow(long id, String assetUrn, String field, String kind, String change, String severity,
                           String message, JsonNode evidence, long versionId, Long comparedWith, String against,
                           Instant detectedAt, String detail) {
    }

    public record Issue(long id, String assetUrn, String field, Long ruleId, Long driftEventId, String title,
                        String detail, String severity, String status, Instant createdAt, Instant updatedAt,
                        String closedBy) {
    }

    public record Run(long id, long sourceId, String assetUrn, Long ruleId, Long scanRunId, String trigger,
                      String status, String requestedBy, Instant requestedAt, Instant startedAt, Instant finishedAt,
                      String detail) {
    }

    public record ScoreRow(String field, String dimension, double score, JsonNode reasons, Instant computedAt) {
    }

    public record MeasureRow(String field, String dimension, double score, JsonNode evidence) {
    }

    /** What the quality job needs about an asset's source. */
    public record SourceRef(long id, String name, String kind) {
    }

    // ---- profile versions (written during a scan) ----

    /**
     * Records the profile version of {@code urn} for scan run {@code runId} (or updates it), with
     * the fields as they are now.
     */
    public void saveVersion(String urn, long runId, Instant observedAt, long rows, boolean complete, boolean estimate,
                            Double samplePercent, Long estimatedRows) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO kilnmark.profile_version (asset_urn, run_id, observed_at, row_count, row_count_complete,
                    estimate, sampled_rows, sample_percent, estimated_rows, fields, method)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, coalesce((SELECT jsonb_agg(jsonb_build_object('name', f.name,
                    'position', f.position, 'kind', f.kind, 'sourceType', f.source_type, 'nullable', f.nullable)
                    ORDER BY f.position) FROM kilnmark.field f WHERE f.asset_urn = ?), '[]'), ?)
                ON CONFLICT (asset_urn, run_id) DO UPDATE SET observed_at = EXCLUDED.observed_at,
                    row_count = EXCLUDED.row_count, row_count_complete = EXCLUDED.row_count_complete,
                    estimate = EXCLUDED.estimate, sampled_rows = EXCLUDED.sampled_rows,
                    sample_percent = EXCLUDED.sample_percent, estimated_rows = EXCLUDED.estimated_rows,
                    fields = EXCLUDED.fields, method = EXCLUDED.method, analysed_at = NULL""")) {
                ps.setString(1, urn);
                ps.setLong(2, runId);
                ps.setTimestamp(3, Db.timestamp(observedAt));
                ps.setLong(4, estimate && estimatedRows != null ? estimatedRows : rows);
                ps.setBoolean(5, complete);
                ps.setBoolean(6, estimate);
                ps.setLong(7, rows);
                setDouble(ps, 8, samplePercent);
                setLong(ps, 9, estimatedRows);
                ps.setString(10, urn);
                ps.setString(11, estimate ? "table-sample" : complete ? "scan" : "row-limit");
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static final String VERSION_COLUMNS = "v.id, v.asset_urn, v.run_id, v.observed_at, v.row_count, "
        + "v.row_count_complete, v.estimate, v.sampled_rows, v.sample_percent, v.estimated_rows, v.origin, v.method, "
        + "v.is_baseline";

    private static Version version(ResultSet rs) throws SQLException {
        return new Version(rs.getLong(1), rs.getString(2), getLong(rs, 3), Db.instant(rs.getTimestamp(4)),
            getLong(rs, 5), getBoolean(rs, 6), rs.getBoolean(7), getLong(rs, 8), getDouble(rs, 9), getLong(rs, 10),
            rs.getString(11), rs.getString(12), rs.getBoolean(13));
    }

    /** The versions a scan run produced. */
    public List<Version> versionsOfRun(long runId) {
        return query("SELECT " + VERSION_COLUMNS + " FROM kilnmark.profile_version v WHERE v.run_id = ? ORDER BY v.id",
            ps -> ps.setLong(1, runId), QualityStore::version);
    }

    /** An asset's versions, newest first. */
    public List<Version> versions(String urn, int limit) {
        return query("SELECT " + VERSION_COLUMNS + " FROM kilnmark.profile_version v WHERE v.asset_urn = ? "
            + "ORDER BY v.observed_at DESC, v.id DESC LIMIT ?", ps -> {
                ps.setString(1, urn);
                ps.setInt(2, limit);
            }, QualityStore::version);
    }

    public Optional<Version> latestVersion(String urn) {
        return versions(urn, 1).stream().findFirst();
    }

    public Optional<Version> baseline(String urn) {
        return query("SELECT " + VERSION_COLUMNS + " FROM kilnmark.profile_version v WHERE v.asset_urn = ? "
            + "AND v.is_baseline ORDER BY v.observed_at DESC LIMIT 1", ps -> ps.setString(1, urn), QualityStore::version)
            .stream().findFirst();
    }

    /** Makes {@code versionId} the asset's baseline (or clears it with null). */
    public boolean setBaseline(String urn, Long versionId) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "UPDATE kilnmark.profile_version SET is_baseline = coalesce(id = ?, false) WHERE asset_urn = ? AND "
                    + "(is_baseline OR id = ?)")) {
                setLong(ps, 1, versionId);
                ps.setString(2, urn);
                setLong(ps, 3, versionId);
                return ps.executeUpdate() > 0 || versionId == null;
            }
        });
    }

    /**
     * Keeps an asset's history bounded: versions older than the newest {@code keep} (except
     * baselines) go with their measures and drift events (issues stay, unlinked), so do the column
     * profiles of runs no version still refers to, and each rule keeps its newest {@code keep}
     * results.
     */
    public int pruneVersions(String urn, int keep) {
        int k = Math.max(2, keep);
        return db.tx(c -> {
            int n;
            try (PreparedStatement ps = c.prepareStatement("""
                DELETE FROM kilnmark.profile_version WHERE asset_urn = ? AND NOT is_baseline AND id NOT IN (
                    SELECT id FROM kilnmark.profile_version WHERE asset_urn = ?
                    ORDER BY observed_at DESC, id DESC LIMIT ?)""")) {
                ps.setString(1, urn);
                ps.setString(2, urn);
                ps.setInt(3, k);
                n = ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                DELETE FROM kilnmark.profile p WHERE p.asset_urn = ? AND NOT EXISTS (
                    SELECT 1 FROM kilnmark.profile_version v WHERE v.asset_urn = p.asset_urn AND v.run_id = p.run_id)
                  AND EXISTS (SELECT 1 FROM kilnmark.profile_version v WHERE v.asset_urn = p.asset_urn)""")) {
                ps.setString(1, urn);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                DELETE FROM kilnmark.quality_result r WHERE r.asset_urn = ? AND r.id NOT IN (
                    SELECT id FROM (SELECT id, row_number() OVER (PARTITION BY rule_id ORDER BY observed_at DESC, id DESC)
                                    AS rn FROM kilnmark.quality_result WHERE asset_urn = ?) x WHERE x.rn <= ?)""")) {
                ps.setString(1, urn);
                ps.setString(2, urn);
                ps.setInt(3, k);
                ps.executeUpdate();
            }
            return n;
        });
    }

    public void markAnalysed(long versionId) {
        update("UPDATE kilnmark.profile_version SET analysed_at = now() WHERE id = ?", ps -> ps.setLong(1, versionId));
    }

    /** A version as analysis reads it: fields as they were, their statistics, and accepted shapes. */
    public Optional<ProfileFacts> facts(long versionId) {
        return db.tx(c -> {
            String urn;
            Long runId;
            Instant observed;
            Long rows;
            Boolean complete;
            boolean estimate;
            JsonNode fields;
            JsonNode columns;
            List<String> pk;
            try (PreparedStatement ps = c.prepareStatement("""
                SELECT v.asset_urn, v.run_id, v.observed_at, v.row_count, v.row_count_complete, v.estimate,
                       v.fields::text, v.columns::text, a.primary_key
                FROM kilnmark.profile_version v JOIN kilnmark.asset a ON a.urn = v.asset_urn WHERE v.id = ?""")) {
                ps.setLong(1, versionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    urn = rs.getString(1);
                    runId = getLong(rs, 2);
                    observed = Db.instant(rs.getTimestamp(3));
                    rows = getLong(rs, 4);
                    complete = getBoolean(rs, 5);
                    estimate = rs.getBoolean(6);
                    fields = read(rs.getString(7));
                    columns = read(rs.getString(8));
                    pk = strings(rs.getArray(9));
                }
            }
            Map<String, JsonNode> stats = new HashMap<>();
            if (columns != null && columns.isObject()) {
                columns.properties().forEach(e -> stats.put(e.getKey(), e.getValue()));
            } else if (runId != null) {
                try (PreparedStatement ps = c.prepareStatement(
                    "SELECT field_name, stats::text FROM kilnmark.profile WHERE asset_urn = ? AND run_id = ?")) {
                    ps.setString(1, urn);
                    ps.setLong(2, runId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            stats.put(rs.getString(1), read(rs.getString(2)));
                        }
                    }
                }
            }
            Map<String, Set<String>> accepted = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT field_name, shape FROM kilnmark.accepted_pattern WHERE asset_urn = ?")) {
                ps.setString(1, urn);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        accepted.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getString(2));
                    }
                }
            }
            List<ProfileFacts.FieldFacts> out = new ArrayList<>();
            if (fields != null) {
                for (JsonNode f : fields) {
                    String name = f.path("name").asText();
                    out.add(new ProfileFacts.FieldFacts(name, f.path("position").asInt(), f.path("kind").asText(),
                        f.path("sourceType").asText(), f.path("nullable").asBoolean(true), stats.get(name),
                        accepted.get(name)));
                }
            }
            return Optional.of(new ProfileFacts(versionId, urn, observed, rows, complete, estimate, pk, out));
        });
    }

    /** Older versions of the asset than {@code versionId}, newest first. */
    public List<Long> earlierVersions(String urn, long versionId, int limit) {
        return query("""
            SELECT v.id FROM kilnmark.profile_version v, kilnmark.profile_version me
            WHERE me.id = ? AND v.asset_urn = ? AND v.id <> me.id
              AND (v.observed_at, v.id) < (me.observed_at, me.id)
            ORDER BY v.observed_at DESC, v.id DESC LIMIT ?""", ps -> {
                ps.setLong(1, versionId);
                ps.setString(2, urn);
                ps.setInt(3, limit);
            }, rs -> rs.getLong(1));
    }

    // ---- samples ----

    public void saveSample(String urn, long runId, String method, List<String> keyFields, String columnsJson,
                           String rowsJson, Long seen, String examplesJson, Instant at) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO kilnmark.sample (asset_urn, run_id, method, key_fields, columns, rows, seen, examples, taken_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?)
                ON CONFLICT (asset_urn) DO UPDATE SET run_id = EXCLUDED.run_id, method = EXCLUDED.method,
                    key_fields = EXCLUDED.key_fields, columns = EXCLUDED.columns, rows = EXCLUDED.rows,
                    seen = EXCLUDED.seen, examples = EXCLUDED.examples, taken_at = EXCLUDED.taken_at""")) {
                ps.setString(1, urn);
                ps.setLong(2, runId);
                ps.setString(3, method);
                ps.setArray(4, c.createArrayOf("text", keyFields.toArray()));
                ps.setString(5, columnsJson);
                ps.setString(6, rowsJson);
                setLong(ps, 7, seen);
                ps.setString(8, examplesJson);
                ps.setTimestamp(9, Db.timestamp(at));
                ps.executeUpdate();
            }
            return null;
        });
    }

    public Optional<Sample> sample(String urn) {
        return query("SELECT asset_urn, run_id, method, key_fields, columns::text, rows::text, seen, examples::text, "
            + "taken_at FROM kilnmark.sample WHERE asset_urn = ?", ps -> ps.setString(1, urn),
            rs -> new Sample(rs.getString(1), getLong(rs, 2), rs.getString(3), strings(rs.getArray(4)),
                read(rs.getString(5)), read(rs.getString(6)), getLong(rs, 7), read(rs.getString(8)),
                Db.instant(rs.getTimestamp(9)))).stream().findFirst();
    }

    public int deleteSample(String urn) {
        return update("DELETE FROM kilnmark.sample WHERE asset_urn = ?", ps -> ps.setString(1, urn));
    }

    /**
     * Removes stored values for a source's assets: samples of assets no longer in the source, and
     * with {@code all}, every sample and every failing row kept with results (keep_samples off).
     */
    public int purgeSamples(long sourceId, boolean all) {
        return db.tx(c -> {
            int n;
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM kilnmark.sample s USING kilnmark.asset a "
                + "WHERE a.urn = s.asset_urn AND a.source_id = ?" + (all ? "" : " AND a.removed_at IS NOT NULL"))) {
                ps.setLong(1, sourceId);
                n = ps.executeUpdate();
            }
            if (all) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.quality_result r SET failing_rows = NULL "
                    + "FROM kilnmark.asset a WHERE a.urn = r.asset_urn AND a.source_id = ? AND r.failing_rows IS NOT NULL")) {
                    ps.setLong(1, sourceId);
                    ps.executeUpdate();
                }
            }
            return n;
        });
    }

    /**
     * Columns marked sensitive, including columns since dropped or renamed (their stored values stay
     * masked). Callers outside {@link Sights} and the marking itself should not need this.
     */
    public Set<String> sensitive(String urn) {
        return sensitive(java.util.List.of(urn)).get(urn);
    }

    /**
     * A carried mark meaning every column of the asset: a write whose columns could not be followed
     * or told before its run. It masks every column at once, and is listed as the asset's columns.
     */
    public static final String EVERY_COLUMN = "*";

    /** Names compared as masks compare them: ignoring case. */
    private static Set<String> names() {
        return new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    }

    /** A fixed copy of {@code marks} that still compares names ignoring case (Set.copyOf would not). */
    public static Set<String> marks(java.util.Collection<String> marks) {
        Set<String> out = names();
        if (marks != null) {
            out.addAll(marks);
        }
        return java.util.Collections.unmodifiableSet(out);
    }

    /** Columns marked sensitive, for several assets at once. */
    public Map<String, Set<String>> sensitive(java.util.Collection<String> urns) {
        Map<String, Set<String>> out = new HashMap<>();
        urns.forEach(u -> out.put(u, names()));
        if (urns.isEmpty()) {
            return out;
        }
        query("SELECT asset_urn, name FROM kilnmark.sensitive_column WHERE asset_urn = ANY (?)",
            ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", urns.toArray())),
            rs -> out.computeIfAbsent(rs.getString(1), k -> names()).add(rs.getString(2)));
        // "every column": the asset's columns, as they are catalogued now
        List<String> every = out.entrySet().stream().filter(e -> e.getValue().contains(EVERY_COLUMN))
            .map(Map.Entry::getKey).toList();
        if (!every.isEmpty()) {
            query("SELECT asset_urn, name FROM kilnmark.field WHERE asset_urn = ANY (?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", every.toArray())),
                rs -> out.get(rs.getString(1)).add(rs.getString(2)));
        }
        return out;
    }

    /**
     * Marks a column sensitive or not. Marking needs the column to exist now; unmarking does not,
     * so an admin can clear the mark of a column since dropped.
     */
    public boolean setSensitive(String urn, String field, boolean sensitive) {
        if (!sensitive) {
            // An admin's decision: the mark a steward set and the one a pipeline carried here both go,
            // whatever their case. Whether there is anything to clear is known before anything goes.
            boolean known = !query("SELECT 1 FROM kilnmark.sensitive_column WHERE asset_urn = ? AND lower(name) = lower(?) "
                + "UNION ALL SELECT 1 FROM kilnmark.field WHERE asset_urn = ? AND lower(name) = lower(?)", ps -> {
                    ps.setString(1, urn);
                    ps.setString(2, field);
                    ps.setString(3, urn);
                    ps.setString(4, field);
                }, rs -> 1).isEmpty();
            if (!known) {
                return false;
            }
            update("DELETE FROM kilnmark.carried_mark WHERE asset_urn = ? AND lower(name) = lower(?)", ps -> {
                ps.setString(1, urn);
                ps.setString(2, field);
            });
            update("DELETE FROM kilnmark.column_mark WHERE asset_urn = ? AND lower(name) = lower(?)", ps -> {
                ps.setString(1, urn);
                ps.setString(2, field);
            });
            return true;
        }
        return update("INSERT INTO kilnmark.column_mark (asset_urn, name) SELECT asset_urn, name "
            + "FROM kilnmark.field WHERE asset_urn = ? AND name = ? ON CONFLICT DO NOTHING", ps -> {
                ps.setString(1, urn);
                ps.setString(2, field);
            }) == 1 || sensitive(urn).contains(field);
    }

    /**
     * Of {@code urns}, the assets that or any of whose parts, at any depth (a workbook's sheets, a
     * mail's attached workbook's sheets), have a column marked sensitive.
     */
    public Set<String> textWithheld(java.util.Collection<String> urns) {
        return new HashSet<>(query("SELECT u FROM unnest(?::text[]) u WHERE u IN ("
            + io.kilnmark.graph.store.JdbcGraphStore.WITHHELD_TEXT + ")",
            ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", urns.toArray())), rs -> rs.getString(1)));
    }

    /** Whether any asset of the source has a column marked sensitive. */
    public boolean sourceHasMarks(String source) {
        return !query("SELECT 1 FROM kilnmark.sensitive_column m JOIN kilnmark.asset a ON a.urn = m.asset_urn "
            + "JOIN kilnmark.data_source s ON s.id = a.source_id WHERE s.name = ? LIMIT 1",
            ps -> ps.setString(1, source), rs -> 1).isEmpty();
    }

    /**
     * Marks carried to {@code urn} by a pipeline's write: columns it wrote from columns marked
     * sensitive upstream ({@code from}, the first such asset read). Kept apart from the marks
     * stewards set, and kept when the asset is removed and found again; an admin clears them as any
     * mark.
     */
    public void carry(String urn, java.util.Collection<String> names, String from, String pipeline, Long runId) {
        if (names.isEmpty()) {
            return;
        }
        for (String name : names) {
            update("INSERT INTO kilnmark.carried_mark (asset_urn, name, from_urn, pipeline, run_id) VALUES (?, ?, ?, ?, ?) "
                + "ON CONFLICT (asset_urn, name) DO NOTHING", ps -> {
                    ps.setString(1, urn);
                    ps.setString(2, name);
                    ps.setString(3, from);
                    ps.setString(4, pipeline);
                    if (runId == null) {
                        ps.setNull(5, java.sql.Types.BIGINT);
                    } else {
                        ps.setLong(5, runId);
                    }
                });
        }
    }

    /** A column renamed from {@code from} to {@code to} keeps its mark under both names. */
    public void carrySensitive(String urn, String from, String to) {
        update("INSERT INTO kilnmark.column_mark (asset_urn, name) SELECT DISTINCT asset_urn, ? "
            + "FROM kilnmark.sensitive_column WHERE asset_urn = ? AND name = ? ON CONFLICT DO NOTHING", ps -> {
                ps.setString(1, to);
                ps.setString(2, urn);
                ps.setString(3, from);
            });
    }

    // ---- rules ----

    private static final String RULE_COLUMNS = "id, asset_urn, field_name, name, type, dimension, params::text, "
        + "threshold_unit, warn_above, fail_above, actions::text, enabled, origin, origin_ref, created_by, created_at, "
        + "updated_at";

    private Rule rule(ResultSet rs) throws SQLException {
        JsonNode actions = read(rs.getString(11));
        JsonNode params = read(rs.getString(7));
        return new Rule(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), RuleType.parse(rs.getString(5)),
            Dimension.parse(rs.getString(6)), params instanceof ObjectNode o ? o : json.createObjectNode(),
            rs.getString(8), getDouble(rs, 9), rs.getDouble(10),
            new Rule.Actions(actions == null || actions.path("webhook").isMissingNode() || actions.path("webhook").isNull()
                ? null : actions.path("webhook").asText(), actions != null && actions.path("task").asBoolean(false)),
            rs.getBoolean(12), rs.getString(13), rs.getString(14), rs.getString(15), Db.instant(rs.getTimestamp(16)),
            Db.instant(rs.getTimestamp(17)));
    }

    private String actionsJson(Rule.Actions a) {
        ObjectNode o = json.createObjectNode();
        if (a.webhook() != null) {
            o.put("webhook", a.webhook());
        }
        o.put("task", a.task());
        return o.toString();
    }

    public Rule insertRule(Rule r) {
        return db.tx(c -> insertRule(c, r));
    }

    private Rule insertRule(Connection c, Rule r) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.quality_rule (asset_urn, field_name, name, "
            + "type, dimension, params, threshold_unit, warn_above, fail_above, actions, enabled, origin, origin_ref, "
            + "created_by) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?, ?, ?, ?) RETURNING " + RULE_COLUMNS)) {
            ps.setString(1, r.assetUrn());
            ps.setString(2, r.field());
            ps.setString(3, r.name());
            ps.setString(4, r.type().id());
            ps.setString(5, r.dimension().id());
            ps.setString(6, r.params().toString());
            ps.setString(7, r.thresholdUnit());
            setDouble(ps, 8, r.warnAbove());
            ps.setDouble(9, r.failAbove());
            ps.setString(10, actionsJson(r.actions()));
            ps.setBoolean(11, r.enabled());
            ps.setString(12, r.origin());
            ps.setString(13, r.originRef());
            ps.setString(14, r.createdBy());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rule(rs);
            }
        }
    }

    public Optional<Rule> updateRule(long id, Rule r) {
        return updateRule(id, r, null);
    }

    /**
     * Replaces a rule's settings. With {@code expected}, only while its {@code updated_at} is still
     * that: empty when the rule is gone or was changed since.
     */
    public Optional<Rule> updateRule(long id, Rule r, Instant expected) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.quality_rule SET field_name = ?, name = ?, "
                + "type = ?, dimension = ?, params = ?::jsonb, threshold_unit = ?, warn_above = ?, fail_above = ?, "
                + "actions = ?::jsonb, enabled = ?, updated_at = now() WHERE id = ? "
                + "AND (?::timestamptz IS NULL OR updated_at = ?::timestamptz) RETURNING " + RULE_COLUMNS)) {
                ps.setString(1, r.field());
                ps.setString(2, r.name());
                ps.setString(3, r.type().id());
                ps.setString(4, r.dimension().id());
                ps.setString(5, r.params().toString());
                ps.setString(6, r.thresholdUnit());
                setDouble(ps, 7, r.warnAbove());
                ps.setDouble(8, r.failAbove());
                ps.setString(9, actionsJson(r.actions()));
                ps.setBoolean(10, r.enabled());
                ps.setLong(11, id);
                ps.setTimestamp(12, Db.timestamp(expected));
                ps.setTimestamp(13, Db.timestamp(expected));
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rule(rs)) : Optional.empty();
                }
            }
        });
    }

    public boolean deleteRule(long id) {
        return update("DELETE FROM kilnmark.quality_rule WHERE id = ?", ps -> ps.setLong(1, id)) == 1;
    }

    public Optional<Rule> rule(long id) {
        return query("SELECT " + RULE_COLUMNS + " FROM kilnmark.quality_rule WHERE id = ?", ps -> ps.setLong(1, id),
            this::rule).stream().findFirst();
    }

    /** Rules of one asset, or of every asset when {@code urn} is null; {@code source} narrows to a source. */
    public List<Rule> rules(String urn, String source, boolean enabledOnly) {
        StringBuilder sql = new StringBuilder("SELECT " + RULE_COLUMNS.replaceAll("(^|, )", "$1r.") + " FROM kilnmark.quality_rule r JOIN kilnmark.asset a ON a.urn = r.asset_urn "
            + "JOIN kilnmark.data_source s ON s.id = a.source_id WHERE true");
        List<String> args = new ArrayList<>();
        if (urn != null) {
            sql.append(" AND r.asset_urn = ?");
            args.add(urn);
        }
        if (source != null) {
            sql.append(" AND s.name = ?");
            args.add(source);
        }
        if (enabledOnly) {
            sql.append(" AND r.enabled");
        }
        sql.append(" ORDER BY r.asset_urn, r.field_name NULLS FIRST, r.id");
        return query(sql.toString(), ps -> {
            for (int i = 0; i < args.size(); i++) {
                ps.setString(i + 1, args.get(i));
            }
        }, this::rule);
    }

    // ---- accepted patterns ----

    /** Accepts a shape for a field and creates its validity rule, in one transaction. */
    public Accepted accept(String urn, String field, String shape, String regex, Rule rule, String by) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM kilnmark.accepted_pattern "
                + "WHERE asset_urn = ? AND field_name = ? AND shape = ?")) {
                ps.setString(1, urn);
                ps.setString(2, field);
                ps.setString(3, shape);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        throw new IllegalStateException("That pattern is already accepted for " + field);
                    }
                }
            }
            Rule saved = insertRule(c, rule);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.accepted_pattern (asset_urn, field_name, "
                + "shape, regex, rule_id, accepted_by) VALUES (?, ?, ?, ?, ?, ?) RETURNING id, accepted_at")) {
                ps.setString(1, urn);
                ps.setString(2, field);
                ps.setString(3, shape);
                ps.setString(4, regex);
                ps.setLong(5, saved.id());
                ps.setString(6, by);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return new Accepted(rs.getLong(1), urn, field, shape, regex, saved.id(), by,
                        Db.instant(rs.getTimestamp(2)));
                }
            }
        });
    }

    public List<Accepted> accepted(String urn) {
        return query("SELECT id, asset_urn, field_name, shape, regex, rule_id, accepted_by, accepted_at "
            + "FROM kilnmark.accepted_pattern WHERE asset_urn = ? ORDER BY field_name, id", ps -> ps.setString(1, urn),
            rs -> new Accepted(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                getLong(rs, 6), rs.getString(7), Db.instant(rs.getTimestamp(8))));
    }

    /** Accepts a shape for a field whose validity rule already exists. */
    public Accepted acceptWith(String urn, String field, String shape, String regex, long ruleId, String by) {
        return query("INSERT INTO kilnmark.accepted_pattern (asset_urn, field_name, shape, regex, rule_id, accepted_by) "
            + "VALUES (?, ?, ?, ?, ?, ?) RETURNING id, accepted_at", ps -> {
                ps.setString(1, urn);
                ps.setString(2, field);
                ps.setString(3, shape);
                ps.setString(4, regex);
                ps.setLong(5, ruleId);
                ps.setString(6, by);
            }, rs -> new Accepted(rs.getLong(1), urn, field, shape, regex, ruleId, by, Db.instant(rs.getTimestamp(2))))
            .get(0);
    }

    public boolean deleteAccepted(String urn, long id) {
        return update("DELETE FROM kilnmark.accepted_pattern WHERE id = ? AND asset_urn = ?", ps -> {
            ps.setLong(1, id);
            ps.setString(2, urn);
        }) == 1;
    }

    // ---- quality runs and the job queue ----

    /**
     * Queues an evaluation: a quality run and its job (kind {@code quality}) in one transaction,
     * and wakes the workers. The job takes its source's scan lock like a scan, so after a scan it
     * is queued a few seconds ahead ({@code delaySeconds}), once the scan has let the lock go.
     */
    public long queueRun(long sourceId, String urn, Long ruleId, Long scanRunId, String trigger, String by,
                         int maxAttempts, int delaySeconds) {
        return db.tx(c -> {
            long id;
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.quality_run (source_id, asset_urn, "
                + "rule_id, scan_run_id, trigger, requested_by) VALUES (?, ?, ?, ?, ?, ?) RETURNING id")) {
                ps.setLong(1, sourceId);
                ps.setString(2, urn);
                setLong(ps, 3, ruleId);
                setLong(ps, 4, scanRunId);
                ps.setString(5, trigger);
                ps.setString(6, by);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    id = rs.getLong(1);
                }
            }
            // No runId in the payload: the scan queue's recovery then leaves scan runs alone.
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.job (kind, payload, max_attempts, "
                + "run_after) VALUES ('quality', jsonb_build_object('qualityRunId', ?::bigint, 'sourceId', ?::bigint), ?, "
                + "now() + make_interval(secs => ?))")) {
                ps.setLong(1, id);
                ps.setLong(2, sourceId);
                ps.setInt(3, Math.max(1, maxAttempts));
                ps.setInt(4, Math.max(0, delaySeconds));
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("NOTIFY " + io.kilnmark.server.jobs.JobQueue.CHANNEL)) {
                ps.execute();
            }
            return id;
        });
    }

    /** The quality run a job is for. */
    public Optional<Long> runForJob(long jobId) {
        return query("SELECT (payload ->> 'qualityRunId')::bigint FROM kilnmark.job WHERE id = ? AND kind = 'quality'",
            ps -> ps.setLong(1, jobId), rs -> getLong(rs, 1)).stream().filter(java.util.Objects::nonNull).findFirst();
    }

    private static final String RUN_COLUMNS = "id, source_id, asset_urn, rule_id, scan_run_id, trigger, status, "
        + "requested_by, requested_at, started_at, finished_at, detail";

    public Optional<Run> run(long id) {
        return query("SELECT " + RUN_COLUMNS + " FROM kilnmark.quality_run WHERE id = ?", ps -> ps.setLong(1, id),
            rs -> new Run(rs.getLong(1), rs.getLong(2), rs.getString(3), getLong(rs, 4), getLong(rs, 5), rs.getString(6),
                rs.getString(7), rs.getString(8), Db.instant(rs.getTimestamp(9)), Db.instant(rs.getTimestamp(10)),
                Db.instant(rs.getTimestamp(11)), rs.getString(12))).stream().findFirst();
    }

    public void startRun(long id) {
        update("UPDATE kilnmark.quality_run SET status = 'running', started_at = now(), finished_at = NULL WHERE id = ?",
            ps -> ps.setLong(1, id));
    }

    public void finishRun(long id, String status, String detail) {
        update("UPDATE kilnmark.quality_run SET status = ?, finished_at = now(), detail = ? WHERE id = ?", ps -> {
            ps.setString(1, status);
            ps.setString(2, detail);
            ps.setLong(3, id);
        });
    }

    public Optional<SourceRef> sourceOfAsset(String urn) {
        return query("SELECT s.id, s.name, s.kind FROM kilnmark.asset a JOIN kilnmark.data_source s ON s.id = a.source_id "
            + "WHERE a.urn = ?", ps -> ps.setString(1, urn), rs -> new SourceRef(rs.getLong(1), rs.getString(2),
            rs.getString(3))).stream().findFirst();
    }

    // ---- results ----

    /** Adds a sentence to a result's message (words for every role, such as why its webhook was not called). */
    public void noteResult(long id, String note) {
        update("UPDATE kilnmark.quality_result SET message = CASE WHEN coalesce(message, '') = '' THEN ? "
            + "ELSE message || ' ' || ? END WHERE id = ?", ps -> {
                ps.setString(1, note);
                ps.setString(2, note);
                ps.setLong(3, id);
            });
    }

    public long saveResult(Rule rule, Long runId, Rule.Status status, RowChecks.Evaluation e, boolean keepRows) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.quality_result (rule_id, quality_run_id, "
                + "asset_urn, status, total, failing, method, failing_query, failing_rows, message, error_detail, estimate) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?) RETURNING id")) {
                ps.setLong(1, rule.id());
                setLong(ps, 2, runId);
                ps.setString(3, rule.assetUrn());
                ps.setString(4, status.id());
                setLong(ps, 5, e.error() == null ? e.total() : null);
                setLong(ps, 6, e.error() == null ? e.failing() : null);
                ps.setString(7, e.method());
                ps.setString(8, e.failingQuery());
                ps.setString(9, keepRows && e.failingRows() != null ? json.valueToTree(e.failingRows()).toString() : null);
                ps.setString(10, e.error() != null ? e.error() : e.message());
                ps.setString(11, e.errorDetail());
                ps.setBoolean(12, e.estimate());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    private static final String RESULT_COLUMNS = "id, rule_id, quality_run_id, asset_urn, status, total, failing, method, "
        + "failing_query, failing_rows::text, message, observed_at, origin, error_detail, estimate";

    private Result result(ResultSet rs) throws SQLException {
        return new Result(rs.getLong(1), rs.getLong(2), getLong(rs, 3), rs.getString(4), Rule.Status.parse(rs.getString(5)),
            getLong(rs, 6), getLong(rs, 7), rs.getString(8), rs.getString(9), read(rs.getString(10)), rs.getString(11),
            Db.instant(rs.getTimestamp(12)), rs.getString(13), rs.getString(14), rs.getBoolean(15));
    }

    public Optional<Result> result(long id) {
        return query("SELECT " + RESULT_COLUMNS + " FROM kilnmark.quality_result WHERE id = ?", ps -> ps.setLong(1, id),
            this::result).stream().findFirst();
    }

    public List<Result> results(long ruleId, int limit) {
        return query("SELECT " + RESULT_COLUMNS + " FROM kilnmark.quality_result WHERE rule_id = ? "
            + "ORDER BY observed_at DESC, id DESC LIMIT ?", ps -> {
                ps.setLong(1, ruleId);
                ps.setInt(2, limit);
            }, this::result);
    }

    /** Each rule's latest result for an asset, by rule id. */
    public Map<Long, Result> latestResults(String urn) {
        Map<Long, Result> out = new LinkedHashMap<>();
        query("SELECT DISTINCT ON (rule_id) " + RESULT_COLUMNS + " FROM kilnmark.quality_result WHERE asset_urn = ? "
            + "ORDER BY rule_id, observed_at DESC, id DESC", ps -> ps.setString(1, urn), this::result)
            .forEach(r -> out.put(r.ruleId(), r));
        return out;
    }

    /** The latest result of each rule of several assets at once, by asset. */
    public Map<String, Map<Long, Result>> latestResults(java.util.Collection<String> urns) {
        Map<String, Map<Long, Result>> out = new HashMap<>();
        urns.forEach(u -> out.put(u, new LinkedHashMap<>()));
        if (urns.isEmpty()) {
            return out;
        }
        query("SELECT DISTINCT ON (rule_id) " + RESULT_COLUMNS + " FROM kilnmark.quality_result WHERE asset_urn = ANY (?) "
            + "ORDER BY rule_id, observed_at DESC, id DESC",
            ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", urns.toArray())), this::result)
            .forEach(r -> out.computeIfAbsent(r.assetUrn(), k -> new LinkedHashMap<>()).put(r.ruleId(), r));
        return out;
    }

    // ---- measures, scores, drift ----

    public void saveMeasures(long versionId, List<Measures.Measure> measures) {
        db.tx(c -> {
            try (PreparedStatement del = c.prepareStatement("DELETE FROM kilnmark.quality_measure WHERE version_id = ?")) {
                del.setLong(1, versionId);
                del.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.quality_measure (version_id, field_name, "
                + "dimension, score, evidence) VALUES (?, ?, ?, ?, ?::jsonb) ON CONFLICT DO NOTHING")) {
                for (Measures.Measure m : measures) {
                    ps.setLong(1, versionId);
                    ps.setString(2, m.field());
                    ps.setString(3, m.dimension().id());
                    ps.setDouble(4, m.score());
                    ObjectNode e = m.evidence().deepCopy();
                    e.put("reason", m.reason());
                    ps.setString(5, e.toString());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    public List<MeasureRow> measures(long versionId) {
        return query("SELECT field_name, dimension, score, evidence::text FROM kilnmark.quality_measure "
            + "WHERE version_id = ? ORDER BY field_name, dimension", ps -> ps.setLong(1, versionId),
            rs -> new MeasureRow(rs.getString(1), rs.getString(2), rs.getDouble(3), read(rs.getString(4))));
    }

    /** Measures stored for a version, back as {@link Measures.Measure}s. */
    public List<Measures.Measure> measureObjects(long versionId) {
        List<Measures.Measure> out = new ArrayList<>();
        for (MeasureRow m : measures(versionId)) {
            ObjectNode e = m.evidence() instanceof ObjectNode o ? o.deepCopy() : json.createObjectNode();
            String reason = e.path("reason").asText("");
            e.remove("reason");
            out.add(new Measures.Measure(m.field(), Dimension.parse(m.dimension()), m.score(), e, reason));
        }
        return out;
    }

    public void saveScores(String urn, List<Scores.Score> scores) {
        db.tx(c -> {
            try (PreparedStatement del = c.prepareStatement("DELETE FROM kilnmark.quality_score WHERE asset_urn = ?")) {
                del.setString(1, urn);
                del.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.quality_score (asset_urn, field_name, "
                + "dimension, score, reasons) VALUES (?, ?, ?, ?, ?::jsonb)")) {
                for (Scores.Score s : scores) {
                    ps.setString(1, urn);
                    ps.setString(2, s.field());
                    ps.setString(3, s.dimension());
                    ps.setDouble(4, s.score());
                    ps.setString(5, json.valueToTree(s.reasons()).toString());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    public List<ScoreRow> scores(String urn) {
        return query("SELECT field_name, dimension, score, reasons::text, computed_at FROM kilnmark.quality_score "
            + "WHERE asset_urn = ? ORDER BY field_name, dimension", ps -> ps.setString(1, urn),
            rs -> new ScoreRow(rs.getString(1), rs.getString(2), rs.getDouble(3), read(rs.getString(4)),
                Db.instant(rs.getTimestamp(5))));
    }

    /** A source's score per dimension and overall: the mean over its current assets. */
    public Map<String, Double> sourceScores(String source) {
        Map<String, Double> out = new LinkedHashMap<>();
        query("""
            SELECT q.dimension, avg(q.score) FROM kilnmark.quality_score q
            JOIN kilnmark.asset a ON a.urn = q.asset_urn JOIN kilnmark.data_source s ON s.id = a.source_id
            WHERE s.name = ? AND q.field_name = '' AND a.removed_at IS NULL
            GROUP BY q.dimension ORDER BY q.dimension""", ps -> ps.setString(1, source),
            rs -> Map.entry(rs.getString(1), rs.getDouble(2))).forEach(e -> out.put(e.getKey(),
                Math.round(e.getValue() * 10_000) / 10_000.0));
        return out;
    }

    /** The asset scores of a source's current assets: URN to overall score. */
    public Map<String, Double> assetScores(String source) {
        Map<String, Double> out = new LinkedHashMap<>();
        query("""
            SELECT q.asset_urn, q.score FROM kilnmark.quality_score q
            JOIN kilnmark.asset a ON a.urn = q.asset_urn JOIN kilnmark.data_source s ON s.id = a.source_id
            WHERE s.name = ? AND q.field_name = '' AND q.dimension = 'overall' AND a.removed_at IS NULL
            ORDER BY q.score, q.asset_urn""", ps -> ps.setString(1, source),
            rs -> Map.entry(rs.getString(1), rs.getDouble(2))).forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    /** Stores drift events; returns their ids in order. */
    public List<Long> saveDrift(String urn, long versionId, Long comparedWith, String against, List<Drift.Event> events) {
        return db.tx(c -> {
            try (PreparedStatement del = c.prepareStatement(
                "DELETE FROM kilnmark.drift_event WHERE version_id = ? AND against = ?")) {
                del.setLong(1, versionId);
                del.setString(2, against);
                del.executeUpdate();
            }
            List<Long> ids = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.drift_event (asset_urn, field_name, kind, "
                + "change, severity, message, evidence, version_id, compared_with, against, detail) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?) RETURNING id")) {
                for (Drift.Event e : events) {
                    ps.setString(1, urn);
                    ps.setString(2, e.field());
                    ps.setString(3, e.kind());
                    ps.setString(4, e.change());
                    ps.setString(5, e.severity());
                    ps.setString(6, e.message());
                    ps.setString(7, e.evidence().toString());
                    ps.setLong(8, versionId);
                    setLong(ps, 9, comparedWith);
                    ps.setString(10, against);
                    ps.setString(11, e.detail());
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        ids.add(rs.getLong(1));
                    }
                }
            }
            return ids;
        });
    }

    private static DriftRow drift(ResultSet rs, ObjectMapper json) throws SQLException {
        JsonNode evidence;
        try {
            evidence = json.readTree(rs.getString(8));
        } catch (JsonProcessingException e) {
            evidence = json.createObjectNode();
        }
        return new DriftRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
            rs.getString(6), rs.getString(7), evidence, rs.getLong(9), getLong(rs, 10), rs.getString(11),
            Db.instant(rs.getTimestamp(12)), rs.getString(13));
    }

    public List<DriftRow> drift(String urn, int limit) {
        return query("SELECT id, asset_urn, field_name, kind, change, severity, message, evidence::text, version_id, "
            + "compared_with, against, detected_at, detail FROM kilnmark.drift_event WHERE asset_urn = ? "
            + "ORDER BY detected_at DESC, id DESC LIMIT ?", ps -> {
                ps.setString(1, urn);
                ps.setInt(2, limit);
            }, rs -> drift(rs, json));
    }

    /** Drift events of one version, back as {@link Drift.Event}s. */
    public List<Drift.Event> driftOf(long versionId) {
        return query("SELECT id, asset_urn, field_name, kind, change, severity, message, evidence::text, version_id, "
            + "compared_with, against, detected_at, detail FROM kilnmark.drift_event WHERE version_id = ? ORDER BY id",
            ps -> ps.setLong(1, versionId), rs -> drift(rs, json)).stream()
            .map(d -> new Drift.Event(d.field(), d.kind(), d.change(), d.severity(), d.message(), d.detail(),
                d.evidence() instanceof ObjectNode o ? o : json.createObjectNode()))
            .toList();
    }

    // ---- issues ----

    /** Opens an issue for a failing rule, or refreshes the one already open; the new issue's id when one was opened. */
    public Optional<Long> openRuleIssue(Rule rule, String title, String detail, String severity) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO kilnmark.quality_issue (asset_urn, field_name, rule_id, issue_key, title, detail, severity)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (asset_urn, issue_key) WHERE status = 'open'
                DO UPDATE SET title = EXCLUDED.title, detail = EXCLUDED.detail, severity = EXCLUDED.severity,
                    updated_at = now()
                RETURNING id, xmax = 0""")) {
                ps.setString(1, rule.assetUrn());
                ps.setString(2, rule.field());
                ps.setLong(3, rule.id());
                ps.setString(4, "rule:" + rule.id());
                ps.setString(5, title);
                ps.setString(6, detail);
                ps.setString(7, severity);
                return opened(ps);
            }
        });
    }

    /** The id of the row an upsert inserted ({@code xmax = 0}), or empty when it updated one. */
    private static Optional<Long> opened(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            return rs.next() && rs.getBoolean(2) ? Optional.of(rs.getLong(1)) : Optional.empty();
        }
    }

    /** Resolves a rule's open issue once it passes again. */
    public int resolveRuleIssue(long ruleId) {
        return update("UPDATE kilnmark.quality_issue SET status = 'resolved', updated_at = now(), closed_by = 'kilnmark' "
            + "WHERE rule_id = ? AND status = 'open'", ps -> ps.setLong(1, ruleId));
    }

    /**
     * Opens an issue for drift, or refreshes the open one for the same kind of change on the same
     * column (key {@code drift:<change>:<field>}), pointing it at the latest event. Returns the new
     * issue's id when one was opened.
     */
    public Optional<Long> openDriftIssue(String urn, String field, String change, long driftEventId, String title,
                                         String detail, String severity) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO kilnmark.quality_issue (asset_urn, field_name, drift_event_id, issue_key, title, detail, severity)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (asset_urn, issue_key) WHERE status = 'open'
                DO UPDATE SET drift_event_id = EXCLUDED.drift_event_id, title = EXCLUDED.title, detail = EXCLUDED.detail,
                    severity = EXCLUDED.severity, updated_at = now()
                RETURNING id, xmax = 0""")) {
                ps.setString(1, urn);
                ps.setString(2, field);
                ps.setLong(3, driftEventId);
                ps.setString(4, "drift:" + change + ":" + (field == null ? "" : field));
                ps.setString(5, title);
                ps.setString(6, detail);
                ps.setString(7, severity);
                return opened(ps);
            }
        });
    }

    private static final String ISSUE_COLUMNS = "id, asset_urn, field_name, rule_id, drift_event_id, title, detail, "
        + "severity, status, created_at, updated_at, closed_by";

    private static Issue issue(ResultSet rs) throws SQLException {
        return new Issue(rs.getLong(1), rs.getString(2), rs.getString(3), getLong(rs, 4), getLong(rs, 5), rs.getString(6),
            rs.getString(7), rs.getString(8), rs.getString(9), Db.instant(rs.getTimestamp(10)),
            Db.instant(rs.getTimestamp(11)), rs.getString(12));
    }

    public List<Issue> issues(String status, String urn, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + ISSUE_COLUMNS + " FROM kilnmark.quality_issue WHERE true");
        List<String> args = new ArrayList<>();
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (urn != null) {
            sql.append(" AND asset_urn = ?");
            args.add(urn);
        }
        sql.append(" ORDER BY CASE severity WHEN 'high' THEN 0 WHEN 'medium' THEN 1 ELSE 2 END, updated_at DESC LIMIT ?");
        return query(sql.toString(), ps -> {
            int i = 1;
            for (String a : args) {
                ps.setString(i++, a);
            }
            ps.setInt(i, limit);
        }, QualityStore::issue);
    }

    /**
     * Marks failed the quality runs whose job ran out of attempts (a worker died running them each
     * time), so they do not stay "running" for ever. Returns how many.
     */
    public int failOrphanedRuns() {
        return update("""
            UPDATE kilnmark.quality_run r SET status = 'failed', finished_at = now(),
                detail = 'The worker running this evaluation stopped each time it was tried. Check the workers, then '
                    || 'run the rules again.'
            FROM kilnmark.job j
            WHERE j.kind = 'quality' AND j.status = 'failed' AND (j.payload ->> 'qualityRunId')::bigint = r.id
              AND r.status IN ('queued', 'running')""", ps -> { });
    }

    public Optional<Issue> setIssueStatus(long id, String status, String by) {
        return query("UPDATE kilnmark.quality_issue SET status = ?, updated_at = now(), closed_by = "
            + "CASE WHEN ? = 'open' THEN NULL ELSE ? END WHERE id = ? RETURNING " + ISSUE_COLUMNS, ps -> {
                ps.setString(1, status);
                ps.setString(2, status);
                ps.setString(3, by);
                ps.setLong(4, id);
            }, QualityStore::issue).stream().findFirst();
    }

    // ---- helpers ----

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    @FunctionalInterface
    private interface Reader<T> {
        T read(ResultSet rs) throws SQLException;
    }

    private <T> List<T> query(String sql, Binder binder, Reader<T> reader) {
        return db.tx(c -> {
            List<T> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                binder.bind(ps);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(reader.read(rs));
                    }
                }
            }
            return out;
        });
    }

    private int update(String sql, Binder binder) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                binder.bind(ps);
                return ps.executeUpdate();
            }
        });
    }

    JsonNode read(String text) {
        if (text == null) {
            return null;
        }
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static Long getLong(ResultSet rs, int i) throws SQLException {
        long v = rs.getLong(i);
        return rs.wasNull() ? null : v;
    }

    private static Double getDouble(ResultSet rs, int i) throws SQLException {
        double v = rs.getDouble(i);
        return rs.wasNull() ? null : v;
    }

    private static Boolean getBoolean(ResultSet rs, int i) throws SQLException {
        boolean v = rs.getBoolean(i);
        return rs.wasNull() ? null : v;
    }

    private static void setLong(PreparedStatement ps, int i, Long v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.BIGINT);
        } else {
            ps.setLong(i, v);
        }
    }

    private static void setDouble(PreparedStatement ps, int i, Double v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.DOUBLE);
        } else {
            ps.setDouble(i, v);
        }
    }

    private static List<String> strings(Array a) throws SQLException {
        if (a == null) {
            return List.of();
        }
        Object[] values = (Object[]) a.getArray();
        List<String> out = new ArrayList<>(values.length);
        for (Object v : values) {
            out.add(String.valueOf(v));
        }
        return out;
    }
}

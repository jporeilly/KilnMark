package io.kilnmark.server.pipelines;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.arrow.vector.types.pojo.Schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kilnmark.graph.store.Records;
import io.kilnmark.kiln.SettingSpec;
import io.kilnmark.kiln.Node;
import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.NodeFactory;
import io.kilnmark.kiln.NodeKind;
import io.kilnmark.kiln.NodeSpec;
import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.nodes.ReadShaping;
import io.kilnmark.kiln.nodes.expr.ExprException;
import io.kilnmark.kiln.nodes.expr.PushDown;
import io.kilnmark.kiln.plugins.jdbc.JdbcConnector;
import io.kilnmark.server.quality.RuleRunner;
import io.kilnmark.server.quality.SqlTokens;
import io.kilnmark.server.sources.CommonSettings;

/**
 * The {@code read} node: a table, view or file of a saved source, read with its connector's
 * reader (docs/design/pipelines.md section 4). The server registers it, since it needs the saved
 * sources and their secrets; the YAML only names the source. Its columns, for validation and the
 * canvas, are the asset's as last catalogued.
 *
 * <p>Or, in place of an asset, a {@code query} (ADR 0028): one {@code SELECT} a person wrote, run as
 * written on a read-only connection, on a PostgreSQL source with Allow SQL on and a login without
 * superuser-like rights. Its columns are known only when it runs, and every column it reads is
 * treated as marked sensitive. The tables it reads, as the database plans the query, are recorded
 * as the run's reads and the inputs of what it writes.
 */
public final class ReadNodeFactory implements NodeFactory {

    public static final String TYPE = "read";
    static final int BATCH_ROWS = 8192;
    /** The longest query, as for custom SQL rules. */
    static final int MAX_QUERY = 10_000;
    private static final ObjectMapper JSON = new ObjectMapper();
    /** How long the database may take to plan a query, for its lineage, before the read gives up. */
    static final int PLAN_TIMEOUT_SECONDS = 30;

    private final PipelineSources sources;

    public ReadNodeFactory(PipelineSources sources) {
        this.sources = sources;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public NodeKind kind() {
        return NodeKind.SOURCE;
    }

    @Override
    public String label() {
        return "Read a table or file";
    }

    @Override
    public String help() {
        return "Reads a table, view or file from a saved source. The source holds the credentials, so the pipeline "
            + "only names it.";
    }

    @Override
    public List<String> inputPorts() {
        return List.of();
    }

    @Override
    public List<String> outputPorts() {
        return List.of("out");
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
            SettingSpec.required("source", "Source", SettingSpec.Kind.SOURCE, "The saved source to read from"),
            SettingSpec.optional("asset", "Table or file", SettingSpec.Kind.ASSET, null,
                "A table or view as schema.table, or a file's path inside the source. Leave out to run a query"),
            SettingSpec.optional("query", "Query", SettingSpec.Kind.TEXT, null,
                "One SELECT run as written, in place of a table (PostgreSQL sources with Allow SQL on). Its columns "
                    + "are known when it runs").withParameters(SettingSpec.Parameters.NONE),
            SettingSpec.optional("columns", "Columns", SettingSpec.Kind.COLUMNS, null,
                "Only these columns, in this order; all of them when left out. A database reads only these"),
            SettingSpec.optional("where", "Where", SettingSpec.Kind.EXPRESSION, null,
                "Only the rows meeting this condition, such as status = 'delivered'. A database applies what it "
                    + "can while reading, with each value bound; Kilnmark checks every row that arrives"),
            SettingSpec.optional("row_limit", "Row limit", SettingSpec.Kind.NUMBER, 0,
                "Reads at most this many rows (after where); 0 reads them all. Handy while trying a pipeline out"));
    }

    @Override
    public List<String> check(Map<String, Object> config) {
        List<String> problems = new ArrayList<>();
        String name = PipelineSources.text(config, "source");
        String query = query(config);
        Optional<Records.Source> s = Optional.empty();
        if (name != null) {
            s = sources.source(name);
            if (s.isEmpty()) {
                problems.add("source: there is no saved source called '" + name + "'");
            } else {
                // on production the example data's kind has no connector (ConnectorsProducer): refused here
                Optional<Connector> c = sources.connector(s.get().kind());
                if (c.isEmpty()) {
                    problems.add("source: this server has no connector for sources of kind '" + s.get().kind() + "'");
                } else if (!c.get().capabilities().contains(Connector.Capability.READ)) {
                    problems.add("source: " + c.get().displayName() + " sources cannot be read in pipelines yet");
                } else if (query != null) {
                    String p = queryProblem(s.get(), c.get());
                    if (p != null) {
                        problems.add(p);
                    }
                }
            }
        }
        if (config.get("query") != null && query == null) {
            problems.add("query: one SELECT, or leave query out to read a table or file");
        }
        if (query != null) {
            if (config.get("asset") != null) {
                problems.add("query: a read takes a table or file, or a query, not both");
            }
            if (config.get("columns") != null || config.get("where") != null) {
                problems.add("query: columns and where do not apply to a query; choose the columns and rows in the "
                    + "query itself");
            }
            try {
                checkQuery(query);
            } catch (IllegalArgumentException e) {
                problems.add(e.getMessage());
            }
        } else if (config.get("query") == null && config.get("asset") == null) {
            problems.add("asset: one table or file to read, such as crm.customers or exports/orders.csv, or a query");
        } else if (config.get("asset") != null && PipelineSources.text(config, "asset") == null) {
            problems.add("asset: one table or file, such as crm.customers or exports/orders.csv");
        }
        if (rowLimit(config) < 0) {
            problems.add("row_limit: a whole number of rows, 0 or more (0 reads every row)");
        }
        return problems;
    }

    /** The query set, stripped, or null when none (or only blanks). */
    public static String query(Map<String, Object> config) {
        String q = PipelineSources.text(config, "query");
        return q == null || q.isBlank() ? null : q.strip();
    }

    /** Whether this read runs a query rather than reading a table or file. */
    public static boolean isQuery(Map<String, Object> config) {
        return config.get("query") != null;
    }

    /** Why a query may not run on this source, or null: PostgreSQL only, with Allow SQL on. */
    static String queryProblem(Records.Source source, Connector connector) {
        if (!"postgresql".equals(source.kind()) || !(connector instanceof JdbcConnector)) {
            return "query: a query reads only from PostgreSQL sources for now";
        }
        if (!CommonSettings.allowsSql(source.settings())) {
            return "query: " + source.name() + " does not allow SQL. A designer or admin turns on Allow SQL on the "
                + "source, after checking its login only reads what pipelines may read";
        }
        return null;
    }

    /**
     * The query checked as custom SQL rules are (one {@code SELECT} or {@code WITH}, nothing that acts
     * outside the query) and returned without trailing semicolons; throws with a reason starting
     * {@code query:}. Parameters are not filled in a query yet, so {@code ${...}} is refused.
     */
    static String checkQuery(String sql) {
        if (sql != null && sql.contains("${")) {
            throw new IllegalArgumentException("query: parameters such as ${name} are not filled in a query yet; "
                + "write the value in the query");
        }
        try {
            return SqlTokens.checkCustomSql(sql, MAX_QUERY);
        } catch (IllegalArgumentException e) {
            String m = String.valueOf(e.getMessage());
            m = m.startsWith("params.sql: ") ? m.substring("params.sql: ".length()) : m;
            throw new IllegalArgumentException("query: " + m.replace("a SELECT listing the failing rows", "one SELECT")
                .replace("custom rules only read", "a query only reads"));
        }
    }

    /** A refusal of the login, worded for a query rather than a custom SQL rule. */
    static String forQuery(String refusal) {
        return refusal.replace("Custom SQL does not run", "The query does not run")
            .replace("custom SQL rules", "queries").replace("custom SQL", "queries");
    }

    /**
     * The tables and views a query reads, as {@code schema.name}, from PostgreSQL's
     * {@code EXPLAIN (VERBOSE, FORMAT JSON)} plan: every plan node naming a relation. Views appear as
     * the tables they read, since the plan has them expanded.
     */
    static Set<String> relations(String explainJson) {
        Set<String> out = new LinkedHashSet<>();
        if (explainJson == null) {
            return out;
        }
        try {
            collect(JSON.readTree(explainJson), out);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // no lineage rather than a failed read
        }
        return out;
    }

    private static void collect(JsonNode n, Set<String> out) {
        if (n == null) {
            return;
        }
        if (n.isObject()) {
            JsonNode name = n.get("Relation Name");
            JsonNode schema = n.get("Schema");
            if (name != null && name.isTextual() && schema != null && schema.isTextual()) {
                out.add(schema.asText() + "." + name.asText());
            }
            n.elements().forEachRemaining(c -> collect(c, out));
        } else if (n.isArray()) {
            n.elements().forEachRemaining(c -> collect(c, out));
        }
    }

    /** The row limit set, 0 when none, or -1 when it is not a whole number of 0 or more. */
    static long rowLimit(Map<String, Object> config) {
        Object v = config.get("row_limit");
        if (v == null) {
            return 0;
        }
        if (v instanceof Long || v instanceof Integer) {
            long n = ((Number) v).longValue();
            return n < 0 ? -1 : n;
        }
        if (v instanceof String s) {
            try {
                long n = Long.parseLong(s.strip());
                return n < 0 ? -1 : n;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    /** The URN of the asset this node reads, as the catalogue records it; null if the settings do not say. */
    public static String urn(Map<String, Object> config) {
        return PipelineSources.urn(PipelineSources.text(config, "source"), PipelineSources.text(config, "asset"));
    }

    @Override
    public Schema outputSchema(String port, Map<String, Schema> inputs, Map<String, Object> config)
        throws NodeException {
        String source = PipelineSources.text(config, "source");
        String asset = PipelineSources.text(config, "asset");
        if (source == null || asset == null || isQuery(config)) {
            return null; // a query's columns are known when it runs
        }
        // The asset as written, or as the catalogue writes a file's path (# and % escaped), so a path
        // picked from the catalogue and one typed as on disk both find its columns.
        Schema all = assetSchema(source, asset);
        if (all == null) {
            return null; // not scanned yet: columns and where are checked when the run reads the asset
        }
        try {
            return ReadShaping.outputSchema(all, columns(config), where(config));
        } catch (ExprException e) {
            String m = e.getMessage();
            throw new NodeException(m.startsWith("columns:") ? m : "where: " + m, e);
        }
    }

    /** The asset's columns as the catalogue records them, or null when it has not been scanned. */
    private Schema assetSchema(String source, String asset) {
        List<Records.FieldDetail> fields = assetFields(source, asset);
        return fields == null ? null : ArrowKinds.schema(fields);
    }

    private List<Records.FieldDetail> assetFields(String source, String asset) {
        // The asset as written, or as the catalogue writes a file's path (# and % escaped), so a path
        // picked from the catalogue and one typed as on disk both find its columns.
        for (String path : List.of(asset, escapedFilePath(asset))) {
            String urn = PipelineSources.urn(source, path);
            Optional<List<Records.FieldDetail>> f = urn == null ? Optional.empty()
                : sources.asset(urn).map(Records.AssetDetail::fields).filter(l -> !l.isEmpty());
            if (f.isPresent()) {
                return f.get();
            }
        }
        return null;
    }

    /**
     * Where a read's condition is applied, for the pipeline's check: by the database while reading, in
     * part, or by Kilnmark as rows arrive. A note, never a problem: the rows are the same either way.
     */
    @Override
    public List<String> notes(Map<String, Schema> inputs, Map<String, Object> config) {
        String where = where(config);
        String source = PipelineSources.text(config, "source");
        String asset = PipelineSources.text(config, "asset");
        if (where == null || source == null || asset == null || isQuery(config)) {
            return List.of();
        }
        List<Records.FieldDetail> fields = assetFields(source, asset);
        if (fields == null) {
            return List.of();
        }
        Optional<Connector> connector = narrowing(source);
        if (connector.isEmpty()) {
            return List.of("where is applied by Kilnmark as the rows arrive: this kind of source reads every row");
        }
        Set<String> compared = new HashSet<>();
        for (Records.FieldDetail f : fields) {
            if (connector.get().comparesAsRead(f.kind(), f.sourceType())) {
                compared.add(f.name());
            }
        }
        try {
            PushDown.Split split = PushDown.split(where, ArrowKinds.schema(fields), compared::contains);
            if (split.whole()) {
                return List.of("where is applied by the database while reading, with each value bound");
            }
            if (split.filter() != null) {
                return List.of("where is applied in part by the database while reading; Kilnmark does the rest "
                    + "as rows arrive (" + String.join("; ", split.kept()) + ")");
            }
            return List.of("where is applied by Kilnmark as the rows arrive, not by the database ("
                + String.join("; ", split.kept()) + ")");
        } catch (ExprException e) {
            return List.of(); // reported as a problem
        }
    }

    /** The source's connector when its reads can be narrowed at the source (a database table, not a file). */
    private Optional<Connector> narrowing(String sourceName) {
        Optional<Connector> c = sources.source(sourceName).flatMap(s -> sources.connector(s.kind()));
        if (c.isEmpty()) {
            return Optional.empty();
        }
        AssetInfo table = new AssetInfo("s.t", "table", "s", "t", null, List.of(), List.of(), false, true,
            Map.of("schema", "s", "table", "t"));
        try {
            return c.get().narrowsReads(null, table) ? c : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * What the connector is asked to read of {@code asset}: only the columns needed and the rows
     * meeting as much of {@code where} as it can apply, within the row limit when that stays exact.
     * Shared by {@link #create} and {@link #statement}, so the SQL shown is the SQL that runs.
     */
    private static Connector.ReadOptions readOptions(Connector connector, SourceConfig source, AssetInfo asset,
                                                     String urn, Map<String, Object> config) throws ExprException {
        List<String> columns = columns(config);
        String where = where(config);
        long limit = Math.max(0, rowLimit(config));
        if (columns.isEmpty() && where == null) {
            return new Connector.ReadOptions(urn, limit, BATCH_ROWS);
        }
        Schema all = ArrowKinds.schemaOf(asset.fields());
        ReadShaping.outputSchema(all, columns, where); // the asset may have changed since the check
        PushDown.Split split = where == null || !connector.narrowsReads(source, asset) ? null
            : PushDown.split(where, all, compared(connector, asset));
        // With a condition, the source's limit would count rows Kilnmark may still drop, unless the
        // source keeps exactly what Kilnmark keeps: otherwise the read node counts them, and stops
        // the read when it has enough.
        long sourceLimit = where == null || (split != null && split.keepsExactly()) ? limit : 0;
        return new Connector.ReadOptions(urn, sourceLimit, BATCH_ROWS, ReadShaping.needed(all, columns, where),
            split == null ? null : split.filter());
    }

    /**
     * The SQL a read sends to its database, as configured, for the canvas to show: {@code sql} with
     * {@code ?} for each of the {@code values} bound from {@code where}, or null with {@code note}
     * saying why it cannot be shown. A {@code note} beside a statement adds what else may run.
     */
    public record SqlShown(String sql, int values, String note) {
        static SqlShown none(String why) {
            return new SqlShown(null, 0, why);
        }
    }

    /**
     * The SQL this read runs, worked out without connecting: the same options and the same reader as
     * {@link #create}, given the table as the catalogue last recorded it. A query read shows the
     * query as it is sent (wrapped when there is a row limit). Nothing here holds a secret: the source
     * is described without one, and only the statement is kept.
     */
    public SqlShown statement(Map<String, Object> config) {
        String name = PipelineSources.text(config, "source");
        if (name == null) {
            return SqlShown.none("Choose a source to see the SQL this read runs.");
        }
        Optional<Records.Source> s = sources.source(name);
        if (s.isEmpty()) {
            return SqlShown.none("There is no saved source by that name.");
        }
        Optional<Connector> c = sources.connector(s.get().kind());
        if (c.isEmpty()) {
            return SqlShown.none("This server has no connector for the source's kind, so it cannot read it.");
        }
        if (!(c.get() instanceof JdbcConnector jdbc)) {
            return SqlShown.none("Kilnmark reads this source itself, without SQL.");
        }
        SourceConfig described = new SourceConfig(s.get().name(), s.get().kind(), s.get().settings(), null);
        try {
            if (isQuery(config)) {
                String q = query(config);
                if (q == null || config.get("asset") != null || queryProblem(s.get(), jdbc) != null) {
                    return SqlShown.none("Fix the problems shown with the query to see what runs.");
                }
                String sent = jdbc.queryReader(described, checkQuery(q), "query", Math.max(0, rowLimit(config)),
                    BATCH_ROWS).settings().get("query");
                return new SqlShown(sent, 0, null);
            }
            if (jdbc instanceof io.kilnmark.kiln.plugins.jdbc.GenericJdbcConnector) {
                return SqlShown.none("Kilnmark writes the SQL for this kind of source once it has connected, so it "
                    + "is known only when the read runs.");
            }
            String path = PipelineSources.text(config, "asset");
            if (path == null) {
                return SqlShown.none("Choose a table to see the SQL this read runs.");
            }
            Records.AssetDetail detail = assetDetail(name, path);
            if (detail == null) {
                return SqlShown.none("Shown once the table has been scanned: the SQL names its columns.");
            }
            if (detail.container() == null || detail.name() == null) {
                return SqlShown.none("The catalogue does not record this table's schema, so its SQL is known only "
                    + "when the read runs.");
            }
            AssetInfo asset = catalogued(detail);
            String urn = PipelineSources.urn(name, asset.path());
            Map<String, String> settings = jdbc.reader(described, asset, readOptions(jdbc, described, asset, urn,
                config)).settings();
            int values = 0;
            while (settings.containsKey(io.kilnmark.kiln.plugins.jdbc.JdbcTableInput.VALUE_PREFIX + (values + 1))) {
                values++;
            }
            String above = settings.get(io.kilnmark.kiln.plugins.jdbc.JdbcTableInput.SAMPLE_ABOVE);
            return new SqlShown(settings.get("query"), values, above == null ? null
                : "When the database estimates the table holds more than " + above + " rows, Kilnmark reads a "
                    + "sample of it instead, as the source is set to.");
        } catch (ExprException | IllegalArgumentException e) {
            return SqlShown.none("Fix the problems shown in this node's settings to see the SQL it runs.");
        } catch (RuntimeException e) {
            return SqlShown.none("The SQL cannot be worked out until the source's settings are complete.");
        }
    }

    /** The asset as the catalogue last recorded it, found as {@link #assetFields} finds it; null if not scanned. */
    private Records.AssetDetail assetDetail(String source, String asset) {
        for (String path : List.of(asset, escapedFilePath(asset))) {
            String urn = PipelineSources.urn(source, path);
            Optional<Records.AssetDetail> a = urn == null ? Optional.empty()
                : sources.asset(urn).filter(d -> !d.fields().isEmpty());
            if (a.isPresent()) {
                return a.get();
            }
        }
        return null;
    }

    /**
     * A database table or view as its connector describes it when a read runs, rebuilt from the
     * catalogue: the same columns, schema and table, and a view counted as bounded as the connector
     * counts it (so it is never sampled).
     */
    static AssetInfo catalogued(Records.AssetDetail a) {
        List<AssetInfo.FieldInfo> fields = a.fields().stream().map(f -> new AssetInfo.FieldInfo(f.name(),
            f.position(), f.sourceType(), f.kind(), f.nullable(), f.description())).toList();
        boolean table = "table".equals(a.kind()) || "partitioned table".equals(a.kind());
        return new AssetInfo(io.kilnmark.graph.Urn.parse(a.urn()).path(), a.kind(), a.container(), a.name(),
            a.description(), fields, a.primaryKey(), !table, true, Map.of("schema", a.container(), "table", a.name()));
    }

    /** The columns of {@code asset} the source compares as Kilnmark reads them. */
    private static java.util.function.Predicate<String> compared(Connector connector, AssetInfo asset) {
        Set<String> names = new HashSet<>();
        for (AssetInfo.FieldInfo f : asset.fields()) {
            if (connector.comparesAsRead(f.kind(), f.sourceType())) {
                names.add(f.name());
            }
        }
        return names::contains;
    }

    /** The chosen columns, or empty for all of them. */
    static List<String> columns(Map<String, Object> config) {
        Object v = config.get("columns");
        if (!(v instanceof List<?> l)) {
            return List.of();
        }
        return l.stream().map(String::valueOf).toList();
    }

    /** The condition, or null. */
    static String where(Map<String, Object> config) {
        String w = PipelineSources.text(config, "where");
        return w == null || w.isBlank() ? null : w;
    }

    /**
     * The asset at {@code path}: as written, or, when that finds nothing and the path is a file's as
     * the catalogue records it ({@code %23} for {@code #}, {@code %25} for {@code %}), the file it names,
     * so a path picked from the catalogue reads the same file as one typed as on disk.
     */
    private static AssetInfo describe(PipelineSources.Resolved r, String path) throws ConnectorException {
        try {
            return r.connector().asset(r.config(), path);
        } catch (ConnectorException e) {
            String onDisk = io.kilnmark.kiln.plugins.files.FileSourceConnector.unescape(path);
            if (onDisk.equals(path)) {
                throw e;
            }
            try {
                return r.connector().asset(r.config(), onDisk);
            } catch (ConnectorException again) {
                throw e;
            }
        }
    }

    /** A file path as the catalogue records it: {@code %} and {@code #} escaped, as file sources do. */
    static String escapedFilePath(String path) {
        return io.kilnmark.kiln.plugins.files.FileSourceConnector.urnPath(path);
    }

    @Override
    public Node create(Map<String, Object> config) throws NodeException {
        RunContext run = RunContext.current().orElseThrow(() -> new NodeException(
            "A read node is set up only when its pipeline runs"));
        String source = PipelineSources.text(config, "source");
        if (isQuery(config)) {
            return createQuery(run, config, source);
        }
        String path = PipelineSources.text(config, "asset");
        if (source == null || path == null) {
            throw new NodeException("A read node needs a source and an asset");
        }
        String node = "read " + path;
        PipelineSources.Resolved r = sources.resolve(node, source, run);
        AssetInfo asset;
        try {
            asset = describe(r, path);
        } catch (ConnectorException e) {
            throw new NodeException("Cannot read " + path + " from " + source + ": " + run.scrub(e.getMessage()));
        } catch (RuntimeException e) {
            throw new NodeException("Cannot read " + path + " from " + source + ": " + run.scrub(String.valueOf(
                e.getMessage())));
        }
        if (!asset.readable()) {
            throw new NodeException(path + " in " + source + " cannot be read as rows");
        }
        String urn = PipelineSources.urn(source, asset.path());
        run.read(source, path, urn);
        List<String> columns = columns(config);
        String where = where(config);
        long limit = Math.max(0, rowLimit(config));
        Connector.ReadOptions options;
        try {
            options = readOptions(r.connector(), r.config(), asset, urn, config);
        } catch (ExprException e) {
            String m = e.getMessage();
            throw new NodeException((m.startsWith("columns:") ? "" : "where: ") + m, e);
        }
        NodeSpec spec;
        try {
            spec = r.connector().reader(r.config(), asset, options);
        } catch (RuntimeException e) {
            throw new NodeException("Cannot read " + path + " from " + source + ": " + run.scrub(String.valueOf(
                e.getMessage())));
        }
        Node reader = new WithSettings(spec.node(), spec.settings());
        return columns.isEmpty() && where == null ? reader : new ShapedRead(reader, new ReadShaping(where, columns, limit));
    }

    /**
     * A read that runs a query: checked again as it is set up (the source may have changed since the
     * pipeline was saved), the login checked as for custom SQL rules, and the tables the database plans
     * to read recorded for lineage, before any row is read.
     */
    private Node createQuery(RunContext run, Map<String, Object> config, String source) throws NodeException {
        String sql = query(config);
        if (source == null || sql == null) {
            throw new NodeException("A read node with a query needs a source and one SELECT");
        }
        String checked;
        try {
            checked = checkQuery(sql);
        } catch (IllegalArgumentException e) {
            throw new NodeException(e.getMessage());
        }
        PipelineSources.Resolved r = sources.resolve("read query", source, run);
        String problem = queryProblem(r.source(), r.connector());
        if (problem != null) {
            throw new NodeException(problem);
        }
        JdbcConnector jdbc = (JdbcConnector) r.connector();
        // The URL's rules again: a source saved before a rule existed (preferQueryMode, readOnlyMode) keeps it
        if (!jdbc.validate(r.config()).isEmpty()) {
            throw new NodeException("query: " + source + "'s settings are no longer accepted; open the source and save "
                + "it again to see why");
        }
        try (Connection c = jdbc.connect(r.config())) {
            c.setAutoCommit(false);
            c.setReadOnly(true);
            String refusal = RuleRunner.privileged(c);
            if (refusal != null) {
                throw new NodeException("query: " + forQuery(refusal));
            }
            String plan;
            try (Statement st = c.createStatement()) {
                // The query was checked as PostgreSQL reads it with this on (ADR 0028)
                io.kilnmark.kiln.plugins.jdbc.PostgresConnector.conformingStrings(c);
                st.execute("SET TRANSACTION READ ONLY");
                st.setQueryTimeout(PLAN_TIMEOUT_SECONDS);
                try (ResultSet rs = st.executeQuery("EXPLAIN (VERBOSE, FORMAT JSON) " + checked)) {
                    plan = rs.next() ? rs.getString(1) : "[]";
                }
            } finally {
                c.rollback();
            }
            List<String> urns = new ArrayList<>();
            for (String table : relations(plan)) {
                String urn = PipelineSources.urn(source, table);
                if (urn != null) {
                    urns.add(urn);
                }
            }
            run.queryRead(source, sql, urns);
        } catch (SQLException e) {
            throw new NodeException("Cannot run the query on " + source + ": " + run.scrub(String.valueOf(
                e.getMessage())));
        }
        NodeSpec spec;
        try {
            spec = jdbc.queryReader(r.config(), checked, "query", Math.max(0, rowLimit(config)), BATCH_ROWS);
        } catch (RuntimeException e) {
            throw new NodeException("Cannot run the query on " + source + ": " + run.scrub(String.valueOf(
                e.getMessage())));
        }
        return new WithSettings(spec.node(), spec.settings());
    }
}

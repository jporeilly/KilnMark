package io.kilnmark.server.pipelines;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.arrow.vector.types.pojo.Schema;

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
import io.kilnmark.kiln.nodes.ReadShaping;
import io.kilnmark.kiln.nodes.expr.ExprException;
import io.kilnmark.kiln.nodes.expr.PushDown;

/**
 * The {@code read} node: a table, view or file of a saved source, read with its connector's
 * reader (docs/design/pipelines.md section 4). The server registers it, since it needs the saved
 * sources and their secrets; the YAML only names the source. Its columns, for validation and the
 * canvas, are the asset's as last catalogued.
 */
public final class ReadNodeFactory implements NodeFactory {

    public static final String TYPE = "read";
    static final int BATCH_ROWS = 8192;

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
            SettingSpec.required("asset", "Table or file", SettingSpec.Kind.ASSET,
                "A table or view as schema.table, or a file's path inside the source"),
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
        if (name != null) {
            Optional<Records.Source> s = sources.source(name);
            if (s.isEmpty()) {
                problems.add("source: there is no saved source called '" + name + "'");
            } else {
                Optional<Connector> c = sources.connector(s.get().kind());
                if (c.isEmpty()) {
                    problems.add("source: this server has no connector for sources of kind '" + s.get().kind() + "'");
                } else if (!c.get().capabilities().contains(Connector.Capability.READ)) {
                    problems.add("source: " + c.get().displayName() + " sources cannot be read in pipelines yet");
                }
            }
        }
        if (config.get("asset") != null && PipelineSources.text(config, "asset") == null) {
            problems.add("asset: one table or file, such as crm.customers or exports/orders.csv");
        }
        if (rowLimit(config) < 0) {
            problems.add("row_limit: a whole number of rows, 0 or more (0 reads every row)");
        }
        return problems;
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
        if (source == null || asset == null) {
            return null;
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
        if (where == null || source == null || asset == null) {
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
        Connector.ReadOptions options = new Connector.ReadOptions(urn, limit, BATCH_ROWS);
        if (!columns.isEmpty() || where != null) {
            Schema all = ArrowKinds.schemaOf(asset.fields());
            try {
                ReadShaping.outputSchema(all, columns, where); // the asset may have changed since the check
                PushDown.Split split = where == null || !r.connector().narrowsReads(r.config(), asset) ? null
                    : PushDown.split(where, all, compared(r.connector(), asset));
                // With a condition, the source's limit would count rows Kilnmark may still drop, unless the
                // source keeps exactly what Kilnmark keeps: otherwise the read node counts them, and stops
                // the read when it has enough.
                long sourceLimit = where == null || (split != null && split.keepsExactly()) ? limit : 0;
                options = new Connector.ReadOptions(urn, sourceLimit, BATCH_ROWS,
                    ReadShaping.needed(all, columns, where), split == null ? null : split.filter());
            } catch (ExprException e) {
                String m = e.getMessage();
                throw new NodeException((m.startsWith("columns:") ? "" : "where: ") + m, e);
            }
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
}

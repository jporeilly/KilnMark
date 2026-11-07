package io.kilnmark.kiln.nodes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;

import io.kilnmark.kiln.RowSet;
import io.kilnmark.kiln.SettingSpec;
import io.kilnmark.kiln.Node;
import io.kilnmark.kiln.NodeContext;
import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.NodeFactory;
import io.kilnmark.kiln.NodeKind;
import io.kilnmark.kiln.nodes.expr.Col;
import io.kilnmark.kiln.nodes.expr.Names;
import io.kilnmark.kiln.nodes.expr.Type;

/**
 * {@code lookup}: adds columns from the {@code lookup} input to each row of the {@code main} input,
 * by key. The lookup side is read in full first and held in memory (its Arrow batches, plus a hash
 * table from key to row), up to {@code memory_limit_mb}; then main rows stream through. Keys match
 * by value across number types (1, 1.0 and 1.00 match) and dates match timestamps at midnight;
 * nulls never match.
 *
 * <p>Rows of main with no match are kept with empty lookup columns ({@code unmatched: keep}, the
 * default for {@code join: left}), dropped ({@code drop}, the default for {@code join: inner}), or
 * sent to the {@code rejected} port unchanged ({@code rejected}). When the lookup side has a key
 * more than once, {@code duplicates} ({@link WhichRow}) says which of its rows is used: the first
 * to arrive by default, the last, the latest or earliest by {@code duplicates_by}, or an error that
 * names the key's columns. While the lookup side loads, main
 * batches are read and held too, so a main input fed by the same node as the lookup input cannot
 * stall the pipeline; they count against the same memory limit.
 */
public final class LookupFactory implements NodeFactory {

    public static final String TYPE = "lookup";
    private static final List<String> JOINS = List.of("left", "inner");
    private static final List<String> UNMATCHED = List.of("keep", "drop", "rejected");

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public NodeKind kind() {
        return NodeKind.TRANSFORM;
    }

    @Override
    public String category() {
        return io.kilnmark.kiln.NodeCategories.COMBINE;
    }

    @Override
    public String label() {
        return "Look up";
    }

    @Override
    public String help() {
        return "Adds columns from the lookup input to each main row with the same key, like a join. Rows with no "
            + "match are kept, dropped or sent to the rejected port.";
    }

    @Override
    public List<String> inputPorts() {
        return List.of("main", "lookup");
    }

    @Override
    public List<String> outputPorts() {
        return List.of("out", "rejected");
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
            SettingSpec.required("on", "Match on", SettingSpec.Kind.COLUMN_MAP,
                "Key columns: each main column and the lookup column it must equal."),
            SettingSpec.optional("keep", "Columns to add", SettingSpec.Kind.COLUMNS, null,
                "Lookup columns to add to main rows. Leave empty to add every lookup column except the keys."),
            SettingSpec.choice("join", "Join", false, "left",
                "What happens to main rows with no match: left keeps them, inner drops them.", JOINS),
            SettingSpec.choice("unmatched", "Rows with no match", false, null,
                "What to do with a main row that has no match: keep it, drop it, or send it to the rejected port. "
                    + "Overrides join when set.", UNMATCHED),
            WhichRow.setting("first", "When the lookup side has a key more than once, which of its rows is used: "
                + "the first or last to arrive, the latest or earliest by a column, or error to stop the run"),
            WhichRow.bySetting("lookup"),
            SettingSpec.optional("memory_limit_mb", "Memory limit (MB)", SettingSpec.Kind.NUMBER, 256,
                "The most memory the lookup side (and main rows waiting for it) may take."));
    }

    record Settings(LinkedHashMap<String, String> on, List<String> keep, String unmatched,
                    WhichRow.Settings duplicates, long limitMb) {
    }

    private static Settings read(Config c) {
        LinkedHashMap<String, String> on = c.map("on", true);
        List<String> keep = c.columns("keep", false);
        String join = c.choice("join", JOINS, "left");
        String unmatched = c.choice("unmatched", UNMATCHED, null);
        if (unmatched == null) {
            unmatched = join.equals("inner") ? "drop" : "keep";
        } else if (join.equals("inner") && unmatched.equals("keep") && c.has("join")) {
            c.problem("unmatched", "'keep' contradicts join: inner; leave one of them out");
        }
        WhichRow.Settings duplicates = WhichRow.read(c.raw(), "first", c.problems());
        long mb = c.number("memory_limit_mb", 256, 1, 1_048_576);
        return new Settings(on, keep, unmatched, duplicates, mb);
    }

    @Override
    public List<String> check(Map<String, Object> config) {
        Config c = new Config(config);
        read(c);
        return c.problems();
    }

    @Override
    public Node create(Map<String, Object> config) throws NodeException {
        Config c = new Config(config);
        Settings s = read(c);
        c.orThrow(TYPE);
        return new LookupNode(s);
    }

    @Override
    public Schema outputSchema(String port, Map<String, Schema> inputs, Map<String, Object> config)
        throws NodeException {
        if (!outputPorts().contains(port)) {
            throw new NodeException("A lookup node has no output port '" + port + "'");
        }
        Config c = new Config(config);
        Settings s = read(c);
        c.orThrow(TYPE);
        Schema main = inputs.get("main");
        Schema lookup = inputs.get("lookup");
        if (main == null) {
            return null;
        }
        if (port.equals("rejected")) {
            return main;
        }
        if (lookup == null) {
            return null;
        }
        Plan p = plan(lookup, s, "");
        p.checkMain(main, "");
        return p.outSchema(main);
    }

    /** What the lookup side gives: the columns to add and the key columns, checked against its schema. */
    record Plan(Settings settings, List<String> lookupKeys, List<Type> lookupTypes, List<Field> kept) {

        /** Checks the main side's key columns against the lookup's, and that kept names are free. */
        void checkMain(Schema main, String prefix) throws NodeException {
            int i = 0;
            for (Map.Entry<String, String> e : settings.on().entrySet()) {
                Field f = BaseNode.field(main, e.getKey());
                if (f == null) {
                    throw unknown(prefix, "on", e.getKey(), main, "main");
                }
                Type t = Type.of(f.getType());
                if (t == null) {
                    throw new NodeException(prefix + "on: the main column '" + e.getKey() + "' holds " + f.getType()
                        + " values, which cannot be matched");
                }
                Type other = lookupTypes.get(i++);
                Type common = Type.common(t, other);
                if (common == null) {
                    throw new NodeException(prefix + "on: the main column '" + e.getKey() + "' (" + t.family()
                        + ") cannot be matched with the lookup column '" + e.getValue() + "' (" + other.family()
                        + "); cast one of them first");
                }
            }
            for (Field k : kept) {
                if (BaseNode.field(main, k.getName()) != null) {
                    throw new NodeException(prefix + "keep: the column '" + k.getName() + "' is on both sides; "
                        + "list the lookup columns to add in keep, or rename one with a select node first");
                }
            }
        }

        Schema outSchema(Schema main) {
            List<Field> fields = new ArrayList<>(main.getFields());
            for (Field k : kept) {
                fields.add(settings.unmatched().equals("keep") ? BaseNode.nullable(k) : k);
            }
            return new Schema(fields);
        }
    }

    static Plan plan(Schema lookup, Settings s, String prefix) throws NodeException {
        List<String> keys = new ArrayList<>(s.on().values());
        List<Type> types = new ArrayList<>();
        for (String k : keys) {
            Field f = BaseNode.field(lookup, k);
            if (f == null) {
                throw unknown(prefix, "on", k, lookup, "lookup");
            }
            Type t = Type.of(f.getType());
            if (t == null) {
                throw new NodeException(prefix + "on: the lookup column '" + k + "' holds " + f.getType()
                    + " values, which cannot be matched");
            }
            types.add(t);
        }
        WhichRow.checkBy(lookup, s.duplicates(), prefix);
        List<Field> kept = new ArrayList<>();
        if (s.keep() != null) {
            for (String k : s.keep()) {
                Field f = BaseNode.field(lookup, k);
                if (f == null) {
                    throw unknown(prefix, "keep", k, lookup, "lookup");
                }
                kept.add(f);
            }
        } else {
            for (Field f : lookup.getFields()) {
                if (!keys.contains(f.getName())) {
                    kept.add(f);
                }
            }
        }
        return new Plan(s, keys, types, kept);
    }

    private static NodeException unknown(String prefix, String key, String column, Schema in, String side) {
        String near = Names.suggest(column, BaseNode.names(in));
        return new NodeException(prefix + key + ": the " + side + " input has no column '" + column + "'"
            + (near != null ? "; did you mean " + near + "?" : ""));
    }

    /** The running node. */
    static final class LookupNode extends BaseNode {
        private static final int NO_MATCH = -1;
        /** How long the lookup side may be quiet before a waiting main batch is taken. */
        private static final long IDLE_MILLIS = 250;
        private final Settings settings;
        private RowSet main;
        private RowSet lookup;
        private final List<VectorSchemaRoot> waiting = new ArrayList<>();
        private boolean loaded;
        /** Whether the lookup side has been quiet long enough that ready main batches are taken. */
        private boolean idle;
        private Schema lookupSchema;
        private Plan plan;
        private final List<VectorSchemaRoot> stored = new ArrayList<>();
        /** The lookup row used for each key, as batch and row: the winner by {@code duplicates}. */
        private final WhichRow.Winners index;
        private long indexBytes;
        private long lookupRows;
        private Schema mainChecked;
        private Schema outSchema;

        LookupNode(Settings settings) {
            super(TYPE);
            this.settings = settings;
            this.index = new WhichRow.Winners(settings.duplicates());
        }

        @Override
        public void init(NodeContext ctx) throws NodeException {
            super.init(ctx);
            main = ctx.input("main");
            lookup = ctx.input("lookup");
        }

        @Override
        public boolean process(NodeContext ctx) throws NodeException {
            try {
                if (!loaded) {
                    load(ctx);
                    return true;
                }
                VectorSchemaRoot batch = !waiting.isEmpty() ? waiting.removeFirst()
                    : main.atEnd() ? null : main.take(ctx.allocator());
                if (batch == null) {
                    return false;
                }
                match(batch, ctx);
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NodeException("Interrupted", e);
            }
        }

        /**
         * Reads the next batch of the lookup side into the table. While none is waiting, takes any
         * main batch that is, and holds it: when both inputs come from one node upstream, that node
         * would otherwise wait for room on main and never finish the lookup side.
         */
        private void load(NodeContext ctx) throws NodeException, InterruptedException {
            VectorSchemaRoot batch = lookup.poll(ctx.allocator(), idle ? 0 : IDLE_MILLIS, TimeUnit.MILLISECONDS);
            if (batch == null && !lookup.atEnd()) {
                // Nothing on the lookup side for a while. If both inputs come from one node upstream,
                // that node may be waiting for room on main: take the main batches that are ready, until
                // the lookup side moves again.
                idle = true;
                VectorSchemaRoot early = main.atEnd() ? null : main.poll(ctx.allocator(), 0, TimeUnit.MILLISECONDS);
                if (early != null) {
                    waiting.add(early);
                    checkMemory(ctx);
                    return;
                }
                batch = lookup.poll(ctx.allocator(), 5, TimeUnit.MILLISECONDS);
                if (batch == null && !lookup.atEnd()) {
                    return;
                }
            }
            idle = false;
            if (batch == null) {
                loaded = true;
                if (plan == null) {
                    // no lookup rows at all: its columns as the pipeline check worked them out, if known
                    Schema declared = ctx.inputSchema("lookup");
                    if (declared != null) {
                        plan = plan(declared, settings, "");
                        lookupSchema = declared;
                    }
                }
                return;
            }
            index(batch, ctx);
        }

        private void checkMemory(NodeContext ctx) throws NodeException {
            long held = ctx.allocator().getAllocatedMemory() + indexBytes;
            if (held > megabytes(settings.limitMb())) {
                throw fail("the lookup side" + (waiting.isEmpty() ? "" : " and " + count(waiting.size())
                    + " main batches held while it loads") + (waiting.isEmpty() ? " takes" : " take") + " more than " + settings.limitMb() + " MB ("
                    + count(lookupRows) + " lookup rows read so far); raise memory_limit_mb, or keep fewer columns "
                    + "from the lookup side");
            }
        }

        /** Adds one batch of the lookup side to the table; the batch is given up. */
        private void index(VectorSchemaRoot batch, NodeContext ctx) throws NodeException {
            try {
                if (lookupSchema == null) {
                    plan = plan(batch.getSchema(), settings, "");
                    lookupSchema = batch.getSchema();
                } else if (!batch.getSchema().equals(lookupSchema)) {
                    throw fail("the lookup input's columns changed part-way through");
                }
                int n = batch.getRowCount();
                if (n == 0) {
                    return;
                }
                Keys.Reader[] readers = new Keys.Reader[plan.lookupKeys().size()];
                for (int k = 0; k < readers.length; k++) {
                    readers[k] = Keys.universal(batch.getVector(plan.lookupKeys().get(k)));
                }
                int at = stored.size();
                String byColumn = settings.duplicates().by();
                Col by = byColumn == null ? null : WhichRow.values(batch, byColumn);
                List<String> lookupKeys = plan.lookupKeys();
                long before = index.bytes();
                for (int i = 0; i < n; i++) {
                    Object key = Keys.of(readers, i, true);
                    if (key == null) {
                        continue;
                    }
                    long ref = ((long) at << 32) | i;
                    index.offer(key, ref, by == null ? null : by.get(i), "the lookup side has", lookupKeys);
                }
                indexBytes += index.bytes() - before;
                lookupRows += n;
                List<FieldVector> keep = new ArrayList<>();
                for (Field f : plan.kept()) {
                    keep.add(move(batch.getVector(f.getName()), f, ctx.allocator()));
                }
                stored.add(new VectorSchemaRoot(plan.kept(), keep, n));
            } finally {
                batch.close();
            }
            checkMemory(ctx);
        }

        /** Matches one main batch and sends its rows on; the batch is given up. */
        private void match(VectorSchemaRoot batch, NodeContext ctx) throws NodeException {
            try {
                Schema main = batch.getSchema();
                if (!main.equals(mainChecked)) {
                    if (plan == null) {
                        // the lookup side had no rows at all: its columns are unknown, so none can be added
                        plan = new Plan(settings, new ArrayList<>(settings.on().values()),
                            settings.on().keySet().stream().map(k -> keyType(main, k)).toList(), List.of());
                    }
                    plan.checkMain(main, "");
                    outSchema = plan.outSchema(main);
                    mainChecked = main;
                }
                int n = batch.getRowCount();
                Keys.Reader[] readers = new Keys.Reader[settings.on().size()];
                int k = 0;
                for (String col : settings.on().keySet()) {
                    readers[k++] = Keys.universal(batch.getVector(col));
                }
                long[] refs = new long[n];
                int[] outRows = new int[n];
                int[] rejectedRows = new int[n];
                int outCount = 0;
                int rejectedCount = 0;
                String unmatched = settings.unmatched();
                for (int i = 0; i < n; i++) {
                    Object key = Keys.of(readers, i, true);
                    Long ref = key == null ? null : index.winner(key);
                    if (ref != null) {
                        refs[outCount] = ref;
                        outRows[outCount++] = i;
                    } else if (unmatched.equals("keep")) {
                        refs[outCount] = NO_MATCH;
                        outRows[outCount++] = i;
                    } else if (unmatched.equals("rejected")) {
                        rejectedRows[rejectedCount++] = i;
                    }
                }
                if (rejectedCount > 0 && out(ctx, "rejected") != null) {
                    put(ctx, "rejected", copy(batch, rejectedRows, rejectedCount, ctx.allocator()));
                }
                if (outCount > 0 && out(ctx, "out") != null) {
                    put(ctx, "out", build(batch, outRows, refs, outCount, ctx));
                }
            } finally {
                batch.close();
            }
        }

        private static Type keyType(Schema main, String column) {
            Field f = BaseNode.field(main, column);
            Type t = f == null ? null : Type.of(f.getType());
            return t == null ? Type.NULL : t;
        }

        private VectorSchemaRoot build(VectorSchemaRoot batch, int[] rows, long[] refs, int count, NodeContext ctx) {
            List<FieldVector> vectors = new ArrayList<>();
            try {
                boolean all = count == batch.getRowCount();
                if (all) {
                    for (FieldVector v : batch.getFieldVectors()) {
                        vectors.add(move(v, v.getField(), ctx.allocator()));
                    }
                } else {
                    try (VectorSchemaRoot part = copy(batch, rows, count, ctx.allocator())) {
                        for (FieldVector v : part.getFieldVectors()) {
                            vectors.add(move(v, v.getField(), ctx.allocator()));
                        }
                    }
                }
                List<Field> outFields = outSchema.getFields();
                int mainColumns = batch.getSchema().getFields().size();
                for (int c = 0; c < plan.kept().size(); c++) {
                    Field f = outFields.get(mainColumns + c);
                    FieldVector v = f.createVector(ctx.allocator());
                    vectors.add(v);
                    v.setInitialCapacity(count);
                    v.allocateNew();
                    String source = plan.kept().get(c).getName();
                    for (int j = 0; j < count; j++) {
                        long ref = refs[j];
                        if (ref == NO_MATCH) {
                            v.setNull(j);
                        } else {
                            VectorSchemaRoot from = stored.get((int) (ref >>> 32));
                            v.copyFromSafe((int) ref, j, from.getVector(source));
                        }
                    }
                    v.setValueCount(count);
                }
                return new VectorSchemaRoot(outSchema, vectors, count);
            } catch (RuntimeException e) {
                vectors.forEach(FieldVector::close);
                throw e;
            }
        }

        @Override
        public void close() {
            waiting.forEach(VectorSchemaRoot::close);
            waiting.clear();
            stored.forEach(VectorSchemaRoot::close);
            stored.clear();
            index.clear();
        }
    }
}

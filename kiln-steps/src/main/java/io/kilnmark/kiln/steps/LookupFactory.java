package io.kilnmark.kiln.steps;

import java.util.ArrayList;
import java.util.HashMap;
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
import io.kilnmark.kiln.Step;
import io.kilnmark.kiln.StepContext;
import io.kilnmark.kiln.StepException;
import io.kilnmark.kiln.StepFactory;
import io.kilnmark.kiln.StepKind;
import io.kilnmark.kiln.steps.expr.Names;
import io.kilnmark.kiln.steps.expr.Type;

/**
 * {@code lookup}: adds columns from the {@code lookup} input to each row of the {@code main} input,
 * by key. The lookup side is read in full first and held in memory (its Arrow batches, plus a hash
 * table from key to row), up to {@code memory_limit_mb}; then main rows stream through. Keys match
 * by value across number types (1, 1.0 and 1.00 match) and dates match timestamps at midnight;
 * nulls never match.
 *
 * <p>Rows of main with no match are kept with empty lookup columns ({@code unmatched: keep}, the
 * default for {@code join: left}), dropped ({@code drop}, the default for {@code join: inner}), or
 * sent to the {@code rejected} port unchanged ({@code rejected}). While the lookup side loads, main
 * batches are read and held too, so a main input fed by the same step as the lookup input cannot
 * stall the pipeline; they count against the same memory limit.
 */
public final class LookupFactory implements StepFactory {

    public static final String TYPE = "lookup";
    private static final List<String> JOINS = List.of("left", "inner");
    private static final List<String> UNMATCHED = List.of("keep", "drop", "rejected");
    private static final List<String> DUPLICATES = List.of("first", "error");

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public StepKind kind() {
        return StepKind.TRANSFORM;
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
                "left keeps main rows with no match; inner drops them.", JOINS),
            SettingSpec.choice("unmatched", "Rows with no match", false, null,
                "keep, drop, or send to the rejected port. Overrides join when set.", UNMATCHED),
            SettingSpec.choice("duplicates", "Duplicate keys", false, "first",
                "When the lookup side has a key more than once: use the first row, or stop with an error.", DUPLICATES),
            SettingSpec.optional("memory_limit_mb", "Memory limit (MB)", SettingSpec.Kind.NUMBER, 256,
                "The most memory the lookup side (and main rows waiting for it) may take."));
    }

    record Settings(LinkedHashMap<String, String> on, List<String> keep, String unmatched, boolean failOnDuplicates,
                    long limitMb) {
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
        String duplicates = c.choice("duplicates", DUPLICATES, "first");
        long mb = c.number("memory_limit_mb", 256, 1, 1_048_576);
        return new Settings(on, keep, unmatched, duplicates.equals("error"), mb);
    }

    @Override
    public List<String> check(Map<String, Object> config) {
        Config c = new Config(config);
        read(c);
        return c.problems();
    }

    @Override
    public Step create(Map<String, Object> config) throws StepException {
        Config c = new Config(config);
        Settings s = read(c);
        c.orThrow(TYPE);
        return new LookupStep(s);
    }

    @Override
    public Schema outputSchema(String port, Map<String, Schema> inputs, Map<String, Object> config)
        throws StepException {
        if (!outputPorts().contains(port)) {
            throw new StepException("A lookup step has no output port '" + port + "'");
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
        void checkMain(Schema main, String prefix) throws StepException {
            int i = 0;
            for (Map.Entry<String, String> e : settings.on().entrySet()) {
                Field f = BaseStep.field(main, e.getKey());
                if (f == null) {
                    throw unknown(prefix, "on", e.getKey(), main, "main");
                }
                Type t = Type.of(f.getType());
                if (t == null) {
                    throw new StepException(prefix + "on: the main column '" + e.getKey() + "' holds " + f.getType()
                        + " values, which cannot be matched");
                }
                Type other = lookupTypes.get(i++);
                Type common = Type.common(t, other);
                if (common == null) {
                    throw new StepException(prefix + "on: the main column '" + e.getKey() + "' (" + t.family()
                        + ") cannot be matched with the lookup column '" + e.getValue() + "' (" + other.family()
                        + "); cast one of them first");
                }
            }
            for (Field k : kept) {
                if (BaseStep.field(main, k.getName()) != null) {
                    throw new StepException(prefix + "keep: the column '" + k.getName() + "' is on both sides; "
                        + "list the lookup columns to add in keep, or rename one with a select step first");
                }
            }
        }

        Schema outSchema(Schema main) {
            List<Field> fields = new ArrayList<>(main.getFields());
            for (Field k : kept) {
                fields.add(settings.unmatched().equals("keep") ? BaseStep.nullable(k) : k);
            }
            return new Schema(fields);
        }
    }

    static Plan plan(Schema lookup, Settings s, String prefix) throws StepException {
        List<String> keys = new ArrayList<>(s.on().values());
        List<Type> types = new ArrayList<>();
        for (String k : keys) {
            Field f = BaseStep.field(lookup, k);
            if (f == null) {
                throw unknown(prefix, "on", k, lookup, "lookup");
            }
            Type t = Type.of(f.getType());
            if (t == null) {
                throw new StepException(prefix + "on: the lookup column '" + k + "' holds " + f.getType()
                    + " values, which cannot be matched");
            }
            types.add(t);
        }
        List<Field> kept = new ArrayList<>();
        if (s.keep() != null) {
            for (String k : s.keep()) {
                Field f = BaseStep.field(lookup, k);
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

    private static StepException unknown(String prefix, String key, String column, Schema in, String side) {
        String near = Names.suggest(column, BaseStep.names(in));
        return new StepException(prefix + key + ": the " + side + " input has no column '" + column + "'"
            + (near != null ? "; did you mean " + near + "?" : ""));
    }

    /** The running step. */
    static final class LookupStep extends BaseStep {
        private static final int NO_MATCH = -1;
        private final Settings settings;
        private RowSet main;
        private RowSet lookup;
        private final List<VectorSchemaRoot> waiting = new ArrayList<>();
        private boolean loaded;
        private Schema lookupSchema;
        private Plan plan;
        private final List<VectorSchemaRoot> stored = new ArrayList<>();
        private final Map<Object, Long> index = new HashMap<>();
        private long indexBytes;
        private long lookupRows;
        private Schema mainChecked;
        private Schema outSchema;

        LookupStep(Settings settings) {
            super(TYPE);
            this.settings = settings;
        }

        @Override
        public void init(StepContext ctx) throws StepException {
            super.init(ctx);
            main = ctx.input("main");
            lookup = ctx.input("lookup");
        }

        @Override
        public boolean process(StepContext ctx) throws StepException {
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
                throw new StepException("Interrupted", e);
            }
        }

        /**
         * Reads the next batch of the lookup side into the table. While none is waiting, takes any
         * main batch that is, and holds it: when both inputs come from one step upstream, that step
         * would otherwise wait for room on main and never finish the lookup side.
         */
        private void load(StepContext ctx) throws StepException, InterruptedException {
            VectorSchemaRoot batch = lookup.poll(ctx.allocator(), 0, TimeUnit.MILLISECONDS);
            if (batch == null && !lookup.atEnd()) {
                VectorSchemaRoot early = main.poll(ctx.allocator(), 0, TimeUnit.MILLISECONDS);
                if (early != null) {
                    waiting.add(early);
                    checkMemory(ctx);
                    return;
                }
                batch = lookup.poll(ctx.allocator(), 5, TimeUnit.MILLISECONDS);
            }
            if (batch == null) {
                loaded = lookup.atEnd();
                return;
            }
            index(batch, ctx);
        }

        private void checkMemory(StepContext ctx) throws StepException {
            long held = ctx.allocator().getAllocatedMemory() + indexBytes;
            if (held > megabytes(settings.limitMb())) {
                throw fail("the lookup side holds more than " + settings.limitMb() + " MB (" + count(lookupRows)
                    + " rows read so far, and " + count(waiting.size()) + " main batches waiting); raise "
                    + "memory_limit_mb, or keep fewer columns from the lookup side");
            }
        }

        /** Adds one batch of the lookup side to the table; the batch is given up. */
        private void index(VectorSchemaRoot batch, StepContext ctx) throws StepException {
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
                for (int i = 0; i < n; i++) {
                    Object key = Keys.of(readers, i, true);
                    if (key == null) {
                        continue;
                    }
                    long ref = ((long) at << 32) | i;
                    Long before = index.putIfAbsent(key, ref);
                    if (before == null) {
                        indexBytes += 64 + Keys.size(key);
                    } else if (settings.failOnDuplicates()) {
                        throw fail("the lookup side has more than one row with the key " + describe(key)
                            + "; set duplicates: first to use the first such row");
                    }
                }
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

        private String describe(Object key) {
            List<String> names = new ArrayList<>(settings.on().keySet());
            List<?> parts = key instanceof List<?> l ? l : List.of(key);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                String v = String.valueOf(parts.get(i));
                sb.append(names.get(i)).append(" = ").append(v.length() > 40 ? v.substring(0, 40) + "…" : v);
            }
            return sb.toString();
        }

        /** Matches one main batch and sends its rows on; the batch is given up. */
        private void match(VectorSchemaRoot batch, StepContext ctx) throws StepException {
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
                    Long ref = key == null ? null : index.get(key);
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
            Field f = BaseStep.field(main, column);
            Type t = f == null ? null : Type.of(f.getType());
            return t == null ? Type.NULL : t;
        }

        private VectorSchemaRoot build(VectorSchemaRoot batch, int[] rows, long[] refs, int count, StepContext ctx) {
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

package io.kilnmark.kiln.steps;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
import io.kilnmark.kiln.steps.expr.ArrowIO;
import io.kilnmark.kiln.steps.expr.ExprException;
import io.kilnmark.kiln.steps.expr.Type;

/**
 * {@code union}: appends the rows of every input, matching columns by name. A column missing from
 * an input is null in its rows; a column whose type differs between inputs is widened to a type
 * that holds both (int32 and int64 to int64, whole numbers and decimals to decimal, and so on), or
 * is an error. With {@code strict}, every input must have the same columns.
 *
 * <p>Inputs are read as batches arrive, whichever input they come from ({@link RowSet#takeAny}).
 * Until every input has sent its first batch (or ended), the output's columns are not known, so
 * batches wait in memory, up to {@code memory_limit_mb}. An input that ends without sending any
 * batch adds no columns.
 */
public final class UnionFactory implements StepFactory {

    public static final String TYPE = "union";

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
        return "Union";
    }

    @Override
    public String help() {
        return "Appends the rows of all its inputs, matching columns by name. Columns an input lacks are left "
            + "empty (null).";
    }

    @Override
    public List<String> inputPorts() {
        return List.of("in*");
    }

    @Override
    public List<String> outputPorts() {
        return List.of("out");
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
            SettingSpec.optional("strict", "Same columns only", SettingSpec.Kind.BOOLEAN, false,
                "Fail unless every input has exactly the same columns, with the same types."),
            SettingSpec.optional("memory_limit_mb", "Memory limit (MB)", SettingSpec.Kind.NUMBER, 256,
                "The most memory batches may take while the union waits for every input's first batch."));
    }

    @Override
    public List<String> check(Map<String, Object> config) {
        Config c = new Config(config);
        c.bool("strict", false);
        c.number("memory_limit_mb", 256, 1, 1_048_576);
        return c.problems();
    }

    @Override
    public Step create(Map<String, Object> config) throws StepException {
        Config c = new Config(config);
        boolean strict = c.bool("strict", false);
        long mb = c.number("memory_limit_mb", 256, 1, 1_048_576);
        c.orThrow(TYPE);
        return new UnionStep(strict, mb);
    }

    /**
     * Every input's columns: union has one port taking many edges, so every schema in
     * {@code inputs} is one of its inputs, keyed {@code in[0]}, {@code in[1]} ... in listed order.
     */
    @Override
    public Schema outputSchema(String port, Map<String, Schema> inputs, Map<String, Object> config)
        throws StepException {
        if (!port.equals("out")) {
            throw new StepException("A union step has no output port '" + port + "'");
        }
        if (inputs.isEmpty() || inputs.containsValue(null)) {
            return null;
        }
        Config c = new Config(config);
        boolean strict = c.bool("strict", false);
        c.orThrow(TYPE);
        List<Map.Entry<String, Schema>> entries = new ArrayList<>(inputs.entrySet());
        entries.sort(java.util.Comparator.comparingInt(e -> index(e.getKey())));
        return combine(entries.stream().map(Map.Entry::getValue).toList(), strict, "");
    }

    /** k for a key {@code in[k]}; keys of other forms keep their place. */
    private static int index(String key) {
        int open = key.indexOf('[');
        if (open >= 0 && key.endsWith("]")) {
            try {
                return Integer.parseInt(key.substring(open + 1, key.length() - 1));
            } catch (NumberFormatException e) {
                return Integer.MAX_VALUE;
            }
        }
        return Integer.MAX_VALUE;
    }

    /** The output columns for these inputs' columns. */
    static Schema combine(List<Schema> inputs, boolean strict, String prefix) throws StepException {
        if (strict) {
            Schema first = inputs.getFirst();
            for (int i = 1; i < inputs.size(); i++) {
                String diff = difference(first, inputs.get(i));
                if (diff != null) {
                    throw new StepException(prefix + "strict: input " + (i + 1) + " differs from input 1: " + diff);
                }
            }
        }
        LinkedHashMap<String, Field> fields = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> seenIn = new LinkedHashMap<>();
        for (Schema s : inputs) {
            for (Field f : s.getFields()) {
                seenIn.merge(f.getName(), 1, Integer::sum);
                Field before = fields.get(f.getName());
                if (before == null) {
                    fields.put(f.getName(), f);
                    continue;
                }
                if (before.getType().equals(f.getType())) {
                    if (f.isNullable() && !before.isNullable()) {
                        fields.put(f.getName(), BaseStep.nullable(before));
                    }
                    continue;
                }
                Type a = Type.of(before.getType());
                Type b = Type.of(f.getType());
                Type common = a == null || b == null ? null : Type.common(a, b);
                if (common == null || common.isNull()) {
                    throw new StepException(prefix + "the column '" + f.getName() + "' is " + describe(before)
                        + " in one input and " + describe(f) + " in another, and these do not combine; cast one of "
                        + "them with a select step first");
                }
                fields.put(f.getName(), common.field(f.getName()));
            }
        }
        List<Field> out = new ArrayList<>();
        for (Field f : fields.values()) {
            boolean everywhere = seenIn.get(f.getName()) == inputs.size();
            out.add(everywhere ? f : BaseStep.nullable(f));
        }
        return new Schema(out);
    }

    private static String describe(Field f) {
        Type t = Type.of(f.getType());
        return t == null ? f.getType().toString() : t.toString();
    }

    /** How two inputs' columns differ, or null if they have the same names and types. */
    private static String difference(Schema a, Schema b) {
        for (Field f : a.getFields()) {
            Field g = BaseStep.field(b, f.getName());
            if (g == null) {
                return "it has no column '" + f.getName() + "'";
            }
            if (!Objects.equals(f.getType(), g.getType())) {
                return "the column '" + f.getName() + "' is " + describe(g) + ", not " + describe(f);
            }
        }
        for (Field g : b.getFields()) {
            if (BaseStep.field(a, g.getName()) == null) {
                return "it has an extra column '" + g.getName() + "'";
            }
        }
        return null;
    }

    /** The running step. */
    static final class UnionStep extends BaseStep {
        private final boolean strict;
        private final long limitMb;
        private List<RowSet> sources;
        private Schema[] schemas;
        private final List<VectorSchemaRoot> waiting = new ArrayList<>();
        private Schema out;

        UnionStep(boolean strict, long limitMb) {
            super(TYPE);
            this.strict = strict;
            this.limitMb = limitMb;
        }

        @Override
        public void init(StepContext ctx) throws StepException {
            super.init(ctx);
            sources = ctx.inputs("in");
            schemas = new Schema[sources.size()];
        }

        @Override
        public boolean process(StepContext ctx) throws StepException {
            RowSet.Taken taken;
            try {
                // whichever input has a batch: reading them in turn stalls inputs that share a step upstream
                taken = RowSet.takeAny(sources, ctx.allocator());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StepException("Interrupted", e);
            }
            if (taken == null) {
                if (out == null) {
                    decide();
                }
                flush(ctx);
                return false;
            }
            if (out != null) {
                put(ctx, "out", conform(taken.batch(), ctx));
                return true;
            }
            if (schemas[taken.index()] == null) {
                schemas[taken.index()] = taken.batch().getSchema();
            }
            waiting.add(taken.batch());
            if (ctx.allocator().getAllocatedMemory() > megabytes(limitMb)) {
                throw fail("batches waiting for every input's first batch take more than " + limitMb
                    + " MB; raise memory_limit_mb");
            }
            if (known()) {
                decide();
                flush(ctx);
            }
            return true;
        }

        /** Whether every input has sent a batch or ended. */
        private boolean known() {
            for (int i = 0; i < schemas.length; i++) {
                if (schemas[i] == null && !sources.get(i).atEnd()) {
                    return false;
                }
            }
            return true;
        }

        private void decide() throws StepException {
            List<Schema> seen = new ArrayList<>();
            for (Schema s : schemas) {
                if (s != null) {
                    seen.add(s);
                }
            }
            if (!seen.isEmpty()) {
                out = combine(seen, strict, "");
            }
        }

        private void flush(StepContext ctx) throws StepException {
            while (!waiting.isEmpty()) {
                VectorSchemaRoot b = waiting.removeFirst();
                put(ctx, "out", conform(b, ctx));
            }
        }

        /** The batch with the output's columns, in its order; the batch itself is closed. */
        private VectorSchemaRoot conform(VectorSchemaRoot batch, StepContext ctx) throws StepException {
            List<FieldVector> vectors = new ArrayList<>();
            try {
                int n = batch.getRowCount();
                for (Field f : out.getFields()) {
                    FieldVector v = batch.getVector(f.getName());
                    if (v == null) {
                        vectors.add(nulls(f, n, ctx.allocator()));
                    } else if (v.getField().getType().equals(f.getType())) {
                        vectors.add(move(v, f, ctx.allocator()));
                    } else {
                        try {
                            vectors.add(ArrowIO.write(ArrowIO.read(v), f, ctx.allocator()));
                        } catch (ExprException | IllegalArgumentException e) {
                            throw fail("the column '" + f.getName() + "' could not be converted to " + describe(f) + ": "
                                + e.getMessage());
                        }
                    }
                }
                return new VectorSchemaRoot(out, vectors, n);
            } catch (StepException | RuntimeException e) {
                vectors.forEach(FieldVector::close);
                throw e;
            } finally {
                batch.close();
            }
        }

        @Override
        public void close() {
            waiting.forEach(VectorSchemaRoot::close);
            waiting.clear();
        }
    }
}

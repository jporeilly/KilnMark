package io.kilnmark.kiln.steps;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;

import io.kilnmark.kiln.SettingSpec;
import io.kilnmark.kiln.Step;
import io.kilnmark.kiln.StepContext;
import io.kilnmark.kiln.StepException;
import io.kilnmark.kiln.StepFactory;
import io.kilnmark.kiln.StepKind;
import io.kilnmark.kiln.steps.expr.ArrowIO;
import io.kilnmark.kiln.steps.expr.Col;
import io.kilnmark.kiln.steps.expr.ExprException;
import io.kilnmark.kiln.steps.expr.Type;

/**
 * {@code select}: keeps or drops columns, renames, reorders and casts them. Every setting names
 * columns as they arrive (before renaming). Done in this order: keep or drop, cast, rename, order.
 */
public final class SelectFactory implements StepFactory {

    public static final String TYPE = "select";

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
        return "Select columns";
    }

    @Override
    public String help() {
        return "Keeps or drops columns, and renames, reorders and changes the type of the columns kept. "
            + "Every setting names columns as they arrive.";
    }

    @Override
    public List<String> inputPorts() {
        return List.of("in");
    }

    @Override
    public List<String> outputPorts() {
        return List.of("out");
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
            SettingSpec.optional("keep", "Keep", SettingSpec.Kind.COLUMNS, null,
                "Only these columns, in this order. Leave empty to keep them all."),
            SettingSpec.optional("drop", "Drop", SettingSpec.Kind.COLUMNS, null,
                "Columns to leave out. Use either keep or drop, not both."),
            SettingSpec.optional("rename", "Rename", SettingSpec.Kind.COLUMN_MAP, null,
                "New names: each column as it arrives, and its new name."),
            SettingSpec.optional("order", "Order", SettingSpec.Kind.COLUMNS, null,
                "Columns to put first, in this order; the rest follow as they were."),
            SettingSpec.optional("cast", "Change types", SettingSpec.Kind.COLUMN_MAP, null,
                "Columns to convert, and the type for each: boolean, int64, float64, decimal(p,s), utf8, date or "
                    + "timestamp."));
    }

    /** The settings, read. */
    record Settings(List<String> keep, List<String> drop, Map<String, String> rename, List<String> order,
                    Map<String, Type> cast) {
    }

    static Settings read(Config c) {
        List<String> keep = c.columns("keep", false);
        List<String> drop = c.columns("drop", false);
        if (keep != null && drop != null) {
            c.problem("drop", "use either keep or drop, not both");
        }
        LinkedHashMap<String, String> rename = c.map("rename", false);
        if (rename != null) {
            Set<String> targets = new HashSet<>();
            for (Map.Entry<String, String> e : rename.entrySet()) {
                if (!targets.add(e.getValue())) {
                    c.problem("rename", "two columns are renamed to '" + e.getValue() + "'");
                }
            }
        }
        List<String> order = c.columns("order", false);
        LinkedHashMap<String, String> castNames = c.map("cast", false);
        Map<String, Type> cast = new LinkedHashMap<>();
        if (castNames != null) {
            for (Map.Entry<String, String> e : castNames.entrySet()) {
                Type t = Type.named(e.getValue());
                if (t == null || t.isNull()) {
                    c.problem("cast." + e.getKey(), "unknown type '" + e.getValue()
                        + "'; use boolean, int64, float64, decimal(p,s), utf8, date or timestamp");
                } else {
                    cast.put(e.getKey(), t);
                }
            }
        }
        return new Settings(keep, drop, rename == null ? Map.of() : rename, order, cast);
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
        return new SelectStep(s);
    }

    @Override
    public Schema outputSchema(String port, Map<String, Schema> inputs, Map<String, Object> config)
        throws StepException {
        if (!port.equals("out")) {
            throw new StepException("A select step has no output port '" + port + "'");
        }
        Schema in = inputs.get("in");
        if (in == null) {
            return null;
        }
        Config c = new Config(config);
        Settings s = read(c);
        c.orThrow(TYPE);
        return plan(in, s).schema();
    }

    /** One output column: where it comes from, and the type to cast it to (or null). */
    record Out(String source, Field field, Type cast) {
    }

    record Plan(List<Out> columns) {
        Schema schema() {
            return new Schema(columns.stream().map(Out::field).toList());
        }
    }

    /** Works out the output columns. */
    static Plan plan(Schema in, Settings s) throws StepException {
        String prefix = "";
        List<String> names = BaseStep.names(in);
        List<String> chosen = new ArrayList<>();
        if (s.keep() != null) {
            for (String k : s.keep()) {
                require(in, k, "keep", prefix);
                chosen.add(k);
            }
        } else {
            chosen.addAll(names);
        }
        if (s.drop() != null) {
            for (String d : s.drop()) {
                require(in, d, "drop", prefix);
                chosen.remove(d);
            }
        }
        for (String k : s.cast().keySet()) {
            require(in, k, "cast", prefix);
            if (!chosen.contains(k)) {
                throw new StepException(prefix + "cast: the column '" + k + "' is not kept");
            }
            Field f = BaseStep.field(in, k);
            Type from = Type.of(f.getType());
            Type to = s.cast().get(k);
            if (from == null) {
                throw new StepException(prefix + "cast: the column '" + k + "' holds " + f.getType()
                    + " values, which cannot be converted");
            }
            if (!Type.canCast(from, to)) {
                throw new StepException(prefix + "cast: the column '" + k + "' is " + from.family()
                    + ", which cannot be turned into " + to.family());
            }
        }
        for (String k : s.rename().keySet()) {
            require(in, k, "rename", prefix);
            if (!chosen.contains(k)) {
                throw new StepException(prefix + "rename: the column '" + k + "' is not kept");
            }
        }
        if (s.order() != null) {
            List<String> first = new ArrayList<>();
            for (String o : s.order()) {
                require(in, o, "order", prefix);
                if (!chosen.contains(o)) {
                    throw new StepException(prefix + "order: the column '" + o + "' is not kept");
                }
                first.add(o);
            }
            List<String> rest = new ArrayList<>(chosen);
            rest.removeAll(first);
            chosen = new ArrayList<>(first);
            chosen.addAll(rest);
        }
        List<Out> out = new ArrayList<>();
        Set<String> outNames = new HashSet<>();
        for (String name : chosen) {
            Field f = BaseStep.field(in, name);
            String newName = s.rename().getOrDefault(name, name);
            if (!outNames.add(newName)) {
                throw new StepException(prefix + "rename: two columns would be called '" + newName + "'");
            }
            Type cast = s.cast().get(name);
            Field field;
            if (cast != null) {
                field = cast.field(newName);
            } else {
                field = new Field(newName, new FieldType(f.isNullable(), f.getType(), f.getDictionary(), f.getMetadata()),
                    f.getChildren());
            }
            out.add(new Out(name, field, cast));
        }
        return new Plan(out);
    }

    private static void require(Schema in, String column, String key, String prefix) throws StepException {
        if (BaseStep.field(in, column) == null) {
            String near = io.kilnmark.kiln.steps.expr.Names.suggest(column, BaseStep.names(in));
            throw new StepException(prefix + key + ": unknown column '" + column + "'"
                + (near != null ? "; did you mean " + near + "?" : ""));
        }
    }

    /** The running step. */
    static final class SelectStep extends BaseStep {
        private final Settings settings;
        private Schema boundTo;
        private Plan plan;

        SelectStep(Settings settings) {
            super(TYPE);
            this.settings = settings;
        }

        @Override
        public boolean process(StepContext ctx) throws StepException {
            VectorSchemaRoot batch = take(ctx, "in");
            if (batch == null) {
                return false;
            }
            VectorSchemaRoot out;
            try {
                if (!batch.getSchema().equals(boundTo)) {
                    plan = plan(batch.getSchema(), settings);
                    boundTo = batch.getSchema();
                }
                out = select(batch, ctx);
            } finally {
                batch.close();
            }
            put(ctx, "out", out);
            return true;
        }

        private VectorSchemaRoot select(VectorSchemaRoot batch, StepContext ctx) throws StepException {
            List<FieldVector> vectors = new ArrayList<>();
            try {
                for (Out o : plan.columns()) {
                    FieldVector v = batch.getVector(o.source());
                    if (o.cast() == null) {
                        vectors.add(move(v, o.field(), ctx.allocator()));
                    } else {
                        try {
                            Col c = ArrowIO.read(v).castTo(o.cast());
                            vectors.add(ArrowIO.write(c, o.field(), ctx.allocator()));
                        } catch (ExprException e) {
                            throw fail("cast." + o.source(), e);
                        }
                    }
                }
                return new VectorSchemaRoot(plan.schema(), vectors, batch.getRowCount());
            } catch (StepException | RuntimeException e) {
                vectors.forEach(FieldVector::close);
                throw e;
            }
        }
    }
}

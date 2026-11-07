package io.kilnmark.kiln.nodes;

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
import io.kilnmark.kiln.Node;
import io.kilnmark.kiln.NodeContext;
import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.NodeFactory;
import io.kilnmark.kiln.NodeKind;
import io.kilnmark.kiln.RowErrors;
import io.kilnmark.kiln.nodes.expr.ArrowIO;
import io.kilnmark.kiln.nodes.expr.Col;
import io.kilnmark.kiln.nodes.expr.ExprException;
import io.kilnmark.kiln.nodes.expr.Type;

/**
 * {@code select}: keeps or drops columns, renames, reorders and casts them. Every setting names
 * columns as they arrive (before renaming). Done in this order: keep or drop, cast, rename, order.
 */
public final class SelectFactory implements NodeFactory {

    public static final String TYPE = "select";

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
        return io.kilnmark.kiln.NodeCategories.TRANSFORM;
    }

    @Override
    public String label() {
        return "Select columns";
    }

    @Override
    public String help() {
        return "Keeps or drops columns, and renames, reorders and changes the type of the columns kept. "
            + "Every setting names columns as they arrive. Rows whose cast fails go to the errors port, if something "
            + "is connected to it.";
    }

    @Override
    public List<String> inputPorts() {
        return List.of("in");
    }

    @Override
    public List<String> outputPorts() {
        return List.of("out", RowErrors.PORT);
    }

    @Override
    public List<SettingSpec> settings() {
        return Failing.withTolerance(List.of(
            SettingSpec.optional("keep", "Keep", SettingSpec.Kind.COLUMNS, null,
                "Only these columns, in this order. Leave empty to keep them all."),
            SettingSpec.optional("drop", "Drop", SettingSpec.Kind.COLUMNS, null,
                "Columns to leave out. Use either keep or drop, not both."),
            SettingSpec.optional("rename", "Rename", SettingSpec.Kind.COLUMN_MAP, null,
                "New names: each column as it arrives, and its new name."),
            SettingSpec.optional("order", "Order", SettingSpec.Kind.COLUMNS, null,
                "Columns to put first, in this order; the rest follow as they were."),
            // EXPRESSIONS is the nearest kind: a map of names to free text (here type names). A kind of its own
            // for column-to-type maps would let the canvas offer the types; that needs a kiln-core addition.
            SettingSpec.optional("cast", "Change types", SettingSpec.Kind.EXPRESSIONS, null,
                "Columns to convert, and the type for each: boolean, int64, float64, decimal(p,s), utf8, date or "
                    + "timestamp.")));
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
        return Failing.withTolerance(c.problems(), config);
    }

    @Override
    public Node create(Map<String, Object> config) throws NodeException {
        Config c = new Config(config);
        Settings s = read(c);
        c.problems().addAll(RowErrors.check(config));
        c.orThrow(TYPE);
        return new SelectNode(s, RowErrors.Tally.of(config));
    }

    @Override
    public Schema outputSchema(String port, Map<String, Schema> inputs, Map<String, Object> config)
        throws NodeException {
        if (!port.equals("out") && !port.equals(RowErrors.PORT)) {
            throw new NodeException("A select node has no output port '" + port + "'");
        }
        Schema in = inputs.get("in");
        if (in == null) {
            return null;
        }
        Config c = new Config(config);
        Settings s = read(c);
        c.orThrow(TYPE);
        Schema out = plan(in, s).schema();
        return port.equals(RowErrors.PORT) ? RowErrors.schema(in) : out;
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
    static Plan plan(Schema in, Settings s) throws NodeException {
        String prefix = "";
        List<String> names = BaseNode.names(in);
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
                throw new NodeException(prefix + "cast: the column '" + k + "' is not kept");
            }
            Field f = BaseNode.field(in, k);
            Type from = Type.of(f.getType());
            Type to = s.cast().get(k);
            if (from == null) {
                throw new NodeException(prefix + "cast: the column '" + k + "' holds " + f.getType()
                    + " values, which cannot be converted");
            }
            if (!Type.canCast(from, to)) {
                throw new NodeException(prefix + "cast: the column '" + k + "' is " + from.family()
                    + ", which cannot be turned into " + to.family());
            }
        }
        for (String k : s.rename().keySet()) {
            require(in, k, "rename", prefix);
            if (!chosen.contains(k)) {
                throw new NodeException(prefix + "rename: the column '" + k + "' is not kept");
            }
        }
        if (s.order() != null) {
            List<String> first = new ArrayList<>();
            for (String o : s.order()) {
                require(in, o, "order", prefix);
                if (!chosen.contains(o)) {
                    throw new NodeException(prefix + "order: the column '" + o + "' is not kept");
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
            Field f = BaseNode.field(in, name);
            String newName = s.rename().getOrDefault(name, name);
            if (!outNames.add(newName)) {
                throw new NodeException(prefix + "rename: two columns would be called '" + newName + "'");
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

    private static void require(Schema in, String column, String key, String prefix) throws NodeException {
        if (BaseNode.field(in, column) == null) {
            String near = io.kilnmark.kiln.nodes.expr.Names.suggest(column, BaseNode.names(in));
            throw new NodeException(prefix + key + ": unknown column '" + column + "'"
                + (near != null ? "; did you mean " + near + "?" : ""));
        }
    }

    /** The running node. */
    static final class SelectNode extends BaseNode {
        private final Settings settings;
        private final RowErrors.Tally tally;
        private Schema boundTo;
        private Plan plan;

        SelectNode(Settings settings, RowErrors.Tally tally) {
            super(TYPE);
            this.settings = settings;
            this.tally = tally;
        }

        @Override
        public boolean process(NodeContext ctx) throws NodeException {
            VectorSchemaRoot batch = take(ctx, "in");
            if (batch == null) {
                Failing.finish(tally, ctx);
                return false;
            }
            tally.arrived(batch.getRowCount());
            Failing.Done<VectorSchemaRoot> done;
            try {
                if (!batch.getSchema().equals(boundTo)) {
                    plan = plan(batch.getSchema(), settings);
                    boundTo = batch.getSchema();
                }
                done = Failing.work(batch, rows -> select(rows, ctx), tally, ctx, name);
            } finally {
                batch.close();
            }
            if (done != null) {
                if (done.fresh()) {
                    done.rows().close();
                }
                put(ctx, "out", done.result());
            }
            return true;
        }

        /**
         * The batch with its columns chosen and cast. Every cast is made before any column is moved
         * out of the input, so a row that fails leaves it whole for finding the rows that fail.
         */
        private VectorSchemaRoot select(VectorSchemaRoot batch, NodeContext ctx) throws Failing.RowFailure {
            List<FieldVector> cast = new ArrayList<>();
            try {
                for (Out o : plan.columns()) {
                    if (o.cast() != null) {
                        try {
                            Col c = ArrowIO.read(batch.getVector(o.source())).castTo(o.cast());
                            cast.add(ArrowIO.write(c, o.field(), ctx.allocator()));
                        } catch (ExprException e) {
                            throw new Failing.RowFailure("cast." + o.source(), o.source(), e);
                        }
                    }
                }
            } catch (Failing.RowFailure | RuntimeException e) {
                cast.forEach(FieldVector::close);
                throw e;
            }
            List<FieldVector> vectors = new ArrayList<>();
            int next = 0;
            for (Out o : plan.columns()) {
                vectors.add(o.cast() != null ? cast.get(next++)
                    : move(batch.getVector(o.source()), o.field(), ctx.allocator()));
            }
            return new VectorSchemaRoot(plan.schema(), vectors, batch.getRowCount());
        }
    }
}

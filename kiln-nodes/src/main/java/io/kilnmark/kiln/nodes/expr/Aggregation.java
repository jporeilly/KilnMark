package io.kilnmark.kiln.nodes.expr;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;

import io.kilnmark.kiln.nodes.expr.Type.Kind;

/**
 * Grouping and summarising, for the aggregate node: rows are grouped by the {@code group_by}
 * columns (nulls form a group of their own, as in SQL), and each output value is an expression
 * over aggregate calls ({@code count(*)}, {@code count(x)}, {@code count_distinct(x)},
 * {@code sum}, {@code min}, {@code max}, {@code avg}) and the group's columns, such as
 * {@code sum(price * quantity) / count(*)}.
 *
 * <p>Groups are kept in compact Java structures; {@link State#estimatedBytes()} says roughly how
 * much memory they take, so the node can stop at its memory limit. Distinct counts are exact.
 */
public final class Aggregation {

    private final Schema input;
    private final List<String> groupBy;
    private final List<Field> groupFields = new ArrayList<>();
    private final List<Type> groupTypes = new ArrayList<>();
    private final List<AggCall> calls = new ArrayList<>();
    private final Map<String, Ev> values = new LinkedHashMap<>();

    /** One aggregate call: its function, argument (null for count(*)), result type and hidden column name. */
    private record AggCall(String fn, Ev arg, Type type, String column) {
    }

    /**
     * @throws ExprException naming the first group-by column that is missing or cannot be grouped
     */
    public Aggregation(Schema input, List<String> groupBy) throws ExprException {
        this.input = input;
        this.groupBy = List.copyOf(groupBy);
        for (String g : groupBy) {
            Field f = find(input, g);
            if (f == null) {
                List<String> names = input.getFields().stream().map(Field::getName).toList();
                String near = Compiler.suggest(g, names);
                throw new ExprException("unknown column '" + g + "'", 0, near != null ? "did you mean " + near + "?" : null);
            }
            Type t = Type.of(f.getType());
            if (t == null) {
                throw new ExprException("the column '" + g + "' holds " + f.getType() + " values, which cannot be grouped",
                    0);
            }
            groupFields.add(f);
            groupTypes.add(t);
        }
    }

    private static Field find(Schema s, String name) {
        for (Field f : s.getFields()) {
            if (f.getName().equals(name)) {
                return f;
            }
        }
        return null;
    }

    /**
     * Adds an output value.
     *
     * @throws ExprException if the expression is not valid here: a column used outside an
     *                       aggregate that is not grouped by, an aggregate inside another, a type mismatch
     */
    public void add(String name, Expression e) throws ExprException {
        Compiler.Scope scope = Compiler.Scope.of(input);
        Set<String> grouped = new HashSet<>(groupBy);
        Compiler outer = new Compiler(scope, new Compiler.AggregateHook() {
            @Override
            public Ev aggregate(Node.Call call) throws ExprException {
                return aggregateCall(call, scope);
            }

            @Override
            public String outside(String column) {
                return grouped.contains(column) ? null
                    : "the column '" + column + "' must be in group_by, or inside an aggregate such as max(" + column + ")";
            }
        });
        values.put(name, outer.compile(e.root()));
    }

    private Ev aggregateCall(Node.Call call, Compiler.Scope scope) throws ExprException {
        String fn = call.name();
        Compiler inner = new Compiler(scope, new Compiler.AggregateHook() {
            @Override
            public Ev aggregate(Node.Call nested) throws ExprException {
                throw new ExprException("an aggregate cannot be inside another aggregate", nested.pos());
            }
        });
        Ev arg = null;
        if (call.star()) {
            if (!fn.equals("count")) {
                throw new ExprException("only count(*) takes a star", call.pos(), "write " + fn + "(column)");
            }
        } else {
            if (call.args().size() != 1) {
                throw new ExprException(fn + " takes one argument, not " + call.args().size(), call.pos(),
                    fn.equals("count") ? "use count(*) to count rows" : null);
            }
            arg = inner.compile(call.args().getFirst());
        }
        Type t = arg == null ? Type.INT64 : arg.type;
        Type result = switch (fn) {
            case "count", "count_distinct" -> Type.INT64;
            case "sum", "avg" -> {
                if (!t.isNumeric() && !t.isNull()) {
                    throw new ExprException(fn + " needs numbers, not " + Compiler.article(t), call.pos());
                }
                if (fn.equals("sum")) {
                    yield t.isNull() ? Type.INT64 : t.kind() == Kind.DECIMAL ? Type.decimal(t.scale()) : t;
                }
                yield t.kind() == Kind.DECIMAL ? Type.decimal(Math.max(6, t.scale())) : Type.FLOAT64;
            }
            default -> t; // min, max
        };
        String column = "\u0000agg" + calls.size();
        calls.add(new AggCall(fn, arg, result, column));
        return new Ev.Column(column, result, call.pos());
    }

    /** The columns of the result: the group-by columns as they arrive, then each value. */
    public Schema outputSchema() {
        List<Field> fields = new ArrayList<>(groupFields);
        for (Map.Entry<String, Ev> v : values.entrySet()) {
            fields.add(v.getValue().type.field(v.getKey()));
        }
        return new Schema(fields);
    }

    /** A fresh set of groups for one run. */
    public State newState(long nowMicros) {
        return new State(nowMicros);
    }

    /** The groups seen so far in one run. Not safe for use by two threads at once. */
    public final class State {

        private final long now;
        private final Map<Object, Integer> index = new HashMap<>();
        private final List<Object[]> keys = new ArrayList<>();
        private final List<Acc[]> accs = new ArrayList<>();
        private long bytes;

        private State(long now) {
            this.now = now;
        }

        public int groups() {
            return keys.size();
        }

        /** A rough count of the memory the groups take, in bytes. */
        public long estimatedBytes() {
            return bytes;
        }

        /** Adds a batch's rows to their groups. The batch stays the caller's. */
        public void add(VectorSchemaRoot batch) throws ExprException {
            int n = batch.getRowCount();
            if (n == 0) {
                return;
            }
            Batch b = Batch.of(batch, now);
            Col[] keyCols = new Col[groupBy.size()];
            for (int k = 0; k < keyCols.length; k++) {
                keyCols[k] = new Ev.Column(groupBy.get(k), groupTypes.get(k), 0).eval(b, null);
            }
            Col[] args = new Col[calls.size()];
            for (int a = 0; a < args.length; a++) {
                AggCall call = calls.get(a);
                args[a] = call.arg == null ? null : Expression.safely(call.arg, b, null);
            }
            for (int i = 0; i < n; i++) {
                Acc[] row = group(keyCols, i);
                for (int a = 0; a < row.length; a++) {
                    bytes += row[a].add(args[a], i);
                }
            }
        }

        private Acc[] group(Col[] keyCols, int i) {
            Object key;
            Object[] parts = new Object[keyCols.length];
            for (int k = 0; k < keyCols.length; k++) {
                parts[k] = canonical(keyCols[k].get(i));
            }
            key = parts.length == 1 ? parts[0] : Arrays.asList(parts);
            Integer at = index.get(key);
            if (at != null) {
                return accs.get(at);
            }
            Acc[] row = new Acc[calls.size()];
            for (int a = 0; a < row.length; a++) {
                row[a] = newAcc(calls.get(a));
            }
            index.put(key, keys.size());
            keys.add(parts);
            accs.add(row);
            bytes += 120 + 48L * row.length;
            for (Object p : parts) {
                bytes += sizeOf(p);
            }
            return row;
        }

        /**
         * Builds output rows {@code from} to {@code to} of the groups, in the order they were first
         * seen, from {@code allocator}. With no group-by columns and no rows at all there is still
         * one group, as in SQL: {@code count(*)} gives 0 and {@code sum} null.
         */
        public VectorSchemaRoot emit(int from, int to, BufferAllocator allocator) throws ExprException {
            if (groupBy.isEmpty() && keys.isEmpty()) {
                group(new Col[0], 0);
            }
            int m = to - from;
            Map<String, Col> cols = new HashMap<>();
            Col[] keyCols = new Col[groupBy.size()];
            for (int k = 0; k < keyCols.length; k++) {
                keyCols[k] = Col.blank(groupTypes.get(k), m);
                for (int i = 0; i < m; i++) {
                    keyCols[k].set(i, keys.get(from + i)[k]);
                }
                cols.put(groupBy.get(k), keyCols[k]);
            }
            for (int a = 0; a < calls.size(); a++) {
                AggCall call = calls.get(a);
                Col c = Col.blank(call.type, m);
                for (int i = 0; i < m; i++) {
                    c.set(i, accs.get(from + i)[a].result());
                }
                cols.put(call.column, c);
            }
            Batch b = Batch.of(m, cols, now);
            List<Field> fields = new ArrayList<>();
            List<FieldVector> vectors = new ArrayList<>();
            try {
                for (int k = 0; k < keyCols.length; k++) {
                    fields.add(groupFields.get(k));
                    vectors.add(ArrowIO.write(keyCols[k], groupFields.get(k), allocator));
                }
                for (Map.Entry<String, Ev> v : values.entrySet()) {
                    Field f = v.getValue().type.field(v.getKey());
                    fields.add(f);
                    vectors.add(ArrowIO.write(Expression.safely(v.getValue(), b, null), f, allocator));
                }
                return new VectorSchemaRoot(fields, vectors, m);
            } catch (ExprException | RuntimeException e) {
                vectors.forEach(FieldVector::close);
                throw e;
            }
        }

        /** How many output rows there are: the groups, or one when there are no group-by columns. */
        public int outputRows() {
            return groupBy.isEmpty() ? Math.max(1, keys.size()) : keys.size();
        }
    }

    private static Object canonical(Object v) {
        if (v instanceof Double d) {
            return d == 0.0 ? 0.0 : d; // -0.0 and 0.0 are one group
        }
        if (v instanceof BigDecimal d) {
            return d.signum() == 0 ? BigDecimal.ZERO : d.stripTrailingZeros();
        }
        return v;
    }

    static long sizeOf(Object v) {
        return switch (v) {
            case null -> 8;
            case String s -> 48 + 2L * s.length();
            case BigDecimal d -> 64 + d.precision() / 2;
            default -> 24;
        };
    }

    private static Acc newAcc(AggCall call) {
        return switch (call.fn) {
            case "count" -> new Count(call.arg == null);
            case "count_distinct" -> new Distinct();
            case "sum" -> new Sum(call.type);
            case "avg" -> new Avg(call.arg.type, call.type);
            default -> new MinMax(call.fn.equals("min"), call.type);
        };
    }

    /** One aggregate's running value for one group. */
    private abstract static class Acc {
        /** Takes row {@code i} of {@code c}; returns the bytes this added to memory, roughly. */
        abstract long add(Col c, int i) throws ExprException;

        abstract Object result();
    }

    private static final class Count extends Acc {
        private final boolean rows;
        private long n;

        Count(boolean rows) {
            this.rows = rows;
        }

        @Override
        long add(Col c, int i) {
            if (rows || !c.isNull(i)) {
                n++;
            }
            return 0;
        }

        @Override
        Object result() {
            return n;
        }
    }

    private static final class Distinct extends Acc {
        private final Set<Object> seen = new HashSet<>();

        @Override
        long add(Col c, int i) {
            Object v = c.get(i);
            if (v != null && seen.add(canonical(v))) {
                return 40 + sizeOf(v);
            }
            return 0;
        }

        @Override
        Object result() {
            return (long) seen.size();
        }
    }

    private static final class Sum extends Acc {
        private final Type type;
        private long longs;
        private double doubles;
        private BigDecimal decimals = BigDecimal.ZERO;
        private boolean any;

        Sum(Type type) {
            this.type = type;
        }

        @Override
        long add(Col c, int i) throws ExprException {
            if (c.isNull(i)) {
                return 0;
            }
            any = true;
            switch (type.kind()) {
                case INT64 -> {
                    try {
                        longs = Math.addExact(longs, c.longs[i]);
                    } catch (ArithmeticException e) {
                        throw new ExprException("sum: the total is too large for int64", 0,
                            "sum a decimal instead, like sum(cast(x as decimal(38,0)))");
                    }
                }
                case FLOAT64 -> doubles += c.doubles[i];
                default -> decimals = decimals.add((BigDecimal) c.objects[i]);
            }
            return 0;
        }

        @Override
        Object result() {
            if (!any) {
                return null;
            }
            return switch (type.kind()) {
                case INT64 -> longs;
                case FLOAT64 -> doubles;
                default -> decimals;
            };
        }
    }

    private static final class Avg extends Acc {
        private final Type in;
        private final Type out;
        private double doubles;
        private BigDecimal decimals = BigDecimal.ZERO;
        private long n;

        Avg(Type in, Type out) {
            this.in = in;
            this.out = out;
        }

        @Override
        long add(Col c, int i) {
            if (c.isNull(i)) {
                return 0;
            }
            n++;
            switch (in.kind()) {
                case INT64 -> doubles += c.longs[i];
                case FLOAT64 -> doubles += c.doubles[i];
                case DECIMAL -> decimals = decimals.add((BigDecimal) c.objects[i]);
                default -> n--;
            }
            return 0;
        }

        @Override
        Object result() {
            if (n == 0) {
                return null;
            }
            if (out.kind() == Kind.DECIMAL) {
                return decimals.divide(BigDecimal.valueOf(n), out.scale(), RoundingMode.HALF_UP);
            }
            return doubles / n;
        }
    }

    private static final class MinMax extends Acc {
        private final boolean min;
        private final Col best;
        private boolean any;

        MinMax(boolean min, Type type) {
            this.min = min;
            this.best = Col.nulls(type, 1);
        }

        @Override
        long add(Col c, int i) {
            if (c.isNull(i) || best.type.isNull()) {
                return 0;
            }
            if (!any) {
                best.copy(c, i, 0);
                any = true;
                return c.type.kind() == Kind.UTF8 ? sizeOf(c.get(i)) : 0;
            }
            int cmp = Ev.compare(c, i, best, 0);
            if (min ? cmp < 0 : cmp > 0) {
                long delta = c.type.kind() == Kind.UTF8 ? sizeOf(c.get(i)) - sizeOf(best.get(0)) : 0;
                best.copy(c, i, 0);
                return delta;
            }
            return 0;
        }

        @Override
        Object result() {
            return best.get(0);
        }
    }
}

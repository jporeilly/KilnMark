package io.kilnmark.kiln.nodes.expr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.arrow.vector.util.TransferPair;

/**
 * The window functions, for the window node: each new column is one call, worked out over the rows
 * of its partition (the rows with equal {@code partition_by} values) in their order:
 *
 * <ul>
 *   <li>{@code row_number()}, {@code rank()}, {@code dense_rank()}: the row's place, ties (rows with
 *       equal order values) sharing a rank, with gaps after them for {@code rank} and none for
 *       {@code dense_rank};</li>
 *   <li>{@code count(*)}, {@code count(x)}, {@code sum(x)}, {@code avg(x)}, {@code min(x)},
 *       {@code max(x)}: running totals over the partition's rows so far, this one included (SQL's
 *       {@code rows between unbounded preceding and current row}), with the types the aggregate node
 *       gives;</li>
 *   <li>{@code lag(x [, offset [, default]])} and {@code lead(...)}: {@code x} from the row
 *       {@code offset} rows before or after (1 by default) in the same partition, or {@code default}
 *       (null unless given) when there is none.</li>
 * </ul>
 *
 * <p>Arguments are expressions over the row's columns. The rows arrive already in order, a batch at
 * a time ({@link State#push}); each batch is passed on with its new columns once the rows a
 * {@code lead} looks ahead to have arrived ({@link State#next}), and rows a {@code lag} looks back to
 * are kept, as their arguments' values only, until no later row needs them.
 */
public final class Windowing {

    /** The furthest a lag or lead may look, in rows. */
    public static final int MAX_OFFSET = 1_000;
    /** Functions only a window node has: what the expression language refuses elsewhere. */
    static final Set<String> OWN = Set.of("row_number", "rank", "dense_rank", "lag", "lead");
    private static final Set<String> RUNNING = Set.of("count", "sum", "avg", "min", "max");
    /** Every function a window node's columns may call. */
    static final Set<String> FUNCTIONS;

    static {
        Set<String> all = new LinkedHashSet<>(OWN);
        all.addAll(RUNNING);
        FUNCTIONS = Set.copyOf(all);
    }

    private final Schema input;
    private final Compiler.Scope scope;
    private final Map<String, Fn> fns = new LinkedHashMap<>();

    /** One new column: its function, argument (or null), default (or null), offset and result type. */
    private record Fn(String name, String fn, Ev arg, Ev fallback, int offset, Type type) {
    }

    public Windowing(Schema input) {
        this.input = input;
        this.scope = Compiler.Scope.of(input);
    }

    /**
     * Adds a new column.
     *
     * @throws ExprException if the expression is not one window function call with valid arguments
     */
    public void add(String name, Expression e) throws ExprException {
        Node root = e.root();
        if (!(root instanceof Node.Call call) || !FUNCTIONS.contains(call.name())) {
            String what = root instanceof Node.Call c ? c.name() + "(...)" : "an expression";
            throw new ExprException("a window column is one window function: row_number, rank, dense_rank, "
                + "count, sum, avg, min, max, lag or lead, not " + what, root.pos(),
                "work out more from it in a compute node after the window node");
        }
        String fn = call.name();
        int pos = call.pos();
        if (call.star() && !fn.equals("count")) {
            throw new ExprException("only count(*) takes a star", pos, "write " + fn + "(column)");
        }
        for (Node a : call.args()) {
            Node.Call inner = nestedCall(a);
            if (inner != null) {
                throw new ExprException("a window function's arguments cannot hold another window or aggregate "
                    + "function (" + inner.name() + ")", inner.pos(), "work it out in a window node before this one");
            }
        }
        Compiler compiler = new Compiler(scope, null);
        List<Node> args = call.args();
        switch (fn) {
            case "row_number", "rank", "dense_rank" -> {
                arity(call, 0, 0);
                fns.put(name, new Fn(name, fn, null, null, 0, Type.INT64));
            }
            case "lag", "lead" -> {
                arity(call, 1, 3);
                Ev x = compiler.compile(args.get(0));
                int offset = 1;
                if (args.size() > 1) {
                    if (!(args.get(1) instanceof Node.Literal l) || !(l.value() instanceof Long n) || n < 0
                        || n > MAX_OFFSET) {
                        throw new ExprException(fn + "'s offset must be a whole number from 0 to "
                            + String.format(java.util.Locale.UK, "%,d", MAX_OFFSET) + ", written out",
                            args.get(1).pos());
                    }
                    offset = (int) (long) n;
                }
                Ev fallback = args.size() > 2 ? compiler.compile(args.get(2)) : null;
                Type t = x.type;
                if (fallback != null) {
                    t = Type.common(x.type, fallback.type);
                    if (t == null) {
                        throw new ExprException(fn + "'s default must have the type of its value, "
                            + Compiler.article(x.type) + ", not " + Compiler.article(fallback.type), args.get(2).pos(),
                            "cast one of them first");
                    }
                }
                if (t.isNull()) {
                    t = Type.UTF8;
                }
                fns.put(name, new Fn(name, fn, Ev.widen(x, t), fallback == null ? null : Ev.widen(fallback, t), offset,
                    t));
            }
            default -> { // the running totals
                Ev arg = null;
                if (!call.star()) {
                    if (args.size() != 1) {
                        throw new ExprException(fn + " takes one argument, not " + args.size(), pos,
                            fn.equals("count") ? "use count(*) to count rows" : null);
                    }
                    arg = compiler.compile(args.getFirst());
                }
                Type result = Aggregation.resultType(fn, arg == null ? Type.INT64 : arg.type, pos);
                if (arg != null && arg.type.isNull()) {
                    arg = Ev.widen(arg, result);
                }
                fns.put(name, new Fn(name, fn, arg, null, 0, result));
            }
        }
    }

    /** The first window or aggregate call inside {@code n}, or null. */
    private static Node.Call nestedCall(Node n) {
        return switch (n) {
            case Node.Call c -> FUNCTIONS.contains(c.name()) || Compiler.AGGREGATES.contains(c.name()) ? c
                : first(c.args());
            case Node.Unary u -> nestedCall(u.operand());
            case Node.Binary b -> first(List.of(b.left(), b.right()));
            case Node.IsNull i -> nestedCall(i.operand());
            case Node.In i -> {
                Node.Call c = nestedCall(i.operand());
                yield c != null ? c : first(i.items());
            }
            case Node.Between b -> first(List.of(b.operand(), b.low(), b.high()));
            case Node.Like l -> first(List.of(l.operand(), l.pattern()));
            case Node.Case c -> {
                List<Node> parts = new ArrayList<>();
                if (c.operand() != null) {
                    parts.add(c.operand());
                }
                parts.addAll(c.whens());
                parts.addAll(c.thens());
                if (c.otherwise() != null) {
                    parts.add(c.otherwise());
                }
                yield first(parts);
            }
            case Node.Cast c -> nestedCall(c.operand());
            case Node.Literal _, Node.Column _ -> null;
        };
    }

    private static Node.Call first(List<Node> nodes) {
        for (Node n : nodes) {
            Node.Call c = nestedCall(n);
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    private static void arity(Node.Call c, int min, int max) throws ExprException {
        int n = c.args().size();
        if (n < min || n > max) {
            String want = min == max ? (min == 0 ? "no arguments" : min + " argument" + (min == 1 ? "" : "s"))
                : min + " to " + max + " arguments";
            throw new ExprException(c.name() + " takes " + want + ", not " + n, c.pos());
        }
    }

    /** The columns of the result: the input's, then each new one. */
    public Schema outputSchema() {
        List<Field> fields = new ArrayList<>(input.getFields());
        fields.addAll(newFields());
        return new Schema(fields);
    }

    /** The new columns, in the order they were added. */
    public List<Field> newFields() {
        return fns.values().stream().map(f -> f.type().field(f.name())).toList();
    }

    /** The furthest any lag looks back, in rows. */
    public int lookBehind() {
        return fns.values().stream().filter(f -> f.fn().equals("lag")).mapToInt(Fn::offset).max().orElse(0);
    }

    /** The furthest any lead looks ahead, in rows. */
    public int lookAhead() {
        return fns.values().stream().filter(f -> f.fn().equals("lead")).mapToInt(Fn::offset).max().orElse(0);
    }

    /** A fresh state for one run. */
    public State newState(long nowMicros) {
        return new State(nowMicros);
    }

    /** Rows in order, a batch at a time: one run. Not safe for use by two threads at once. */
    public final class State implements AutoCloseable {

        private final long now;
        private final Fn[] list = fns.values().toArray(new Fn[0]);
        private final int behind = lookBehind();
        private final int ahead = lookAhead();
        /** Batches still held: those passed on (kept for lags, their values only), then those waiting. */
        private final List<Entry> entries = new ArrayList<>();
        /** Index in {@link #entries} of the first batch not yet passed on. */
        private int waiting;
        private long rowsIn;
        private long partition;
        private long rowNumber;
        private long rank;
        private long dense;
        private final Aggregation.Acc[] accs = new Aggregation.Acc[list.length];
        private long bytes;

        private State(long now) {
            this.now = now;
        }

        /** One batch: its rows (null once passed on), where it starts, and what was worked out at arrival. */
        private static final class Entry {
            VectorSchemaRoot root;
            final long start;
            final int rows;
            final long[] part;
            final boolean[] partStart;
            final boolean[] peerStart;
            final Col[] args;
            final Col[] fallbacks;
            long bytes;

            Entry(VectorSchemaRoot root, long start, int rows, int fns) {
                this.root = root;
                this.start = start;
                this.rows = rows;
                this.part = new long[rows];
                this.partStart = new boolean[rows];
                this.peerStart = new boolean[rows];
                this.args = new Col[fns];
                this.fallbacks = new Col[fns];
            }

            long end() {
                return start + rows;
            }
        }

        /**
         * Takes the next batch of rows in order; the state owns it from now on, whatever happens.
         * {@code partStart[i]} says row {@code i} starts a partition (the first row ever must), and
         * {@code peerStart[i]} that its order values differ from the row before's.
         */
        public void push(VectorSchemaRoot rows, boolean[] partStart, boolean[] peerStart) throws ExprException {
            int n = rows.getRowCount();
            if (n == 0) {
                rows.close();
                return;
            }
            Entry e = new Entry(rows, rowsIn, n, list.length);
            try {
                Batch b = Batch.of(rows, now);
                for (int f = 0; f < list.length; f++) {
                    Fn fn = list[f];
                    if (fn.arg() != null) {
                        e.args[f] = Expression.safely(fn.arg(), b, null);
                    }
                    if (fn.fallback() != null) {
                        e.fallbacks[f] = Expression.safely(fn.fallback(), b, null);
                    }
                }
                for (int i = 0; i < n; i++) {
                    boolean starts = partStart[i] || rowsIn + i == 0;
                    if (starts) {
                        partition++;
                    }
                    e.part[i] = partition;
                    e.partStart[i] = starts;
                    e.peerStart[i] = starts || peerStart[i];
                }
            } catch (ExprException | RuntimeException ex) {
                rows.close();
                throw ex;
            }
            e.bytes = estimate(e);
            bytes += e.bytes;
            entries.add(e);
            rowsIn += n;
        }

        /** Roughly the memory the batches held take, in bytes: their Arrow buffers and the values kept. */
        public long heldBytes() {
            return bytes;
        }

        /** Whether any batch is still to be passed on. */
        public boolean waiting() {
            return waiting < entries.size();
        }

        /**
         * The next batch with its new columns, from {@code allocator}, once the rows its leads look
         * ahead to have arrived (or, with {@code ended}, the input has ended); null when none is ready.
         * The caller owns it.
         */
        public VectorSchemaRoot next(BufferAllocator allocator, boolean ended) throws ExprException {
            if (waiting >= entries.size()) {
                return null;
            }
            Entry e = entries.get(waiting);
            if (!ended && rowsIn - e.end() < ahead) {
                return null;
            }
            VectorSchemaRoot out = build(e, allocator);
            // from now on only what a later row's lag reads is needed
            for (int f = 0; f < list.length; f++) {
                if (!list[f].fn().equals("lag")) {
                    e.args[f] = null;
                }
                e.fallbacks[f] = null;
            }
            bytes -= e.bytes;
            e.bytes = estimateKept(e);
            bytes += e.bytes;
            waiting++;
            // let go of batches no lag of a later row reaches
            long needed = e.end() - behind;
            while (waiting > 0 && entries.getFirst().end() <= needed) {
                bytes -= entries.removeFirst().bytes;
                waiting--;
            }
            return out;
        }

        private VectorSchemaRoot build(Entry e, BufferAllocator allocator) throws ExprException {
            int n = e.rows;
            Col[] out = new Col[list.length];
            for (int f = 0; f < list.length; f++) {
                out[f] = Col.blank(list[f].type(), n);
            }
            for (int i = 0; i < n; i++) {
                if (e.partStart[i]) {
                    rowNumber = 0;
                    rank = 0;
                    dense = 0;
                    for (int f = 0; f < list.length; f++) {
                        Fn fn = list[f];
                        accs[f] = RUNNING.contains(fn.fn()) ? Aggregation.newAcc(fn.fn(),
                            fn.arg() == null ? null : fn.arg().type, fn.type()) : null;
                    }
                }
                rowNumber++;
                if (e.peerStart[i]) {
                    rank = rowNumber;
                    dense++;
                }
                for (int f = 0; f < list.length; f++) {
                    Fn fn = list[f];
                    switch (fn.fn()) {
                        case "row_number" -> out[f].set(i, rowNumber);
                        case "rank" -> out[f].set(i, rank);
                        case "dense_rank" -> out[f].set(i, dense);
                        case "lag", "lead" -> offset(e, i, f, fn, out[f]);
                        default -> {
                            accs[f].add(e.args[f], i);
                            out[f].set(i, accs[f].result());
                        }
                    }
                }
            }
            VectorSchemaRoot rows = e.root;
            List<Field> fields = new ArrayList<>(rows.getSchema().getFields());
            List<FieldVector> vectors = new ArrayList<>();
            try {
                for (FieldVector v : rows.getFieldVectors()) {
                    TransferPair tp = v.getTransferPair(allocator);
                    tp.transfer();
                    vectors.add((FieldVector) tp.getTo());
                }
                for (int f = 0; f < list.length; f++) {
                    Field field = list[f].type().field(list[f].name());
                    fields.add(field);
                    vectors.add(ArrowIO.write(out[f], field, allocator));
                }
                return new VectorSchemaRoot(fields, vectors, n);
            } catch (ExprException | RuntimeException ex) {
                vectors.forEach(FieldVector::close);
                throw ex;
            } finally {
                rows.close();
                e.root = null;
            }
        }

        /** A lag's or lead's value for row {@code i} of {@code e}: from the row it looks at, or the default. */
        private void offset(Entry e, int i, int f, Fn fn, Col into) {
            long target = fn.fn().equals("lag") ? e.start + i - fn.offset() : e.start + i + fn.offset();
            Entry at = find(target);
            if (at != null) {
                int j = (int) (target - at.start);
                if (at.part[j] == e.part[i]) {
                    into.copy(at.args[f], j, i);
                    return;
                }
            }
            if (e.fallbacks[f] != null) {
                into.copy(e.fallbacks[f], i, i);
            } else {
                into.nulls[i] = true;
            }
        }

        /** The batch held that holds row {@code row}, or null. */
        private Entry find(long row) {
            int lo = 0;
            int hi = entries.size() - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                Entry m = entries.get(mid);
                if (row < m.start) {
                    hi = mid - 1;
                } else if (row >= m.end()) {
                    lo = mid + 1;
                } else {
                    return m;
                }
            }
            return null;
        }

        private static long estimate(Entry e) {
            long b = 0;
            for (FieldVector v : e.root.getFieldVectors()) {
                b += v.getBufferSize();
            }
            return b + estimateKept(e);
        }

        /** The values kept once a batch is passed on: its arguments, partitions and flags. */
        private static long estimateKept(Entry e) {
            long b = 64 + 10L * e.rows;
            for (Col[] cols : List.of(e.args, e.fallbacks)) {
                for (Col c : cols) {
                    if (c == null) {
                        continue;
                    }
                    b += 9L * c.rows;
                    if (c.objects != null) {
                        for (Object o : c.objects) {
                            b += o == null ? 0 : Aggregation.sizeOf(o);
                        }
                    }
                }
            }
            return b;
        }

        /** Frees every batch still held. */
        @Override
        public void close() {
            for (Entry e : entries) {
                if (e.root != null) {
                    e.root.close();
                    e.root = null;
                }
            }
            entries.clear();
            waiting = 0;
            bytes = 0;
        }
    }
}

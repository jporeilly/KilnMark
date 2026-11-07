package io.kilnmark.kiln.nodes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;

import io.kilnmark.kiln.Node;
import io.kilnmark.kiln.NodeCategories;
import io.kilnmark.kiln.NodeContext;
import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.NodeFactory;
import io.kilnmark.kiln.NodeKind;
import io.kilnmark.kiln.SettingSpec;
import io.kilnmark.kiln.nodes.KeyCodec.KeyBlock;
import io.kilnmark.kiln.nodes.expr.ExprException;
import io.kilnmark.kiln.nodes.expr.Expression;
import io.kilnmark.kiln.nodes.expr.Windowing;

/**
 * {@code window}: adds columns worked out over the rows of each partition (the rows with equal
 * {@code partition_by} values) in {@code order_by} order: row numbers, ranks, running totals and the
 * previous or next row's values ({@link Windowing}). Every input row comes out, with the new columns
 * after its own, in order of partition and then of {@code order_by}; rows with equal values keep the
 * order they arrived in, as a sort's do.
 *
 * <p>Memory: the rows are sorted by partition and order as a sort node sorts them
 * ({@link ExternalSorter}): up to {@code memory_limit_mb} in memory, past it on disk, encrypted, in a
 * private temporary folder deleted when the node ends, fails or is stopped. Nothing is passed on
 * until the input has ended. While the rows are passed on, those a {@code lead} looks ahead to and the
 * values a {@code lag} looks back to are held too, counted against the same limit.
 */
public final class WindowFactory implements NodeFactory {

    public static final String TYPE = "window";

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
        return NodeCategories.TRANSFORM;
    }

    @Override
    public String label() {
        return "Window functions";
    }

    @Override
    public String help() {
        return "Adds row numbers, ranks, running totals and the previous or next row's values, worked out over the "
            + "rows of each group in order. Every row comes out, with the new columns. Past its memory limit it "
            + "orders rows on disk, so any number of rows can be used.";
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
            SettingSpec.optional("partition_by", "Partition by", SettingSpec.Kind.COLUMNS, null,
                "Rows with equal values in these columns form a group, worked out on its own: numbers and totals "
                    + "start again in each. Empty values form a group of their own. Leave empty for one group."),
            SettingSpec.required("order_by", "Order by", SettingSpec.Kind.ORDER,
                "The order rows are worked out in within each group, the first column deciding most. Add desc for "
                    + "largest first, and nulls first to put empty values first (they go last by default)."),
            SettingSpec.required("columns", "Columns", SettingSpec.Kind.EXPRESSIONS,
                "Each new column's name and its window function, for example balance: sum(amount) or "
                    + "previous: lag(amount, 1, 0)."),
            SettingSpec.optional("memory_limit_mb", "Memory limit (MB)", SettingSpec.Kind.NUMBER, 256,
                "The most memory rows may take while they are put in order and worked out. Past it, sorted parts "
                    + "are written to disk and merged."));
    }

    record Settings(List<String> partitionBy, List<Config.OrderTerm> orderBy, LinkedHashMap<String, Expression> columns,
                    long limitMb) {
    }

    private static Settings read(Config c) {
        List<String> partitionBy = c.columns("partition_by", false);
        List<Config.OrderTerm> orderBy = c.order("order_by", true);
        LinkedHashMap<String, Expression> columns = c.expressions("columns", true);
        long mb = c.number("memory_limit_mb", 256, 1, 1_048_576);
        if (columns != null) {
            Map<String, String> seen = new LinkedHashMap<>();
            for (String name : columns.keySet()) {
                String was = seen.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
                if (was != null) {
                    c.problem("columns." + name, "'" + name + "' and '" + was + "' differ only in case, and a database "
                        + "would take them for one column; give one another name");
                }
            }
        }
        return new Settings(partitionBy == null ? List.of() : partitionBy, orderBy, columns, mb);
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
        return new WindowNode(s);
    }

    @Override
    public Schema outputSchema(String port, Map<String, Schema> inputs, Map<String, Object> config)
        throws NodeException {
        if (!outputPorts().contains(port)) {
            throw new NodeException("A window node has no output port '" + port + "'");
        }
        Config c = new Config(config);
        Settings s = read(c);
        c.orThrow(TYPE);
        Schema in = inputs.get("in");
        if (in == null) {
            return null;
        }
        return plan(in, s).windowing().outputSchema();
    }

    /** What a run needs, checked against the input's columns. */
    record Plan(Windowing windowing, KeyCodec sortKey, KeyCodec partitionKey, KeyCodec orderKey) {
    }

    static Plan plan(Schema in, Settings s) throws NodeException {
        List<Config.OrderTerm> partition = s.partitionBy().stream().map(p -> new Config.OrderTerm(p, false, false))
            .toList();
        KeyCodec partitionKey = partition.isEmpty() ? null
            : SortFactory.codec(in, partition, "", "partition_by", "grouped");
        KeyCodec orderKey = SortFactory.codec(in, s.orderBy(), "", "order_by", "put in order");
        List<KeyCodec.Part> sortParts = new ArrayList<>();
        if (partitionKey != null) {
            sortParts.addAll(partitionKey.parts());
        }
        sortParts.addAll(orderKey.parts());
        Map<String, String> inputNames = new LinkedHashMap<>();
        for (Field f : in.getFields()) {
            inputNames.putIfAbsent(f.getName().toLowerCase(Locale.ROOT), f.getName());
        }
        Windowing w = new Windowing(in);
        for (Map.Entry<String, Expression> col : s.columns().entrySet()) {
            String name = col.getKey();
            String clash = inputNames.get(name.toLowerCase(Locale.ROOT));
            if (clash != null) {
                throw new NodeException("columns." + name + ": the input already has a column called '" + clash
                    + "'; give the new column another name, or drop that column with a select node first");
            }
            try {
                w.add(name, col.getValue());
            } catch (ExprException e) {
                throw new NodeException("column " + name + ": " + e.getMessage(), e);
            }
        }
        return new Plan(w, new KeyCodec(sortParts, false), partitionKey, orderKey);
    }

    /** The running node. */
    static final class WindowNode extends BaseNode {
        private final Settings settings;
        private Plan plan;
        private ExternalSorter sorter;
        private ExternalSorter.Sorted sorted;
        private Windowing.State state;
        private boolean inputEnded;
        private boolean drained;
        private KeyBlock lastPartition;
        private KeyBlock lastOrder;
        private int lastRow = -1;

        WindowNode(Settings settings) {
            super(TYPE);
            this.settings = settings;
        }

        /** The folder its files are in, once it has spilled; for tests. */
        java.nio.file.Path spillFolder() {
            return sorter == null ? null : sorter.folder();
        }

        @Override
        public boolean process(NodeContext ctx) throws NodeException {
            try {
                if (!inputEnded) {
                    VectorSchemaRoot batch = take(ctx, "in");
                    if (batch == null) {
                        inputEnded = true;
                        if (sorter == null) {
                            return false;
                        }
                        sorted = sorter.finish(ctx.allocator(), false);
                        if (sorter.runsWritten() > 0) {
                            ctx.note("Put " + count(sorter.rows()) + " rows in order on disk for the window functions: "
                                + "they took more than " + settings.limitMb() + " MB, so " + sorter.runsWritten()
                                + " sorted parts (" + SortFactory.megabytesText(sorter.spilledBytes()) + ") were written "
                                + "to a temporary folder and merged.");
                        }
                        state = plan.windowing().newState(now);
                        return true;
                    }
                    add(batch, ctx);
                    return true;
                }
                if (!drained) {
                    ExternalSorter.Chunk c = sorted.next();
                    if (c == null) {
                        drained = true;
                    } else {
                        push(c.rows());
                    }
                }
                for (VectorSchemaRoot out = state.next(ctx.allocator(), drained); out != null;
                     out = state.next(ctx.allocator(), drained)) {
                    put(ctx, "out", out);
                }
                return !drained || state.waiting();
            } catch (ExprException e) {
                throw fail("columns", e);
            } catch (IOException e) {
                throw SortFactory.spillFailed(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NodeException("Interrupted", e);
            }
        }

        private void add(VectorSchemaRoot batch, NodeContext ctx) throws NodeException, IOException,
            InterruptedException {
            if (sorter == null) {
                try {
                    plan = plan(batch.getSchema(), settings);
                } catch (NodeException | RuntimeException e) {
                    batch.close();
                    throw e;
                }
                sorter = new ExternalSorter("rows", plan.sortKey(), ctx.allocator(), megabytes(settings.limitMb()),
                    ctx::spilled);
            }
            sorter.add(batch);
            if (sorter.held() > megabytes(settings.limitMb())) {
                sorter.spill();
            }
        }

        /** Marks where each partition and each run of equal order values starts, and hands the rows on. */
        private void push(VectorSchemaRoot rows) throws NodeException, ExprException {
            int n = rows.getRowCount();
            boolean[] partStart = new boolean[n];
            boolean[] peerStart = new boolean[n];
            KeyBlock part;
            KeyBlock order;
            try {
                part = plan.partitionKey() == null ? null : plan.partitionKey().encode(rows);
                order = plan.orderKey().encode(rows);
            } catch (RuntimeException e) {
                rows.close();
                throw e;
            }
            for (int i = 0; i < n; i++) {
                boolean first = lastRow < 0 && i == 0;
                KeyBlock prevPart = i == 0 ? lastPartition : part;
                KeyBlock prevOrder = i == 0 ? lastOrder : order;
                int prev = i == 0 ? lastRow : i - 1;
                partStart[i] = first || (part != null && !KeyBlock.equal(prevPart, prev, part, i));
                peerStart[i] = partStart[i] || !KeyBlock.equal(prevOrder, prev, order, i);
            }
            if (n > 0) {
                lastPartition = part;
                lastOrder = order;
                lastRow = n - 1;
            }
            state.push(rows, partStart, peerStart);
            if (state.heldBytes() > megabytes(settings.limitMb())) {
                throw fail("the rows held for lag and lead take more than " + settings.limitMb() + " MB (they look up "
                    + "to " + count(Math.max(plan.windowing().lookBehind(), plan.windowing().lookAhead()))
                    + " rows away); raise memory_limit_mb, or look fewer rows away");
            }
        }

        @Override
        public void close() {
            if (state != null) {
                state.close();
            }
            if (sorter != null) {
                sorter.close();
            }
        }
    }
}

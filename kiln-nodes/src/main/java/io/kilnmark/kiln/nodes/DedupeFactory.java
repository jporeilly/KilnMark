package io.kilnmark.kiln.nodes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;

import io.kilnmark.kiln.SettingSpec;
import io.kilnmark.kiln.Node;
import io.kilnmark.kiln.NodeContext;
import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.NodeFactory;
import io.kilnmark.kiln.NodeKind;
import io.kilnmark.kiln.nodes.expr.Col;
import io.kilnmark.kiln.nodes.expr.Names;

/**
 * {@code dedupe}: keeps one row for each key and sends the other rows with that key to
 * {@code rejected}. Which row is kept is {@code duplicates} ({@link WhichRow}): the first to arrive
 * by default, as before the setting existed. Nulls count as equal to each other, as in SQL's
 * {@code distinct}. The keys seen are held in memory, up to {@code memory_limit_mb}; under
 * {@code last}, {@code latest} and {@code earliest} the rows are held too, since a row's fate is
 * known only once every row has arrived, and are sent on at the end in the order they came.
 */
public final class DedupeFactory implements NodeFactory {

    public static final String TYPE = "dedupe";

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
        return io.kilnmark.kiln.NodeCategories.FILTER_AND_ROUTE;
    }

    @Override
    public String label() {
        return "Remove duplicates";
    }

    @Override
    public String help() {
        return "Keeps one row for each key: the first to arrive, unless Which row wins says otherwise. The "
            + "other rows with the same key go to the rejected port, if something is connected to it.";
    }

    @Override
    public List<String> inputPorts() {
        return List.of("in");
    }

    @Override
    public List<String> outputPorts() {
        return List.of("out", "rejected");
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
            SettingSpec.optional("key", "Key", SettingSpec.Kind.COLUMNS, null,
                "The columns that make rows duplicates of each other. Leave empty to compare whole rows."),
            WhichRow.setting("first", "Which row of a key is kept: the first or last to arrive, the latest or "
                + "earliest by a column, or error to stop the run when a key repeats"),
            WhichRow.bySetting(null),
            SettingSpec.optional("memory_limit_mb", "Memory limit (MB)", SettingSpec.Kind.NUMBER, 256,
                "The most memory the keys seen so far may take, with the rows held under last, latest and "
                    + "earliest."));
    }

    @Override
    public List<String> check(Map<String, Object> config) {
        Config c = new Config(config);
        c.columns("key", false);
        WhichRow.read(config, "first", c.problems());
        c.number("memory_limit_mb", 256, 1, 1_048_576);
        return c.problems();
    }

    @Override
    public Node create(Map<String, Object> config) throws NodeException {
        Config c = new Config(config);
        List<String> key = c.columns("key", false);
        WhichRow.Settings rule = WhichRow.read(config, "first", c.problems());
        long mb = c.number("memory_limit_mb", 256, 1, 1_048_576);
        c.orThrow(TYPE);
        return new DedupeNode(key == null || key.isEmpty() ? null : key, rule, BaseNode.megabytes(mb), mb);
    }

    @Override
    public Schema outputSchema(String port, Map<String, Schema> inputs, Map<String, Object> config)
        throws NodeException {
        if (!outputPorts().contains(port)) {
            throw new NodeException("A dedupe node has no output port '" + port + "'");
        }
        Schema in = inputs.get("in");
        if (in == null) {
            return null;
        }
        Config c = new Config(config);
        List<String> key = c.columns("key", false);
        WhichRow.Settings rule = WhichRow.read(config, "first", c.problems());
        c.orThrow(TYPE);
        checkKey(in, key, "");
        WhichRow.checkBy(in, rule, "");
        return in;
    }

    static void checkKey(Schema in, List<String> key, String prefix) throws NodeException {
        if (key == null) {
            return;
        }
        for (String k : key) {
            if (BaseNode.field(in, k) == null) {
                String near = Names.suggest(k, BaseNode.names(in));
                throw new NodeException(prefix + "key: unknown column '" + k + "'"
                    + (near != null ? "; did you mean " + near + "?" : ""));
            }
        }
    }

    /** The running node. */
    static final class DedupeNode extends BaseNode {
        private static final List<String> PORTS = List.of("out", "rejected");
        private final List<String> key;
        private final WhichRow.Settings rule;
        /** Whether rows are held until the end: their fate is known only once every row has arrived. */
        private final boolean holds;
        private final long limitBytes;
        private final long limitMb;
        private final Set<Object> seen = new HashSet<>();
        private final WhichRow.Winners winners;
        private final List<VectorSchemaRoot> held = new ArrayList<>();
        private long bytes;
        private Schema checked;
        private boolean sent;

        DedupeNode(List<String> key, WhichRow.Settings rule, long limitBytes, long limitMb) {
            super(TYPE);
            this.key = key;
            this.rule = rule;
            this.holds = rule.rule() == WhichRow.Rule.LAST || rule.rule().byColumn();
            this.winners = new WhichRow.Winners(rule);
            this.limitBytes = limitBytes;
            this.limitMb = limitMb;
        }

        @Override
        public boolean process(NodeContext ctx) throws NodeException {
            VectorSchemaRoot batch = take(ctx, "in");
            if (batch == null) {
                if (holds && !sent) {
                    sent = true;
                    sendHeld(ctx);
                }
                return false;
            }
            int[] target;
            try {
                if (!batch.getSchema().equals(checked)) {
                    checkKey(batch.getSchema(), key, "");
                    WhichRow.checkBy(batch.getSchema(), rule, "");
                    checked = batch.getSchema();
                }
                List<String> columns = key != null ? key : names(batch.getSchema());
                Keys.Reader[] readers = Keys.readers(batch, columns, null);
                int n = batch.getRowCount();
                if (holds) {
                    hold(batch, readers, n, ctx);
                    return true;
                }
                target = new int[n];
                for (int i = 0; i < n; i++) {
                    Object k = Keys.of(readers, i, false);
                    if (seen.add(k)) {
                        bytes += 40 + Keys.size(k);
                    } else if (rule.rule() == WhichRow.Rule.ERROR) {
                        throw fail(WhichRow.repeated("the input has", key));
                    } else {
                        target[i] = 1;
                    }
                }
                if (bytes > limitBytes) {
                    throw fail("the keys seen so far take more than " + limitMb + " MB (" + count(seen.size())
                        + " keys); raise memory_limit_mb, or use fewer or shorter key columns");
                }
            } catch (NodeException | RuntimeException e) {
                if (!held.contains(batch)) {
                    batch.close();
                }
                throw e;
            }
            distribute(ctx, batch, target, PORTS);
            return true;
        }

        /** Keeps the batch (which the node then owns) and offers each of its rows as its key's winner. */
        private void hold(VectorSchemaRoot batch, Keys.Reader[] readers, int n, NodeContext ctx) throws NodeException {
            if (n == 0) {
                batch.close();
                return;
            }
            int at = held.size();
            held.add(batch);
            Col by = rule.by() == null ? null : WhichRow.values(batch, rule.by());
            for (int i = 0; i < n; i++) {
                Object k = Keys.of(readers, i, false);
                winners.offer(k, ((long) at << 32) | i, by == null ? null : by.get(i), "the input has", key);
            }
            long taken = ctx.allocator().getAllocatedMemory() + winners.bytes();
            if (taken > limitBytes) {
                throw fail("the rows held to choose which row wins, and their keys, take more than " + limitMb
                    + " MB (" + count(winners.size()) + " keys); raise memory_limit_mb, or set " + WhichRow.KEY
                    + ": first, which holds only the keys");
            }
        }

        /** Sends every held row on, in the order it came: the winners to out, the others to rejected. */
        private void sendHeld(NodeContext ctx) throws NodeException {
            List<int[]> targets = new ArrayList<>();
            for (VectorSchemaRoot b : held) {
                int[] t = new int[b.getRowCount()];
                java.util.Arrays.fill(t, 1);
                targets.add(t);
            }
            for (long ref : winners.all()) {
                targets.get((int) (ref >>> 32))[(int) ref] = 0;
            }
            winners.clear();
            while (!held.isEmpty()) {
                // distribute gives the batch up, whatever happens
                distribute(ctx, held.removeFirst(), targets.removeFirst(), PORTS);
            }
        }

        @Override
        public void close() {
            held.forEach(VectorSchemaRoot::close);
            held.clear();
            winners.clear();
            seen.clear();
        }
    }
}

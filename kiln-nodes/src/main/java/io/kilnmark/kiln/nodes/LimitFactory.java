package io.kilnmark.kiln.nodes;

import java.util.List;
import java.util.Map;

import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;

import io.kilnmark.kiln.SettingSpec;
import io.kilnmark.kiln.Node;
import io.kilnmark.kiln.NodeContext;
import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.NodeFactory;
import io.kilnmark.kiln.NodeKind;

/**
 * {@code limit}: passes on the first N rows, for trying a pipeline out, then stops reading. The
 * graph runner ends the nodes before it early when nothing else reads them, and keeps feeding
 * those that also feed other nodes, so a large source is not read to its end for nothing.
 */
public final class LimitFactory implements NodeFactory {

    public static final String TYPE = "limit";
    static final long MAX_ROWS = Long.MAX_VALUE / 2;

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
        return "Limit rows";
    }

    @Override
    public String help() {
        return "Passes on only the first rows, for trying a pipeline out on a little data.";
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
        return List.of(SettingSpec.required("rows", "Rows", SettingSpec.Kind.NUMBER, "How many rows to pass on."));
    }

    private static long rows(Config c) {
        if (!c.has("rows")) {
            c.problem("rows", "is required");
            return 0;
        }
        return c.number("rows", 0, 0, MAX_ROWS);
    }

    @Override
    public List<String> check(Map<String, Object> config) {
        Config c = new Config(config);
        rows(c);
        return c.problems();
    }

    @Override
    public Node create(Map<String, Object> config) throws NodeException {
        Config c = new Config(config);
        long rows = rows(c);
        c.orThrow(TYPE);
        return new LimitNode(rows);
    }

    @Override
    public Schema outputSchema(String port, Map<String, Schema> inputs, Map<String, Object> config)
        throws NodeException {
        if (!port.equals("out")) {
            throw new NodeException("A limit node has no output port '" + port + "'");
        }
        return inputs.get("in");
    }

    /** The running node. */
    static final class LimitNode extends BaseNode {
        private final long limit;
        private long sent;

        LimitNode(long limit) {
            super(TYPE);
            this.limit = limit;
        }

        @Override
        public boolean process(NodeContext ctx) throws NodeException {
            if (sent >= limit) {
                return false;
            }
            VectorSchemaRoot batch = take(ctx, "in");
            if (batch == null) {
                return false;
            }
            long left = limit - sent;
            int n = batch.getRowCount();
            if (n <= left) {
                sent += n;
                put(ctx, "out", batch);
                return sent < limit;
            }
            VectorSchemaRoot first;
            try {
                first = batch.slice(0, (int) left);
            } finally {
                batch.close();
            }
            sent += left;
            put(ctx, "out", first);
            return false;
        }
    }
}

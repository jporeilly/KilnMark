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
import io.kilnmark.kiln.nodes.expr.Batch;
import io.kilnmark.kiln.nodes.expr.ExprException;
import io.kilnmark.kiln.nodes.expr.Expression;
import io.kilnmark.kiln.nodes.expr.Type;

/** {@code filter}: keeps the rows where a condition is true; the others go to {@code rejected} if it is connected. */
public final class FilterFactory implements NodeFactory {

    public static final String TYPE = "filter";

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
        return "Filter rows";
    }

    @Override
    public String help() {
        return "Keeps the rows where a condition is true. Rows where it is false or empty (null) go to the "
            + "rejected port, if something is connected to it.";
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
        return List.of(SettingSpec.required("condition", "Condition", SettingSpec.Kind.EXPRESSION,
            "The rows to keep, for example status = 'active' and amount > 0."));
    }

    @Override
    public List<String> check(Map<String, Object> config) {
        Config c = new Config(config);
        c.expression("condition", true);
        return c.problems();
    }

    @Override
    public Node create(Map<String, Object> config) throws NodeException {
        Config c = new Config(config);
        Expression condition = c.expression("condition", true);
        c.orThrow(TYPE);
        return new FilterNode(condition);
    }

    @Override
    public Schema outputSchema(String port, Map<String, Schema> inputs, Map<String, Object> config)
        throws NodeException {
        Schema in = inputs.get("in");
        if (!outputPorts().contains(port)) {
            throw new NodeException("A filter node has no output port '" + port + "'");
        }
        if (in == null) {
            return null;
        }
        Config c = new Config(config);
        Expression condition = c.expression("condition", true);
        c.orThrow(TYPE);
        try {
            FilterNode.checkCondition(condition.compile(in));
        } catch (ExprException e) {
            throw new NodeException("condition: " + e.getMessage(), e);
        }
        return in;
    }

    /** The running node. */
    static final class FilterNode extends BaseNode {
        private static final List<String> PORTS = List.of("out", "rejected");
        private final Expression condition;
        private Schema boundTo;
        private Expression.Compiled compiled;

        FilterNode(Expression condition) {
            super(TYPE);
            this.condition = condition;
        }

        static void checkCondition(Expression.Compiled c) throws ExprException {
            if (c.type().kind() != Type.Kind.BOOLEAN && !c.type().isNull()) {
                throw new ExprException("the condition must be true or false, but it gives " + c.type().family(), 0,
                    "compare the value, for example amount > 0");
            }
        }

        @Override
        public boolean process(NodeContext ctx) throws NodeException {
            VectorSchemaRoot batch = take(ctx, "in");
            if (batch == null) {
                return false;
            }
            int[] target;
            try {
                if (!batch.getSchema().equals(boundTo)) {
                    compiled = condition.compile(batch.getSchema());
                    checkCondition(compiled);
                    boundTo = batch.getSchema();
                }
                boolean[] keep = compiled.test(Batch.of(batch, now), null);
                target = new int[keep.length];
                for (int i = 0; i < keep.length; i++) {
                    target[i] = keep[i] ? 0 : 1;
                }
            } catch (ExprException e) {
                batch.close();
                throw fail("condition", e);
            } catch (RuntimeException e) {
                batch.close();
                throw e;
            }
            distribute(ctx, batch, target, PORTS);
            return true;
        }
    }
}

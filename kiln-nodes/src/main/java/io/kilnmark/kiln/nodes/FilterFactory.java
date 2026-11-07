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
import io.kilnmark.kiln.RowErrors;
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
            + "rejected port, and rows where it fails go to the errors port, if something is connected to them.";
    }

    @Override
    public List<String> inputPorts() {
        return List.of("in");
    }

    @Override
    public List<String> outputPorts() {
        return List.of("out", "rejected", RowErrors.PORT);
    }

    @Override
    public List<SettingSpec> settings() {
        return Failing.withTolerance(List.of(SettingSpec.required("condition", "Condition", SettingSpec.Kind.EXPRESSION,
            "The rows to keep, for example status = 'active' and amount > 0.")));
    }

    @Override
    public List<String> check(Map<String, Object> config) {
        Config c = new Config(config);
        c.expression("condition", true);
        return Failing.withTolerance(c.problems(), config);
    }

    @Override
    public Node create(Map<String, Object> config) throws NodeException {
        Config c = new Config(config);
        Expression condition = c.expression("condition", true);
        c.problems().addAll(RowErrors.check(config));
        c.orThrow(TYPE);
        return new FilterNode(condition, RowErrors.Tally.of(config));
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
        return port.equals(RowErrors.PORT) ? RowErrors.schema(in) : in;
    }

    /** The running node. */
    static final class FilterNode extends BaseNode {
        private static final List<String> PORTS = List.of("out", "rejected");
        private final Expression condition;
        private final RowErrors.Tally tally;
        private Schema boundTo;
        private Expression.Compiled compiled;

        FilterNode(Expression condition, RowErrors.Tally tally) {
            super(TYPE);
            this.condition = condition;
            this.tally = tally;
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
                Failing.finish(tally, ctx);
                return false;
            }
            tally.arrived(batch.getRowCount());
            Failing.Done<int[]> done;
            try {
                if (!batch.getSchema().equals(boundTo)) {
                    compiled = condition.compile(batch.getSchema());
                    checkCondition(compiled);
                    boundTo = batch.getSchema();
                }
                done = Failing.work(batch, this::targets, tally, ctx, name);
            } catch (ExprException e) {
                batch.close();
                throw fail("condition", e);
            } catch (NodeException | RuntimeException e) {
                batch.close();
                throw e;
            }
            if (done == null || done.fresh()) {
                batch.close();
            }
            if (done != null) {
                distribute(ctx, done.rows(), done.result(), PORTS);
            }
            return true;
        }

        /** Where each row goes: 0 (out) where the condition is true, 1 (rejected) where not. */
        private int[] targets(VectorSchemaRoot rows) throws Failing.RowFailure {
            boolean[] keep;
            try {
                keep = compiled.test(Batch.of(rows, now), null);
            } catch (ExprException e) {
                throw new Failing.RowFailure("condition", "condition", e);
            }
            int[] target = new int[keep.length];
            for (int i = 0; i < keep.length; i++) {
                target[i] = keep[i] ? 0 : 1;
            }
            return target;
        }
    }
}

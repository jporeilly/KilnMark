package io.kilnmark.kiln.steps;

import java.util.List;
import java.util.Map;

import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;

import io.kilnmark.kiln.SettingSpec;
import io.kilnmark.kiln.Step;
import io.kilnmark.kiln.StepContext;
import io.kilnmark.kiln.StepException;
import io.kilnmark.kiln.StepFactory;
import io.kilnmark.kiln.StepKind;
import io.kilnmark.kiln.steps.expr.Batch;
import io.kilnmark.kiln.steps.expr.ExprException;
import io.kilnmark.kiln.steps.expr.Expression;
import io.kilnmark.kiln.steps.expr.Type;

/** {@code filter}: keeps the rows where a condition is true; the others go to {@code rejected} if it is connected. */
public final class FilterFactory implements StepFactory {

    public static final String TYPE = "filter";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public StepKind kind() {
        return StepKind.TRANSFORM;
    }

    @Override
    public String category() {
        return io.kilnmark.kiln.StepCategories.FILTER_AND_ROUTE;
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
    public Step create(Map<String, Object> config) throws StepException {
        Config c = new Config(config);
        Expression condition = c.expression("condition", true);
        c.orThrow(TYPE);
        return new FilterStep(condition);
    }

    @Override
    public Schema outputSchema(String port, Map<String, Schema> inputs, Map<String, Object> config)
        throws StepException {
        Schema in = inputs.get("in");
        if (!outputPorts().contains(port)) {
            throw new StepException("A filter step has no output port '" + port + "'");
        }
        if (in == null) {
            return null;
        }
        Config c = new Config(config);
        Expression condition = c.expression("condition", true);
        c.orThrow(TYPE);
        try {
            FilterStep.checkCondition(condition.compile(in));
        } catch (ExprException e) {
            throw new StepException("condition: " + e.getMessage(), e);
        }
        return in;
    }

    /** The running step. */
    static final class FilterStep extends BaseStep {
        private static final List<String> PORTS = List.of("out", "rejected");
        private final Expression condition;
        private Schema boundTo;
        private Expression.Compiled compiled;

        FilterStep(Expression condition) {
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
        public boolean process(StepContext ctx) throws StepException {
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

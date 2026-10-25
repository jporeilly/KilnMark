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

/**
 * {@code limit}: passes on the first N rows, for trying a pipeline out, then stops reading. The
 * graph runner ends the steps before it early when nothing else reads them, and keeps feeding
 * those that also feed other steps, so a large source is not read to its end for nothing.
 */
public final class LimitFactory implements StepFactory {

    public static final String TYPE = "limit";
    static final long MAX_ROWS = Long.MAX_VALUE / 2;

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
    public Step create(Map<String, Object> config) throws StepException {
        Config c = new Config(config);
        long rows = rows(c);
        c.orThrow(TYPE);
        return new LimitStep(rows);
    }

    @Override
    public Schema outputSchema(String port, Map<String, Schema> inputs, Map<String, Object> config)
        throws StepException {
        if (!port.equals("out")) {
            throw new StepException("A limit step has no output port '" + port + "'");
        }
        return inputs.get("in");
    }

    /** The running step. */
    static final class LimitStep extends BaseStep {
        private final long limit;
        private long sent;

        LimitStep(long limit) {
            super(TYPE);
            this.limit = limit;
        }

        @Override
        public boolean process(StepContext ctx) throws StepException {
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

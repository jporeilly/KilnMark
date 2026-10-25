package io.kilnmark.kiln.steps;

import java.util.LinkedHashMap;
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
import io.kilnmark.kiln.steps.expr.Aggregation;
import io.kilnmark.kiln.steps.expr.ExprException;
import io.kilnmark.kiln.steps.expr.Expression;

/**
 * {@code aggregate}: groups rows and summarises each group. Holds every group in memory (up to
 * {@code memory_limit_mb}) and writes them, in the order first seen, when its input ends.
 */
public final class AggregateFactory implements StepFactory {

    public static final String TYPE = "aggregate";
    /** Groups per output batch. */
    static final int BATCH_ROWS = 8_192;

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
        return "Aggregate";
    }

    @Override
    public String help() {
        return "Groups rows that share values and summarises each group with count, sum, min, max, avg or "
            + "count_distinct. Without group_by, summarises all rows into one.";
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
            SettingSpec.optional("group_by", "Group by", SettingSpec.Kind.COLUMNS, null,
                "The columns whose values make a group. Leave empty for one row summarising everything."),
            SettingSpec.required("values", "Values", SettingSpec.Kind.EXPRESSIONS,
                "Each output column's name and its summary, for example total: sum(amount)."),
            SettingSpec.optional("memory_limit_mb", "Memory limit (MB)", SettingSpec.Kind.NUMBER, 256,
                "The most memory the groups may take while they are collected."));
    }

    record Settings(List<String> groupBy, LinkedHashMap<String, Expression> values, long limitMb) {
    }

    private static Settings read(Config c) {
        List<String> groupBy = c.columns("group_by", false);
        LinkedHashMap<String, Expression> values = c.expressions("values", true);
        long mb = c.number("memory_limit_mb", 256, 1, 1_048_576);
        if (groupBy != null && values != null) {
            for (String v : values.keySet()) {
                if (groupBy.contains(v)) {
                    c.problem("values." + v, "'" + v + "' is also a group_by column; give the value another name");
                }
            }
        }
        return new Settings(groupBy == null ? List.of() : groupBy, values, mb);
    }

    @Override
    public List<String> check(Map<String, Object> config) {
        Config c = new Config(config);
        read(c);
        return c.problems();
    }

    @Override
    public Step create(Map<String, Object> config) throws StepException {
        Config c = new Config(config);
        Settings s = read(c);
        c.orThrow(TYPE);
        return new AggregateStep(s);
    }

    @Override
    public Schema outputSchema(String port, Map<String, Schema> inputs, Map<String, Object> config)
        throws StepException {
        if (!port.equals("out")) {
            throw new StepException("An aggregate step has no output port '" + port + "'");
        }
        Schema in = inputs.get("in");
        if (in == null) {
            return null;
        }
        Config c = new Config(config);
        Settings s = read(c);
        c.orThrow(TYPE);
        return plan(in, s, "").outputSchema();
    }

    static Aggregation plan(Schema in, Settings s, String prefix) throws StepException {
        Aggregation a;
        try {
            a = new Aggregation(in, s.groupBy());
        } catch (ExprException e) {
            throw new StepException(prefix + "group_by: " + e.getMessage(), e);
        }
        for (Map.Entry<String, Expression> v : s.values().entrySet()) {
            try {
                a.add(v.getKey(), v.getValue());
            } catch (ExprException e) {
                throw new StepException(prefix + "value " + v.getKey() + ": " + e.getMessage(), e);
            }
        }
        return a;
    }

    /** The running step. */
    static final class AggregateStep extends BaseStep {
        private final Settings settings;
        private Aggregation plan;
        private Schema boundTo;
        private Aggregation.State state;
        private boolean ended;
        private int emitted;

        AggregateStep(Settings settings) {
            super(TYPE);
            this.settings = settings;
        }

        @Override
        public boolean process(StepContext ctx) throws StepException {
            if (ended) {
                return emit(ctx);
            }
            VectorSchemaRoot batch = take(ctx, "in");
            if (batch == null) {
                ended = true;
                // With no input at all the columns are unknown, so there is nothing to write.
                return state != null && emit(ctx);
            }
            try {
                if (!batch.getSchema().equals(boundTo)) {
                    if (state != null) {
                        throw fail("the input's columns changed part-way through; an aggregate needs the same "
                            + "columns in every batch");
                    }
                    plan = plan(batch.getSchema(), settings, "Step " + name + ", ");
                    state = plan.newState(now);
                    boundTo = batch.getSchema();
                }
                state.add(batch);
            } catch (ExprException e) {
                throw fail("values", e);
            } finally {
                batch.close();
            }
            if (state.estimatedBytes() > megabytes(settings.limitMb())) {
                throw fail("the groups take more than " + settings.limitMb() + " MB (" + count(state.groups())
                    + " groups so far); raise memory_limit_mb, or group by fewer or shorter columns");
            }
            return true;
        }

        /** Writes the next batch of groups; false once all are written. */
        private boolean emit(StepContext ctx) throws StepException {
            int total = state.outputRows();
            if (emitted >= total) {
                return false;
            }
            int to = Math.min(total, emitted + BATCH_ROWS);
            VectorSchemaRoot out;
            try {
                out = state.emit(emitted, to, ctx.allocator());
            } catch (ExprException e) {
                throw fail("values", e);
            }
            emitted = to;
            put(ctx, "out", out);
            return emitted < total;
        }
    }
}

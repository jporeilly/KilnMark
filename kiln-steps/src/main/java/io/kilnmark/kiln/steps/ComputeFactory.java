package io.kilnmark.kiln.steps;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;

import io.kilnmark.kiln.SettingSpec;
import io.kilnmark.kiln.Step;
import io.kilnmark.kiln.StepContext;
import io.kilnmark.kiln.StepException;
import io.kilnmark.kiln.StepFactory;
import io.kilnmark.kiln.StepKind;
import io.kilnmark.kiln.steps.expr.ArrowIO;
import io.kilnmark.kiln.steps.expr.Batch;
import io.kilnmark.kiln.steps.expr.Col;
import io.kilnmark.kiln.steps.expr.ExprException;
import io.kilnmark.kiln.steps.expr.Expression;

/**
 * {@code compute}: adds or replaces columns from expressions. A name that is already a column
 * replaces it where it stands; a new name is added at the end. Expressions are worked out in the
 * order written, and each can use the columns computed before it.
 */
public final class ComputeFactory implements StepFactory {

    public static final String TYPE = "compute";

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
        return "Compute columns";
    }

    @Override
    public String help() {
        return "Adds or replaces columns, each worked out from an expression. Later expressions can use "
            + "columns computed earlier in the list.";
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
        return List.of(SettingSpec.required("columns", "Columns", SettingSpec.Kind.EXPRESSIONS,
            "Each column's name and the expression that works it out, for example full_name: "
                + "trim(first_name) || ' ' || trim(last_name)."));
    }

    @Override
    public List<String> check(Map<String, Object> config) {
        Config c = new Config(config);
        c.expressions("columns", true);
        return c.problems();
    }

    @Override
    public Step create(Map<String, Object> config) throws StepException {
        Config c = new Config(config);
        LinkedHashMap<String, Expression> columns = c.expressions("columns", true);
        c.orThrow(TYPE);
        return new ComputeStep(columns);
    }

    @Override
    public Schema outputSchema(String port, Map<String, Schema> inputs, Map<String, Object> config)
        throws StepException {
        if (!port.equals("out")) {
            throw new StepException("A compute step has no output port '" + port + "'");
        }
        Schema in = inputs.get("in");
        if (in == null) {
            return null;
        }
        Config c = new Config(config);
        LinkedHashMap<String, Expression> columns = c.expressions("columns", true);
        c.orThrow(TYPE);
        Plan plan = new Plan();
        try {
            return plan.bind(in, columns);
        } catch (ExprException e) {
            throw new StepException("column " + plan.current + ": " + e.getMessage(), e);
        }
    }

    /** The expressions checked against one input schema, and the schema they give. */
    static final class Plan {
        final List<Expression.Compiled> compiled = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        String current;

        Schema bind(Schema in, Map<String, Expression> columns) throws ExprException {
            List<Field> fields = new ArrayList<>(in.getFields());
            for (Map.Entry<String, Expression> e : columns.entrySet()) {
                current = e.getKey();
                Expression.Compiled c = e.getValue().compile(new Schema(fields));
                Field f = c.field(e.getKey());
                int at = indexOf(fields, e.getKey());
                if (at >= 0) {
                    fields.set(at, f);
                } else {
                    fields.add(f);
                }
                compiled.add(c);
                names.add(e.getKey());
            }
            current = null;
            return new Schema(fields);
        }

        private static int indexOf(List<Field> fields, String name) {
            for (int i = 0; i < fields.size(); i++) {
                if (fields.get(i).getName().equals(name)) {
                    return i;
                }
            }
            return -1;
        }
    }

    /** The running step. */
    static final class ComputeStep extends BaseStep {
        private final LinkedHashMap<String, Expression> columns;
        private Schema boundTo;
        private Plan plan;
        private Schema outSchema;

        ComputeStep(LinkedHashMap<String, Expression> columns) {
            super(TYPE);
            this.columns = columns;
        }

        @Override
        public boolean process(StepContext ctx) throws StepException {
            VectorSchemaRoot batch = take(ctx, "in");
            if (batch == null) {
                return false;
            }
            VectorSchemaRoot out;
            try {
                out = compute(batch, ctx);
            } finally {
                batch.close();
            }
            put(ctx, "out", out);
            return true;
        }

        private VectorSchemaRoot compute(VectorSchemaRoot batch, StepContext ctx) throws StepException {
            Plan p = plan;
            if (!batch.getSchema().equals(boundTo)) {
                p = new Plan();
                try {
                    outSchema = p.bind(batch.getSchema(), columns);
                } catch (ExprException e) {
                    throw fail("column " + p.current, e);
                }
                plan = p;
                boundTo = batch.getSchema();
            }
            Batch rows = Batch.of(batch, now);
            Map<String, Col> computed = new HashMap<>();
            for (int i = 0; i < p.compiled.size(); i++) {
                String column = p.names.get(i);
                try {
                    Col c = p.compiled.get(i).eval(rows);
                    rows.put(column, c);
                    computed.put(column, c);
                } catch (ExprException e) {
                    throw fail("column " + column, e);
                }
            }
            List<FieldVector> vectors = new ArrayList<>();
            try {
                for (Field f : outSchema.getFields()) {
                    Col c = computed.get(f.getName());
                    if (c != null) {
                        try {
                            vectors.add(ArrowIO.write(c, f, ctx.allocator()));
                        } catch (ExprException e) {
                            throw fail("column " + f.getName(), e);
                        }
                    } else {
                        vectors.add(move(batch.getVector(f.getName()), f, ctx.allocator()));
                    }
                }
                return new VectorSchemaRoot(outSchema, vectors, batch.getRowCount());
            } catch (StepException | RuntimeException e) {
                vectors.forEach(FieldVector::close);
                throw e;
            }
        }
    }
}

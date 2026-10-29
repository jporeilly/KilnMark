package io.kilnmark.kiln.nodes;

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
import io.kilnmark.kiln.Node;
import io.kilnmark.kiln.NodeContext;
import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.NodeFactory;
import io.kilnmark.kiln.NodeKind;
import io.kilnmark.kiln.nodes.expr.ArrowIO;
import io.kilnmark.kiln.nodes.expr.Batch;
import io.kilnmark.kiln.nodes.expr.Col;
import io.kilnmark.kiln.nodes.expr.ExprException;
import io.kilnmark.kiln.nodes.expr.Expression;

/**
 * {@code compute}: adds or replaces columns from expressions. A name that is already a column
 * replaces it where it stands; a new name is added at the end. Expressions are worked out in the
 * order written, and each can use the columns computed before it.
 */
public final class ComputeFactory implements NodeFactory {

    public static final String TYPE = "compute";

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
        return io.kilnmark.kiln.NodeCategories.TRANSFORM;
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
    public Node create(Map<String, Object> config) throws NodeException {
        Config c = new Config(config);
        LinkedHashMap<String, Expression> columns = c.expressions("columns", true);
        c.orThrow(TYPE);
        return new ComputeNode(columns);
    }

    @Override
    public Schema outputSchema(String port, Map<String, Schema> inputs, Map<String, Object> config)
        throws NodeException {
        if (!port.equals("out")) {
            throw new NodeException("A compute node has no output port '" + port + "'");
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
            throw new NodeException("column " + plan.current + ": " + e.getMessage(), e);
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

    /** The running node. */
    static final class ComputeNode extends BaseNode {
        private final LinkedHashMap<String, Expression> columns;
        private Schema boundTo;
        private Plan plan;
        private Schema outSchema;

        ComputeNode(LinkedHashMap<String, Expression> columns) {
            super(TYPE);
            this.columns = columns;
        }

        @Override
        public boolean process(NodeContext ctx) throws NodeException {
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

        private VectorSchemaRoot compute(VectorSchemaRoot batch, NodeContext ctx) throws NodeException {
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
            } catch (NodeException | RuntimeException e) {
                vectors.forEach(FieldVector::close);
                throw e;
            }
        }
    }
}

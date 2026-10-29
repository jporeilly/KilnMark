package io.kilnmark.kiln.nodes;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;

import io.kilnmark.kiln.MetadataEmitter;
import io.kilnmark.kiln.RowSet;
import io.kilnmark.kiln.Node;
import io.kilnmark.kiln.NodeContext;
import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.NodeFactory;
import io.kilnmark.kiln.nodes.expr.ArrowIO;
import io.kilnmark.kiln.nodes.expr.Col;
import io.kilnmark.kiln.nodes.expr.Type;

/**
 * Drives one node outside a runner: named input row sets filled before the run, named output row
 * sets collected after it, and a check that the node's allocator holds nothing once it is closed.
 */
final class Harness implements AutoCloseable {

    final RootAllocator root = new RootAllocator(Long.MAX_VALUE);
    final BufferAllocator source = root.newChildAllocator("source", 0, Long.MAX_VALUE);
    final BufferAllocator nodeMemory = root.newChildAllocator("node", 0, Long.MAX_VALUE);
    final BufferAllocator sink = root.newChildAllocator("sink", 0, Long.MAX_VALUE);
    private final Map<String, List<RowSet>> inputs = new LinkedHashMap<>();
    private final Map<String, RowSet> outputs = new LinkedHashMap<>();
    /** Rows that arrived on each output port, by column name. */
    final Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
    final Map<String, Schema> schemas = new LinkedHashMap<>();
    final Map<String, Integer> batches = new LinkedHashMap<>();
    private final Map<String, Schema> declared = new LinkedHashMap<>();

    // ---- building batches ----

    static Field field(String name, ArrowType type) {
        return new Field(name, FieldType.nullable(type), null);
    }

    static final ArrowType INT64 = new ArrowType.Int(64, true);
    static final ArrowType INT32 = new ArrowType.Int(32, true);
    static final ArrowType FLOAT64 = Type.FLOAT64.arrow();
    static final ArrowType UTF8 = ArrowType.Utf8.INSTANCE;
    static final ArrowType BOOL = ArrowType.Bool.INSTANCE;
    static final ArrowType DATE = Type.DATE.arrow();
    static final ArrowType TIMESTAMP = Type.TIMESTAMP.arrow();

    static ArrowType decimal(int p, int s) {
        return new ArrowType.Decimal(p, s, 128);
    }

    static Schema schema(Field... fields) {
        return new Schema(List.of(fields));
    }

    /** A batch from rows of Java values (Long/Integer, Double, BigDecimal, String, Boolean, LocalDate, Instant, null). */
    VectorSchemaRoot batch(Schema schema, Object[]... values) {
        return batch(source, schema, values);
    }

    static VectorSchemaRoot batch(BufferAllocator allocator, Schema schema, Object[]... values) {
        List<FieldVector> vectors = new ArrayList<>();
        try {
            for (int c = 0; c < schema.getFields().size(); c++) {
                Field f = schema.getFields().get(c);
                Col col = Col.nulls(Type.of(f.getType()), values.length);
                for (int r = 0; r < values.length; r++) {
                    col.set(r, javaToCol(values[r][c]));
                }
                vectors.add(ArrowIO.write(col, f, allocator));
            }
        } catch (Exception e) {
            vectors.forEach(FieldVector::close);
            throw new IllegalStateException(e);
        }
        return new VectorSchemaRoot(schema, vectors, values.length);
    }

    private static Object javaToCol(Object v) {
        return switch (v) {
            case null -> null;
            case Integer i -> (long) i;
            case LocalDate d -> d.toEpochDay();
            case Instant t -> t.getEpochSecond() * 1_000_000L + t.getNano() / 1_000;
            default -> v;
        };
    }

    static Object[] row(Object... values) {
        return values;
    }

    // ---- wiring ----

    /** Fills an input port (adding another row set if the port already has one) and ends it. */
    Harness input(String port, VectorSchemaRoot... batches) {
        RowSet rs = new RowSet(port + inputs.getOrDefault(port, List.of()).size(), batches.length + 1);
        try {
            for (VectorSchemaRoot b : batches) {
                rs.put(b);
            }
            rs.finish();
        } catch (InterruptedException | NodeException e) {
            throw new IllegalStateException(e);
        }
        inputs.computeIfAbsent(port, k -> new ArrayList<>()).add(rs);
        return this;
    }

    /** Adds an input row set the test fills itself. */
    Harness input(String port, RowSet rs) {
        inputs.computeIfAbsent(port, k -> new ArrayList<>()).add(rs);
        return this;
    }

    Harness connect(String... ports) {
        for (String p : ports) {
            outputs.put(p, new RowSet("out-" + p, 100_000));
        }
        return this;
    }

    // ---- running ----

    /** Creates, runs and closes the node; collects outputs; checks the node freed all its memory. */
    Harness run(NodeFactory factory, Map<String, Object> config) throws NodeException {
        assertEquals(List.of(), factory.check(config), "settings should be valid");
        Node node = factory.create(config);
        Context ctx = new Context(config);
        Throwable failure = null;
        try {
            node.init(ctx);
            while (node.process(ctx)) {
                // next unit of work
            }
        } catch (Throwable t) {
            failure = t;
            throw t;
        } finally {
            node.close();
            collect();
            long left = nodeMemory.getAllocatedMemory();
            if (left != 0) { // checked on failures too: a node must free its memory however it ends
                AssertionError leak = new AssertionError("the node leaked " + left + " bytes");
                if (failure != null) {
                    leak.addSuppressed(failure);
                }
                throw leak;
            }
        }
        return this;
    }

    /** Columns the pipeline check would give a node for an input: {@code in}, {@code lookup}, {@code in[0]} ... */
    Harness declare(String key, Schema schema) {
        declared.put(key, schema);
        return this;
    }

    /** Batches left unread on the inputs. */
    int unread() throws InterruptedException, NodeException {
        int n = 0;
        for (List<RowSet> list : inputs.values()) {
            for (RowSet rs : list) {
                VectorSchemaRoot b;
                while (!rs.atEnd() && (b = rs.poll(sink, 0, java.util.concurrent.TimeUnit.MILLISECONDS)) != null) {
                    b.close();
                    n++;
                }
            }
        }
        return n;
    }

    private void collect() {
        for (Map.Entry<String, RowSet> e : outputs.entrySet()) {
            List<Map<String, Object>> list = new ArrayList<>();
            rows.put(e.getKey(), list);
            batches.put(e.getKey(), 0);
            try {
                e.getValue().finish();
                VectorSchemaRoot b;
                while ((b = e.getValue().take(sink)) != null) {
                    try (VectorSchemaRoot batch = b) {
                        schemas.putIfAbsent(e.getKey(), batch.getSchema());
                        batches.merge(e.getKey(), 1, Integer::sum);
                        list.addAll(rowsOf(batch));
                    }
                }
            } catch (InterruptedException | NodeException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    static List<Map<String, Object>> rowsOf(VectorSchemaRoot batch) {
        List<Col> cols = new ArrayList<>();
        for (FieldVector v : batch.getFieldVectors()) {
            cols.add(Type.of(v.getField().getType()) == null ? null : ArrowIO.read(v));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int r = 0; r < batch.getRowCount(); r++) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int c = 0; c < cols.size(); c++) {
                Col col = cols.get(c);
                Type t = col == null ? null : col.type();
                Object v = col == null ? batch.getVector(c).getObject(r) : col.get(r);
                if (v != null && t != null && t.kind() == Type.Kind.DATE) {
                    v = LocalDate.ofEpochDay((Long) v);
                } else if (v != null && t != null && t.kind() == Type.Kind.TIMESTAMP) {
                    long us = (Long) v;
                    v = Instant.ofEpochSecond(Math.floorDiv(us, 1_000_000L), Math.floorMod(us, 1_000_000L) * 1_000L);
                }
                row.put(batch.getSchema().getFields().get(c).getName(), v);
            }
            out.add(row);
        }
        return out;
    }

    /** One column of the rows on a port. */
    List<Object> column(String port, String name) {
        return rows.get(port).stream().map(r -> r.get(name)).toList();
    }

    static BigDecimal dec(String s) {
        return new BigDecimal(s);
    }

    @Override
    public void close() {
        try {
            unread();
        } catch (InterruptedException | NodeException e) {
            throw new IllegalStateException(e);
        }
        source.close();
        nodeMemory.close();
        sink.close();
        root.close();
    }

    private final class Context implements NodeContext {
        private final Map<String, Object> config;

        Context(Map<String, Object> config) {
            this.config = config;
        }

        @Override
        public String nodeName() {
            return "test";
        }

        @Override
        public Map<String, String> settings() {
            Map<String, String> m = new LinkedHashMap<>();
            config.forEach((k, v) -> m.put(k, String.valueOf(v)));
            return m;
        }

        @Override
        public Map<String, Object> config() {
            return config;
        }

        @Override
        public MetadataEmitter metadata() {
            return e -> { };
        }

        @Override
        public BufferAllocator allocator() {
            return nodeMemory;
        }

        @Override
        public RowSet input() {
            return inputs.get("in").getFirst();
        }

        @Override
        public RowSet output() {
            return outputs.get("out");
        }

        @Override
        public boolean hasInput() {
            return !inputs.isEmpty();
        }

        @Override
        public boolean hasOutput() {
            return !outputs.isEmpty();
        }

        @Override
        public Set<String> inputPorts() {
            return new LinkedHashSet<>(inputs.keySet());
        }

        @Override
        public Set<String> outputPorts() {
            return new LinkedHashSet<>(outputs.keySet());
        }

        @Override
        public RowSet input(String port) throws NodeException {
            List<RowSet> rs = inputs.get(port);
            if (rs == null || rs.size() != 1) {
                throw new NodeException("no single input on " + port);
            }
            return rs.getFirst();
        }

        @Override
        public List<RowSet> inputs(String port) {
            return inputs.getOrDefault(port, List.of());
        }

        @Override
        public RowSet output(String port) {
            return outputs.get(port);
        }

        @Override
        public Schema inputSchema(String port) {
            return declared.get(port);
        }

        @Override
        public Schema inputSchema(String port, int index) {
            return declared.get(port + "[" + index + "]");
        }
    }
}

package io.kilnmark.kiln.nodes;

import static io.kilnmark.kiln.nodes.Harness.INT64;
import static io.kilnmark.kiln.nodes.Harness.UTF8;
import static io.kilnmark.kiln.nodes.Harness.dec;
import static io.kilnmark.kiln.nodes.Harness.decimal;
import static io.kilnmark.kiln.nodes.Harness.field;
import static io.kilnmark.kiln.nodes.Harness.row;
import static io.kilnmark.kiln.nodes.Harness.schema;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.SettingSpec;
import io.kilnmark.kiln.Node;
import io.kilnmark.kiln.NodeContext;
import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.NodeFactories;
import io.kilnmark.kiln.NodeFactory;
import io.kilnmark.kiln.NodeKind;
import io.kilnmark.kiln.graph.KilnGraphRunner;
import io.kilnmark.kiln.yaml.Compiled;
import io.kilnmark.kiln.yaml.PipelineCompiler;

/** The node catalogue, and the nodes together in real pipelines: compiled from YAML and run as a graph. */
class PipelineTest {

    static final Schema CUSTOMERS = schema(field("customer_id", INT64), field("first_name", UTF8),
        field("last_name", UTF8));
    static final Schema TXNS = schema(field("customer_id", INT64), field("amount", decimal(10, 2)));

    /** A test source: fixed rows of fixed columns, chosen by its {@code table} setting. */
    static final class Rows implements NodeFactory {
        @Override
        public String type() {
            return "rows";
        }

        @Override
        public NodeKind kind() {
            return NodeKind.SOURCE;
        }

        @Override
        public String label() {
            return "Test rows";
        }

        @Override
        public String help() {
            return "Rows for tests.";
        }

        @Override
        public List<String> inputPorts() {
            return List.of();
        }

        @Override
        public List<String> outputPorts() {
            return List.of("out");
        }

        @Override
        public List<SettingSpec> settings() {
            return List.of(SettingSpec.required("table", "Table", SettingSpec.Kind.TEXT, "customers or txns"));
        }

        @Override
        public Schema outputSchema(String port, Map<String, Schema> inputs, Map<String, Object> config) {
            return config.get("table").equals("customers") ? CUSTOMERS : TXNS;
        }

        @Override
        public Node create(Map<String, Object> config) {
            boolean customers = config.get("table").equals("customers");
            return new Node() {
                private int sent;

                @Override
                public String type() {
                    return "rows";
                }

                @Override
                public void init(NodeContext context) {
                }

                @Override
                public boolean process(NodeContext ctx) throws NodeException {
                    VectorSchemaRoot b = customers
                        ? Harness.batch(ctx.allocator(), CUSTOMERS, row(1L, " Ada ", "Lovelace"),
                            row(2L, "Grace", "Hopper"))
                        : Harness.batch(ctx.allocator(), TXNS, row(1L, dec("10.00")), row(1L, dec("5.00")),
                            row(2L, dec("7.50")), row(3L, dec("1.00")));
                    try {
                        ctx.emit("out", b);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new NodeException("Interrupted", e);
                    }
                    return ++sent < 3;
                }
            };
        }
    }

    /** A test sink: keeps every row it receives. */
    static final class Collect implements NodeFactory {
        static final Map<String, List<Map<String, Object>>> ROWS = Collections.synchronizedMap(new java.util.HashMap<>());

        @Override
        public String type() {
            return "collect";
        }

        @Override
        public NodeKind kind() {
            return NodeKind.SINK;
        }

        @Override
        public String label() {
            return "Collect";
        }

        @Override
        public String help() {
            return "Keeps rows for tests.";
        }

        @Override
        public List<String> inputPorts() {
            return List.of("in");
        }

        @Override
        public List<String> outputPorts() {
            return List.of();
        }

        @Override
        public List<SettingSpec> settings() {
            return List.of(SettingSpec.required("name", "Name", SettingSpec.Kind.TEXT, "Where the rows go"));
        }

        @Override
        public Node create(Map<String, Object> config) {
            String name = String.valueOf(config.get("name"));
            List<Map<String, Object>> rows = Collections.synchronizedList(new ArrayList<>());
            ROWS.put(name, rows);
            return new Node() {
                @Override
                public String type() {
                    return "collect";
                }

                @Override
                public void init(NodeContext context) {
                }

                @Override
                public boolean process(NodeContext ctx) throws NodeException {
                    VectorSchemaRoot b;
                    try {
                        b = ctx.input().take(ctx.allocator());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new NodeException("Interrupted", e);
                    }
                    if (b == null) {
                        return false;
                    }
                    try (b) {
                        rows.addAll(Harness.rowsOf(b));
                    }
                    return true;
                }
            };
        }
    }

    static NodeFactories factories() {
        return NodeFactories.discover().register(new Rows()).register(new Collect());
    }

    @Test
    void theCatalogueListsEveryNode() {
        NodeFactories all = NodeFactories.discover();
        Set<String> types = new TreeSet<>();
        for (NodeFactory f : all.all()) {
            types.add(f.type());
            assertFalse(f.label().isBlank());
            assertFalse(f.help().isBlank());
            for (SettingSpec s : f.settings()) {
                assertFalse(s.help().isBlank(), f.type() + "." + s.key());
            }
            assertTrue(f.check(Map.of()).stream().allMatch(p -> p.contains(": ")), f.type());
        }
        assertTrue(types.containsAll(List.of("aggregate", "compute", "dedupe", "filter", "limit", "lookup", "route",
            "select", "union")), types.toString());
        assertEquals(List.of("in*"), all.get("union").orElseThrow().inputPorts());
        assertEquals(List.of("main", "lookup"), all.get("lookup").orElseThrow().inputPorts());
    }

    @Test
    void theDesignExampleRunsEndToEnd() throws Exception {
        String yaml = """
            kilnmark: pipeline/v1
            name: customer-billing
            nodes:
              - id: customers
                type: rows
                table: customers
              - id: transactions
                type: rows
                table: txns
              - id: tidy
                type: compute
                input: customers
                columns:
                  full_name: "trim(first_name) || ' ' || trim(last_name)"
              - id: one_each
                type: dedupe
                input: tidy
                key: [customer_id]
              - id: spend
                type: aggregate
                input: transactions
                group_by: [customer_id]
                values:
                  total: "sum(amount)"
                  n: "count(*)"
              - id: joined
                type: lookup
                inputs: { main: spend, lookup: one_each }
                on: { customer_id: customer_id }
                keep: [full_name]
                unmatched: rejected
              - id: sizes
                type: route
                input: joined
                routes:
                  large: "total >= 40"
              - id: both
                type: union
                input: [sizes.large, sizes]
              - id: firsts
                type: limit
                input: both
                rows: 10
              - id: out
                type: collect
                input: firsts
                name: billing
              - id: orphans
                type: collect
                input: joined.rejected
                name: orphans
            """;
        Compiled c = new PipelineCompiler(factories()).compile(yaml);
        assertTrue(c.ok(), c.problems().toString());
        Schema joined = c.schemas().get("joined").get("out");
        assertEquals(List.of("customer_id", "total", "n", "full_name"),
            joined.getFields().stream().map(f -> f.getName()).toList());
        RunResult r = new KilnGraphRunner(256L << 20, 4).run(c.graph(), e -> { });
        assertEquals(RunResult.Status.SUCCEEDED, r.status(), String.valueOf(r.error()));
        List<Map<String, Object>> billing = Collect.ROWS.get("billing");
        assertEquals(2, billing.size());
        Map<Object, Map<String, Object>> byId = new java.util.HashMap<>();
        billing.forEach(row -> byId.put(row.get("customer_id"), row));
        assertEquals(dec("45.00"), byId.get(1L).get("total"));
        assertEquals("Ada Lovelace", byId.get(1L).get("full_name"));
        assertEquals(dec("22.50"), byId.get(2L).get("total"));
        assertEquals(List.of(3L), Collect.ROWS.get("orphans").stream().map(m -> m.get("customer_id")).toList());
    }

    @Test
    void problemsAreFoundBeforeARun() {
        String yaml = """
            kilnmark: pipeline/v1
            name: broken
            nodes:
              - id: customers
                type: rows
                table: customers
              - id: tidy
                type: compute
                input: customers
                columns:
                  full_name: "upper(frist_name)"
              - id: out
                type: collect
                input: tidy
                name: x
            """;
        Compiled c = new PipelineCompiler(factories()).compile(yaml);
        assertFalse(c.ok());
        assertTrue(c.problems().toString().contains(
            "Node tidy: column full_name: unknown column 'frist_name' at 7; did you mean first_name?"),
            c.problems().toString());
    }

    @Test
    void aFailingNodeNamesItselfWhenRun() throws Exception {
        String yaml = """
            kilnmark: pipeline/v1
            name: zero
            nodes:
              - id: transactions
                type: rows
                table: txns
              - id: ratio
                type: compute
                input: transactions
                columns:
                  r: "amount / (customer_id - 1)"
              - id: out
                type: collect
                input: ratio
                name: zero
            """;
        Compiled c = new PipelineCompiler(factories()).compile(yaml);
        assertTrue(c.ok(), c.problems().toString());
        RunResult r = new KilnGraphRunner(64L << 20, 2).run(c.graph(), e -> { });
        assertEquals(RunResult.Status.FAILED, r.status());
        assertEquals("Node ratio (compute) failed: column r: division by zero at 8; guard it with nullif, like "
            + "x / nullif(y, 0)", r.error());
    }
}

package io.kilnmark.server.pipelines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.SettingSpec;
import io.kilnmark.kiln.Node;
import io.kilnmark.kiln.NodeContext;
import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.NodeFactories;
import io.kilnmark.kiln.NodeFactory;
import io.kilnmark.kiln.NodeKind;
import io.kilnmark.server.auth.AccessRole;

/**
 * Validation as the API returns it: kiln-core's compiler with the server's read and write nodes,
 * mapped to problems with lines and nodes, ports, edges and each node's columns.
 */
class KilnPipelineCompilerTest {

    /** A transform keeping some columns of its input; an unknown column is an error, as real nodes report it. */
    static final class Keep implements NodeFactory {
        @Override
        public String type() {
            return "keep";
        }

        @Override
        public NodeKind kind() {
            return NodeKind.TRANSFORM;
        }

        @Override
        public String label() {
            return "Keep columns";
        }

        @Override
        public String help() {
            return "Keeps the columns listed.";
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
            return List.of(SettingSpec.required("columns", "Columns", SettingSpec.Kind.COLUMNS, "The columns to keep"));
        }

        @Override
        public Node create(Map<String, Object> config) {
            return new Nothing("keep");
        }

        @Override
        public Schema outputSchema(String port, Map<String, Schema> inputs, Map<String, Object> config)
            throws NodeException {
            Schema in = inputs.get("in");
            List<Field> out = new ArrayList<>();
            for (Object c : (List<?>) config.get("columns")) {
                Field f = in.getFields().stream().filter(x -> x.getName().equals(c)).findFirst()
                    .orElseThrow(() -> new NodeException("columns: the input has no column '" + c + "'"));
                out.add(f);
            }
            return new Schema(out);
        }
    }

    record Nothing(String type) implements Node {
        @Override
        public void init(NodeContext context) {
        }

        @Override
        public boolean process(NodeContext context) {
            return false;
        }
    }

    private final FakeSources sources = new FakeSources()
        .add("lab-db", Map.of(), "pw1")
        .add("warehouse", Map.of("allow_writing", "true"), "pw2")
        .asset("urn:kilnmark:lab-db:crm.customers", "customer_id:int64", "email:text", "joined:date");
    private final NodeFactories factories = new NodeFactories().register(new ReadNodeFactory(sources))
        .register(new WriteNodeFactory(sources)).register(new Keep());
    private final KilnPipelineCompiler compiler = new KilnPipelineCompiler();

    static final String GOOD = """
        kilnmark: pipeline/v1
        name: customer-copy
        description: Customers' emails, copied to the warehouse.
        nodes:
          - id: customers
            type: read
            source: lab-db
            asset: crm.customers

          - id: emails
            type: keep
            input: customers
            columns: [customer_id, email]

          - id: out
            type: write
            input: emails
            source: warehouse
            table: crm.customer_emails
            mode: replace
        """;

    @Test
    void aGoodPipelineHasNoProblemsAndKnowsItsColumns() {
        PipelineCompilerPort.Compilation c = compiler.check(GOOD, factories);
        assertEquals(List.of(), c.problems());
        assertEquals("customer-copy", c.name());
        assertEquals("Customers' emails, copied to the warehouse.", c.description());
        assertEquals(List.of("customers", "emails", "out"), c.nodes().stream().map(s -> s.id()).toList());
        assertEquals(List.of(new PipelineCompilerPort.EdgeOutline("customers", "out", "emails", "in"),
            new PipelineCompilerPort.EdgeOutline("emails", "out", "out", "in")), c.edges());
        PipelineCompilerPort.NodeOutline emails = c.nodes().get(1);
        assertEquals("transform", emails.kind());
        assertEquals(10, emails.line());
        assertEquals(List.of("in"), emails.inputs());
        assertEquals(List.of("out", "rejected"), emails.outputs());
        assertEquals(List.of(new PipelineCompilerPort.Column("customer_id", "int64"),
            new PipelineCompilerPort.Column("email", "text")), emails.columns().get("out"));
        assertEquals(List.of("customer_id", "email", "joined"), c.nodes().get(0).columns().get("out").stream()
            .map(PipelineCompilerPort.Column::name).toList());
        assertEquals(Map.of(), c.nodes().get(2).columns(), "a write has no output ports");
        assertEquals("replace", c.configs().get("out").get("mode"));
        assertNull(c.graph(), "checking creates no nodes");
    }

    @Test
    void compilingInsideARunBuildsTheGraph() {
        RunContext run = new RunContext("customer-copy", 1, "jo", AccessRole.STEWARD);
        PipelineCompilerPort.Compilation c = run.run(() -> compiler.compile(GOOD, factories));
        assertEquals(List.of(), c.problems());
        assertNotNull(c.graph());
        assertEquals(List.of("customers", "emails", "out"), c.graph().order());
        assertEquals(List.of("customer_id", "email"), run.writeColumns("warehouse", "crm.customer_emails"),
            "the run knows the columns arriving at each write, for the profile's key-like sketches");
    }

    @Test
    void nodesThatCannotBeCreatedBecomeAProblem() {
        PipelineCompilerPort.Compilation c = compiler.compile(GOOD, factories); // no run context
        assertNull(c.graph());
        assertEquals(1, c.problems().size(), c.problems().toString());
        assertTrue(c.problems().get(0).message().contains("only when its pipeline runs"), c.problems().toString());
    }

    @Test
    void unknownColumnsInReadAssetsAreNotesNotProblems() {
        PipelineCompilerPort.Compilation c = compiler.check(GOOD.replace("crm.customers", "crm.unscanned"), factories);
        assertEquals(List.of(), c.problems());
        assertNull(c.nodes().get(0).columns().get("out"));
        assertTrue(!c.notes().isEmpty(), "a note says the columns are not known until the run");
    }

    private PipelineCompilerPort.Problem only(String yaml) {
        List<PipelineCompilerPort.Problem> p = compiler.check(yaml, factories).problems();
        assertEquals(1, p.size(), p.toString());
        return p.get(0);
    }

    @Test
    void problemsCarryTheirLinesAndNodes() {
        PipelineCompilerPort.Problem typo = only(GOOD.replace("    columns: [customer_id, email]",
            "    columns: [customer_id, email]\n    colums: [email]"));
        assertEquals(14, typo.line());
        assertEquals("emails", typo.node());
        assertTrue(typo.message().contains("colums"), typo.message());

        PipelineCompilerPort.Problem readOnly = only(GOOD.replace("source: warehouse", "source: lab-db"));
        assertEquals(18, readOnly.line(), "the source key of the write node");
        assertEquals("out", readOnly.node());
        assertTrue(readOnly.message().contains("lab-db is read-only"), readOnly.message());

        PipelineCompilerPort.Problem column = only(GOOD.replace("[customer_id, email]", "[customer_id, mail]"));
        assertEquals("emails", column.node());
        assertTrue(column.message().contains("mail"), column.message());

        PipelineCompilerPort.Problem noNode = compiler.check(GOOD.replace("input: customers", "input: customer"),
            factories).problems().get(0);
        assertEquals(12, noNode.line());
        assertTrue(noNode.message().contains("customer"), noNode.message());
    }

    @Test
    void yamlThatCannotBeReadSaysWhere() {
        List<PipelineCompilerPort.Problem> p = compiler.check("kilnmark: pipeline/v1\nname: x\nnodes:\n  - id: a\n"
            + "    type: [read\n", factories).problems();
        assertTrue(!p.isEmpty());
        assertTrue(p.get(0).line() >= 5, p.toString());
    }
}

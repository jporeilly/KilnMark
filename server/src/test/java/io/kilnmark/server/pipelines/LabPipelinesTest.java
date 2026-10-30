package io.kilnmark.server.pipelines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.NodeFactories;
import io.kilnmark.kiln.connect.Connectors;

/**
 * The demo pipelines in lab/pipelines check clean against the lab as lab/sources.csv registers it,
 * with the columns a scan of the lab finds. The lab is never shipped: this only keeps its demos
 * working as the nodes change.
 */
class LabPipelinesTest {

    private static Path lab() {
        return Path.of(System.getProperty("user.dir")).getParent().resolve("lab");
    }

    private static FakeSources labSources() {
        FakeSources s = new FakeSources();
        Connectors all = Connectors.load();
        for (String kind : List.of("postgresql", "s3", "mariadb")) {
            s.connectors.put(kind, all.get(kind).orElseThrow(() -> new AssertionError("no connector " + kind)));
        }
        s.add("lab-db", "postgresql", Map.of("host", "localhost", "port", "5433", "database", "lab",
            "username", "lab", "schemas", "crm;billing"), "lab");
        s.add("lab-mariadb", "mariadb", Map.of("host", "localhost", "port", "3307", "username", "kiln_reader",
            "schemas", "shop;stock"), "Lab-Reader-2026");
        s.add("lab-warehouse", "s3", Map.of("endpoint", "http://localhost:9020", "bucket", "lab-warehouse",
            "access_key_id", "labadmin", "path_style", "true", "allow_writing", "true"), "labadmin123");
        // Columns as lab/postgres/01-schema.sql and lab/mariadb/01-shop.sql declare them.
        s.asset("urn:kilnmark:lab-db:crm.customers", "customer_id:int64", "first_name:text", "last_name:text",
            "email:text", "phone:text", "date_of_birth:date", "postcode:text", "ni_number:text", "segment:text",
            "marketing_opt_in:boolean", "created_at:timestamp_tz");
        s.asset("urn:kilnmark:lab-db:billing.transactions", "txn_id:int64", "account_id:int64", "customer_id:int64",
            "amount:decimal", "currency:text", "txn_at:timestamp_tz", "merchant:text", "card_last4:text");
        s.asset("urn:kilnmark:lab-mariadb:shop.orders", "order_id:int64", "customer_id:int64", "ordered_at:timestamp",
            "status:text", "channel:text", "gift_wrap:boolean", "shipping_fee:decimal", "notes:text");
        s.asset("urn:kilnmark:lab-mariadb:stock.supplier_feed", "sku:text", "supplier:text", "cost_price:decimal",
            "lead_time_days:int16", "in_stock:boolean", "received_on:date");
        return s;
    }

    @Test
    void everyDemoPipelineChecksWithoutProblems() throws Exception {
        FakeSources sources = labSources();
        NodeFactories nodes = NodeFactories.discover().register(new ReadNodeFactory(sources))
            .register(new WriteNodeFactory(sources)).register(new GateNodeFactory(sources));
        List<Path> files;
        try (Stream<Path> list = Files.list(lab().resolve("pipelines"))) {
            files = list.filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
        }
        assertFalse(files.isEmpty(), "lab/pipelines has the demo pipelines");
        for (Path file : files) {
            PipelineCompilerPort.Compilation c = new KilnPipelineCompiler().check(Files.readString(file), nodes);
            assertEquals(List.of(), c.problems(), file.getFileName().toString());
            assertTrue(c.name().startsWith("demo-"), file + ": demo pipelines are named demo-...");
            for (PipelineCompilerPort.NodeOutline node : c.nodes()) {
                if (!"sink".equals(node.kind())) {
                    assertTrue(node.columns().get("out") != null, file + ": the columns leaving " + node.id()
                        + " are known");
                }
            }
        }
    }

    /**
     * The demo flows in lab/flows read without problems, run only demo pipelines, give them only the
     * parameters they take, and pass on (by name) only parameters of the kind the pipeline takes.
     */
    @Test
    void everyDemoFlowFitsTheDemoPipelines() throws Exception {
        Map<String, io.kilnmark.kiln.yaml.PipelineDefinition> pipelines = new java.util.HashMap<>();
        try (Stream<Path> list = Files.list(lab().resolve("pipelines"))) {
            for (Path file : list.filter(p -> p.toString().endsWith(".yaml")).toList()) {
                io.kilnmark.kiln.yaml.PipelineDefinition d = io.kilnmark.kiln.yaml.PipelineDefinition
                    .parse(Files.readString(file)).definition();
                pipelines.put(d.name(), d);
            }
        }
        List<Path> files;
        try (Stream<Path> list = Files.list(lab().resolve("flows"))) {
            files = list.filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
        }
        assertFalse(files.isEmpty(), "lab/flows has the demo flows");
        for (Path file : files) {
            io.kilnmark.kiln.yaml.FlowDefinition.Parsed parsed = io.kilnmark.kiln.yaml.FlowDefinition
                .parse(Files.readString(file));
            assertEquals(List.of(), parsed.problems(), file.getFileName().toString());
            io.kilnmark.kiln.yaml.FlowDefinition flow = parsed.definition();
            assertTrue(flow.name().startsWith("demo-"), file + ": demo flows are named demo-...");
            for (io.kilnmark.kiln.yaml.FlowDefinition.Node n : flow.nodes()) {
                if (!io.kilnmark.kiln.yaml.FlowDefinition.RUN_PIPELINE.equals(n.type())) {
                    continue;
                }
                io.kilnmark.kiln.yaml.PipelineDefinition p = pipelines.get(n.pipeline());
                assertTrue(p != null, file + ": node " + n.id() + " runs " + n.pipeline() + ", not in lab/pipelines");
                for (String key : n.with().keySet()) {
                    assertTrue(p.parameters().stream().anyMatch(x -> x.name().equals(key)),
                        file + ": " + n.pipeline() + " takes no parameter " + key);
                }
                for (io.kilnmark.kiln.yaml.Parameters.Parameter x : p.parameters()) {
                    io.kilnmark.kiln.yaml.Parameters.Parameter mine = flow.parameter(x.name());
                    if (mine != null && !n.with().containsKey(x.name())) {
                        assertEquals(x.kind(), mine.kind(), file + ": " + x.name() + " passed to " + n.pipeline());
                    }
                }
            }
        }
    }
}

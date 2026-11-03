package io.kilnmark.server.pipelines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.NodeFactories;
import io.kilnmark.kiln.connect.Connectors;
import io.kilnmark.kiln.yaml.FlowDefinition;
import io.kilnmark.kiln.yaml.Parameters;
import io.kilnmark.kiln.yaml.PipelineDefinition;

/**
 * The demo pipelines and flows in lab/ and the samples in samples/ check clean against the lab as
 * lab/sources.csv registers it, with the columns a scan of the lab finds. The demos never ship; the
 * samples do, naming their sources by placeholders, which are mapped to the lab's sources here as
 * lab/sample-sources.csv says (as lab.sh samples maps them). This keeps both working as the nodes
 * change.
 */
class LabPipelinesTest {

    /** The server variables the samples name, as the samples' README asks for them to be set. */
    static final Map<String, Object> SAMPLE_VARIABLES = Map.of("sample_region", "EMEA");

    private static Path repo() {
        return Path.of(System.getProperty("user.dir")).getParent();
    }

    /** Placeholder to lab source, from lab/sample-sources.csv. */
    static Map<String, String> labMapping() throws Exception {
        Map<String, String> out = new HashMap<>();
        List<String> lines = Files.readAllLines(repo().resolve("lab/sample-sources.csv"));
        assertEquals("placeholder,lab_source", lines.get(0).strip());
        for (String line : lines.subList(1, lines.size())) {
            if (!line.isBlank()) {
                String[] cols = line.strip().split(",");
                out.put(cols[0], cols[1]);
            }
        }
        return out;
    }

    private static FakeSources labSources() {
        FakeSources s = new FakeSources();
        Connectors all = Connectors.load();
        for (String kind : List.of("postgresql", "s3", "mariadb")) {
            s.connectors.put(kind, all.get(kind).orElseThrow(() -> new AssertionError("no connector " + kind)));
        }
        s.add("lab-db", "postgresql", Map.of("host", "localhost", "port", "5433", "database", "lab",
            "username", "lab", "schemas", "crm;billing", "allow_sql", "true"), "lab");
        s.add("lab-mariadb", "mariadb", Map.of("host", "localhost", "port", "3307", "username", "kiln_reader",
            "schemas", "shop;stock"), "Lab-Reader-2026");
        s.add("lab-warehouse", "s3", Map.of("endpoint", "http://localhost:9020", "bucket", "lab-warehouse",
            "access_key_id", "labadmin", "path_style", "true", "allow_writing", "true"), "labadmin123");
        // Columns as lab/postgres/01-schema.sql and lab/mariadb/01-shop.sql declare them.
        String[] customers = {"customer_id:int64", "first_name:text", "last_name:text", "email:text", "phone:text",
            "date_of_birth:date", "postcode:text", "ni_number:text", "segment:text", "marketing_opt_in:boolean",
            "created_at:timestamp_tz"};
        s.asset("urn:kilnmark:lab-db:crm.customers", customers);
        s.asset("urn:kilnmark:lab-db:crm.customers_archive_2023", customers);
        s.asset("urn:kilnmark:lab-db:crm.accounts", "account_id:int64", "customer_id:int64", "account_type:text",
            "opened_on:date", "status:text");
        s.asset("urn:kilnmark:lab-db:billing.transactions", "txn_id:int64", "account_id:int64", "customer_id:int64",
            "amount:decimal", "currency:text", "txn_at:timestamp_tz", "merchant:text", "card_last4:text");
        s.asset("urn:kilnmark:lab-db:billing.invoices", "invoice_id:int64", "cust_ref:text", "issued_on:date",
            "total:decimal", "paid:boolean");
        s.asset("urn:kilnmark:lab-mariadb:shop.customers", "customer_id:int64", "customer_code:text",
            "first_name:text", "last_name:text", "email:text", "phone:text", "date_of_birth:date", "postcode:text",
            "is_active:boolean", "marketing_opt_in:boolean", "loyalty_tier:text", "created_at:timestamp");
        s.asset("urn:kilnmark:lab-mariadb:shop.orders", "order_id:int64", "customer_id:int64", "ordered_at:timestamp",
            "status:text", "channel:text", "gift_wrap:boolean", "shipping_fee:decimal", "notes:text");
        s.asset("urn:kilnmark:lab-mariadb:shop.order_lines", "order_id:int64", "line_no:int16", "product_id:int32",
            "quantity:int32", "unit_price:decimal");
        s.asset("urn:kilnmark:lab-mariadb:stock.products", "product_id:int32", "sku:text", "name:text",
            "category:text", "tags:text", "attributes:text", "launch_year:int16", "list_price:decimal",
            "discontinued:boolean");
        s.asset("urn:kilnmark:lab-mariadb:stock.supplier_feed", "sku:text", "supplier:text", "cost_price:decimal",
            "lead_time_days:int16", "in_stock:boolean", "received_on:date");
        // Kilnmark's own sample data, which the volume sample reads directly, wherever it runs.
        s.connectors.put("sample-data", all.get("sample-data").orElseThrow());
        s.add(Samples.SAMPLE_DATA, "sample-data", Map.of(), null);
        try {
            for (io.kilnmark.kiln.connect.AssetInfo a : all.get("sample-data").orElseThrow()
                .catalog(new io.kilnmark.kiln.connect.SourceConfig(Samples.SAMPLE_DATA, "sample-data", Map.of(), null))) {
                s.asset("urn:kilnmark:" + Samples.SAMPLE_DATA + ":" + a.path(), a.fields().stream()
                    .map(f -> f.name() + ":" + f.kind()).toArray(String[]::new));
            }
        } catch (io.kilnmark.kiln.connect.ConnectorException e) {
            throw new AssertionError(e);
        }
        return s;
    }

    private static List<Path> yaml(Path dir) throws Exception {
        try (Stream<Path> all = Files.walk(dir)) {
            return all.filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
        }
    }

    private static boolean isFlow(Path file) throws Exception {
        return Files.readString(file).contains("kilnmark: flow/v1");
    }

    /**
     * Every pipeline under {@code dir}, with its source names mapped as {@code mapping} says, checks
     * clean, is named {@code prefix...} and has known columns.
     */
    private static Map<String, PipelineDefinition> checkPipelines(Path dir, String prefix, Map<String, Object> vars,
                                                                  Map<String, String> mapping) throws Exception {
        FakeSources sources = labSources();
        NodeFactories nodes = NodeFactories.discover().register(new ReadNodeFactory(sources))
            .register(new WriteNodeFactory(sources)).register(new GateNodeFactory(sources));
        Map<String, PipelineDefinition> out = new HashMap<>();
        for (Path file : yaml(dir)) {
            if (isFlow(file)) {
                continue;
            }
            String text = Samples.withSources(Files.readString(file), mapping);
            PipelineCompilerPort.Compilation c = new KilnPipelineCompiler().check(text, nodes, Map.of(), vars);
            assertEquals(List.of(), c.problems(), file.toString());
            assertTrue(c.name().startsWith(prefix), file + ": named " + prefix + "...");
            assertTrue(out.put(c.name(), PipelineDefinition.parse(text).definition()) == null, file + ": name used twice");
            for (PipelineCompilerPort.NodeOutline node : c.nodes()) {
                // a query's columns are known when it runs (ADR 0028)
                if (!"sink".equals(node.kind()) && !ReadNodeFactory.isQuery(c.configs().getOrDefault(node.id(),
                    Map.of()))) {
                    assertTrue(node.columns().get("out") != null, file + ": the columns leaving " + node.id()
                        + " are known");
                }
            }
        }
        assertFalse(out.isEmpty(), dir + " has pipelines");
        return out;
    }

    /**
     * Every flow under {@code dir} reads without problems, runs only the given pipelines, gives them
     * only the parameters they take, and passes on (by name) only parameters of the kind they take.
     */
    private static void checkFlows(Path dir, String prefix, Map<String, PipelineDefinition> pipelines)
        throws Exception {
        int flows = 0;
        for (Path file : yaml(dir)) {
            if (!isFlow(file)) {
                continue;
            }
            flows++;
            FlowDefinition.Parsed parsed = FlowDefinition.parse(Files.readString(file));
            assertEquals(List.of(), parsed.problems(), file.toString());
            FlowDefinition flow = parsed.definition();
            assertTrue(flow.name().startsWith(prefix), file + ": named " + prefix + "...");
            for (FlowDefinition.Node n : flow.nodes()) {
                if (!FlowDefinition.RUN_PIPELINE.equals(n.type())) {
                    continue;
                }
                PipelineDefinition p = pipelines.get(n.pipeline());
                assertTrue(p != null, file + ": node " + n.id() + " runs " + n.pipeline() + ", which is not there");
                for (String key : n.with().keySet()) {
                    assertTrue(p.parameters().stream().anyMatch(x -> x.name().equals(key)),
                        file + ": " + n.pipeline() + " takes no parameter " + key);
                }
                for (Parameters.Parameter x : p.parameters()) {
                    Parameters.Parameter mine = flow.parameter(x.name());
                    if (mine != null && !n.with().containsKey(x.name())) {
                        assertEquals(x.kind(), mine.kind(), file + ": " + x.name() + " passed to " + n.pipeline());
                    }
                }
            }
        }
        assertTrue(flows > 0, dir + " has flows");
    }

    @Test
    void everyDemoPipelineAndFlowChecksWithoutProblems() throws Exception {
        Map<String, PipelineDefinition> demos = checkPipelines(repo().resolve("lab/pipelines"), "demo-", Map.of(),
            Map.of());
        checkFlows(repo().resolve("lab/flows"), "demo-", demos);
    }

    @Test
    void everySampleChecksWithoutProblems() throws Exception {
        Map<String, PipelineDefinition> samples = checkPipelines(repo().resolve("samples"), "sample-",
            SAMPLE_VARIABLES, labMapping());
        checkFlows(repo().resolve("samples"), "sample-", samples);
    }

    /** The samples ship: they name only the placeholders, every one of which the lab maps, and never the lab. */
    @Test
    void theSamplesNameOnlyPlaceholders() throws Exception {
        List<String> placeholders = Samples.PLACEHOLDERS.stream().map(Samples.Placeholder::name).toList();
        assertEquals(new java.util.HashSet<>(placeholders), labMapping().keySet());
        java.util.regex.Pattern lab = java.util.regex.Pattern.compile("(?i)(?<![a-z])lab(?![a-z])");
        for (Path file : yaml(repo().resolve("samples"))) {
            String text = Files.readString(file);
            // Every source line is one the mapping rewrites, and names a placeholder.
            assertEquals(text.lines().filter(l -> l.strip().matches("(- +)?source:.*")).count(),
                Samples.SOURCE_LINE.matcher(text).results().count(), file + ": a source line the mapping misses");
            java.util.regex.Matcher m = Samples.SOURCE_LINE.matcher(text);
            while (m.find()) {
                // A placeholder, or Kilnmark's own sample data (which ships, and is the same everywhere).
                assertTrue(placeholders.contains(m.group(3)) || Samples.SAMPLE_DATA.equals(m.group(3)),
                    file + ": source " + m.group(3) + " is not a placeholder");
            }
            assertFalse(lab.matcher(text).find(), file + ": mentions the lab, but the samples ship");
            if (!isFlow(file)) {
                assertFalse(Samples.sourcesOf(text).isEmpty(), file + ": a pipeline names placeholders");
            } else {
                // A flow names a source only for a sql node to run on (ADR 0028).
                FlowDefinition flow = FlowDefinition.parse(text).definition();
                List<String> sql = flow.nodes().stream().filter(n -> FlowDefinition.SQL.equals(n.type()))
                    .map(FlowDefinition.Node::source).distinct().toList();
                assertEquals(sql, Samples.sourcesOf(text), file + ": a flow names sources only in sql nodes");
            }
        }
    }

    /** What each read sample says it shows: who applies its where (docs/guide/pipelines.md, the read node). */
    @Test
    void theReadSamplesSayWhoAppliesTheirCondition() throws Exception {
        FakeSources sources = labSources();
        NodeFactories nodes = NodeFactories.discover().register(new ReadNodeFactory(sources))
            .register(new WriteNodeFactory(sources));
        Map<String, String> expected = Map.of(
            "01-read-and-write/read-columns-where.yaml", "where is applied in part by the database",
            "07-parameters/orders-by-status.yaml", "where is applied by the database while reading");
        for (var e : expected.entrySet()) {
            String text = Samples.withSources(Files.readString(repo().resolve("samples").resolve(e.getKey())),
                labMapping());
            PipelineCompilerPort.Compilation c = new KilnPipelineCompiler().check(text, nodes, Map.of(), Map.of());
            assertTrue(c.notes().stream().anyMatch(n -> n.message().contains(e.getValue())),
                e.getKey() + ": " + c.notes());
        }
    }
}

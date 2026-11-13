package io.kilnmark.server.pipelines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.NodeFactories;
import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.Connectors;
import io.kilnmark.kiln.connect.SourceConfig;

/**
 * The samples work on Kilnmark's own sample data, not only on the lab: every sample, pointed at
 * the sample tables as Add samples points it (one schema, crm_customers and so on), checks without
 * problems against the columns the sample data really has, and the pipeline that creates the
 * tables checks too.
 */
class SampleTablesTest {

    static final String SCHEMA = "kilnmark_samples";

    /** The sample data's tables, as its connector catalogues them. */
    private static List<AssetInfo> sampleData() throws Exception {
        Connector c = Connectors.load().get("sample-data").orElseThrow();
        return c.catalog(new SourceConfig(Samples.SAMPLE_DATA, "sample-data", Map.of(), null));
    }

    private static String[] columns(AssetInfo a) {
        return a.fields().stream().map(f -> f.name() + ":" + f.kind()).toArray(String[]::new);
    }

    /** A database holding the sample tables in {@link #SCHEMA}, the sample data itself, and somewhere to write. */
    private static FakeSources sources() throws Exception {
        FakeSources s = new FakeSources();
        Connectors all = Connectors.load();
        for (String kind : List.of("postgresql", "s3", "sample-data")) {
            s.connectors.put(kind, all.get(kind).orElseThrow());
        }
        s.add("my-db", "postgresql", Map.of("url", "jdbc:postgresql://db.example.com:5432/app", "username", "app",
            "schemas", SCHEMA, "allow_writing", "true", "allow_sql", "true"), "secret");
        s.add("my-bucket", "s3", Map.of("endpoint", "https://objects.example.com", "bucket", "exports",
            "access_key_id", "key", "allow_writing", "true"), "secret");
        s.add(Samples.SAMPLE_DATA, "sample-data", Map.of(), null);
        for (AssetInfo a : sampleData()) {
            String table = a.path().replace(".csv", "").replace('/', '.');
            s.asset("urn:kilnmark:my-db:" + Samples.tableIn(SCHEMA, table), columns(a));
            s.asset("urn:kilnmark:" + Samples.SAMPLE_DATA + ":" + a.path(), columns(a));
        }
        return s;
    }

    private static NodeFactories nodes(FakeSources s) {
        return NodeFactories.discover().register(new ReadNodeFactory(s)).register(new WriteNodeFactory(s))
            .register(new GateNodeFactory(s));
    }

    @Test
    void theTablesAreTheSampleDataAndWhatTheSamplesRead() throws Exception {
        assertEquals(Samples.TABLES.stream().sorted().toList(),
            sampleData().stream().map(a -> a.path().replace(".csv", "").replace('/', '.'))
                .filter(t -> !t.startsWith("volume.")).sorted().toList());
        for (Samples.Sample sample : Samples.bundled()) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^\\s*asset:\\s*(\\S+)")
                .matcher(sample.yaml());
            while (m.find()) {
                if (m.group(1).contains("/")) {
                    continue; // a file of the sample data itself, read directly (the volume sample)
                }
                assertTrue(Samples.TABLES.contains(m.group(1)), sample.file() + " reads " + m.group(1)
                    + ", which is not in the sample data");
            }
        }
    }

    @Test
    void everySampleChecksAgainstTheSampleTables() throws Exception {
        FakeSources s = sources();
        Map<String, String> mapping = Map.of("crm-db", "my-db", "shop-db", "my-db", "files", "my-bucket");
        // First what each pipeline writes, for those that read what another writes (the scenarios).
        for (int pass = 0; pass < 3; pass++) {
            for (Samples.Sample sample : Samples.bundled()) {
                if ("pipeline".equals(sample.type())) {
                    String yaml = Samples.withTables(Samples.withSources(sample.yaml(), mapping), SCHEMA);
                    LabPipelinesTest.recordWrites(s, yaml, new KilnPipelineCompiler().check(yaml, nodes(s), Map.of(),
                        LabPipelinesTest.SAMPLE_VARIABLES));
                }
            }
        }
        int checked = 0;
        for (Samples.Sample sample : Samples.bundled()) {
            if (!"pipeline".equals(sample.type())) {
                continue;
            }
            String yaml = Samples.withTables(Samples.withSources(sample.yaml(), mapping), SCHEMA);
            assertFalse(yaml.contains("asset: crm.") || yaml.contains("asset: shop."), yaml);
            assertFalse(yaml.contains(" crm.") || yaml.contains(" shop."), yaml); // queries too
            PipelineCompilerPort.Compilation c = new KilnPipelineCompiler().check(yaml, nodes(s), Map.of(),
                LabPipelinesTest.SAMPLE_VARIABLES);
            assertEquals(List.of(), c.problems(), sample.file());
            for (PipelineCompilerPort.NodeOutline node : c.nodes()) {
                // a query's columns are known when it runs (ADR 0028)
                if (!"sink".equals(node.kind()) && !ReadNodeFactory.isQuery(c.configs().getOrDefault(node.id(),
                    Map.of()))) {
                    assertTrue(node.columns().get("out") != null, sample.file() + ": the columns leaving "
                        + node.id() + " are known");
                }
            }
            checked++;
        }
        assertEquals(34, checked);
    }

    /**
     * Every sample flow, pointed at the sample tables as Add samples points it, passes what Run now
     * checks before it queues a run ({@code FlowService.validate}): it reads without problems, each
     * source its sql, wait-for and check-source nodes name allows what the node does, each sql
     * statement and wait-for query is one Kilnmark runs, and each pipeline it runs is a sample that
     * takes what the flow gives it. A sample that cannot run fails here: sample-sql-before-and-after
     * shipped in 0.1.2 with a statement its own sql node refused (GROUP BY).
     */
    @Test
    void everySampleFlowPassesWhatRunNowChecks() throws Exception {
        FakeSources s = sources();
        Map<String, String> mapping = Map.of("crm-db", "my-db", "shop-db", "my-db", "files", "my-bucket");
        Map<String, io.kilnmark.kiln.yaml.PipelineDefinition> pipelines = new java.util.HashMap<>();
        for (Samples.Sample sample : Samples.bundled()) {
            if ("pipeline".equals(sample.type())) {
                pipelines.put(sample.name(), io.kilnmark.kiln.yaml.PipelineDefinition.parse(
                    Samples.withTables(Samples.withSources(sample.yaml(), mapping), SCHEMA)).definition());
            }
        }
        int checked = 0;
        int statements = 0;
        for (Samples.Sample sample : Samples.bundled()) {
            if (!"flow".equals(sample.type())) {
                continue;
            }
            String yaml = Samples.withTables(Samples.withSources(sample.yaml(), mapping), SCHEMA);
            io.kilnmark.kiln.yaml.FlowDefinition.Parsed parsed = io.kilnmark.kiln.yaml.FlowDefinition.parse(yaml);
            assertEquals(List.of(), parsed.problems(), sample.file());
            assertEquals(List.of(), io.kilnmark.server.flows.FlowSourceChecks.problems(parsed.definition(), s),
                sample.file() + ": a source, statement or query Run now would refuse");
            assertEquals(List.of(), io.kilnmark.server.flows.FlowSourceChecks.pipelineProblems(parsed.definition(),
                pipelines), sample.file() + ": a pipeline it runs");
            statements += parsed.definition().nodes().stream()
                .filter(n -> io.kilnmark.kiln.yaml.FlowDefinition.SQL.equals(n.type()))
                .mapToInt(n -> n.statements().size()).sum();
            checked++;
        }
        assertEquals(9, checked);
        // The check looked at SQL: the two samples with sql nodes hold five statements between them.
        assertEquals(5, statements);
    }

    @Test
    void thePipelineCreatingTheTablesChecks() throws Exception {
        String yaml = Samples.setupYaml("my-db", SCHEMA);
        assertTrue(yaml.contains("name: " + Samples.SETUP + "\n"), yaml);
        assertTrue(yaml.contains("    asset: crm/customers.csv\n") && yaml.contains("    table: " + SCHEMA
            + ".crm_customers\n") && yaml.contains("    mode: replace\n"), yaml);
        PipelineCompilerPort.Compilation c = new KilnPipelineCompiler().check(yaml, nodes(sources()), Map.of(),
            Map.of());
        assertEquals(List.of(), c.problems());
        assertEquals(2 * Samples.TABLES.size(), c.nodes().size());
    }

    @Test
    void onlyTheSampleTablesAreRepointed() {
        String yaml = """
            nodes:
              - id: a
                asset: crm.customers   # the customers
              - id: b
                asset: crm.other
              - id: c
                asset: sales.orders
            """;
        String out = Samples.withTables(yaml, "s1");
        assertTrue(out.contains("    asset: s1.crm_customers   # the customers\n"), out);
        assertTrue(out.contains("    asset: crm.other\n") && out.contains("    asset: sales.orders\n"), out);
    }
}

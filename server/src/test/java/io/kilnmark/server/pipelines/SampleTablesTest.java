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
        assertEquals(21, checked);
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
        assertEquals(20, c.nodes().size());
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

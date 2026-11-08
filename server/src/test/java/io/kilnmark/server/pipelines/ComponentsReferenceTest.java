package io.kilnmark.server.pipelines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.kilnmark.kiln.NodeFactories;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.nodes.expr.Expression;
import io.kilnmark.kiln.yaml.FlowDefinition;
import io.kilnmark.kiln.yaml.PipelineDefinition;
import io.kilnmark.server.flows.FlowSourceChecks;
import io.kilnmark.server.quality.RuleDraft;
import io.kilnmark.server.quality.RuleType;

/**
 * The components reference (docs/guide/components-reference.md and reference-*.md) against the
 * catalogue the server uses: every node type, flow node type, connector and quality rule type has an
 * entry; each entry's generated part ({@link ComponentsReference}) is what the catalogue makes now, so
 * a setting added without running {@code ./gradlew :server:writeReference} fails here; the settings
 * tables written by hand name exactly the keys the readers take; every example is checked as saving
 * checks it; and the versions tested are those the integration tests use.
 */
class ComponentsReferenceTest {

    private static final Path REPO = Path.of(System.getProperty("user.dir")).getParent();
    private static final Path GUIDES = REPO.resolve("docs/guide");
    /** Set by {@code ./gradlew :server:writeReference}: write the generated parts instead of comparing them. */
    private static final boolean WRITE = Boolean.getBoolean("kmtest.reference.write");
    private static final Pattern FENCE = Pattern.compile("(?ms)^```(yaml|json)\\r?\\n(.*?)^```");
    private static final ComponentsReference REF = new ComponentsReference();

    private static String read(String page) throws Exception {
        return Files.readString(GUIDES.resolve(page), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    void everyComponentHasAnEntryAndItsGeneratedPartIsCurrent() throws Exception {
        Map<String, ComponentsReference.Part> parts = REF.parts();
        List<String> problems = new ArrayList<>();
        for (String page : ComponentsReference.PAGES) {
            String text = read(page);
            Map<String, String> on = ComponentsReference.partsOn(text);
            Map<String, String> fresh = new HashMap<>();
            for (Map.Entry<String, ComponentsReference.Part> e : parts.entrySet()) {
                if (!e.getValue().page().equals(page)) {
                    continue;
                }
                String name = e.getKey();
                if (!on.containsKey(name)) {
                    problems.add(page + " has no entry for " + name + ": add one, with the lines ["
                        + "generated: " + name + "]: # and " + ComponentsReference.end(name) + " where its settings go");
                } else if (!on.get(name).equals(e.getValue().text())) {
                    fresh.put(name, e.getValue().text());
                }
            }
            for (String name : on.keySet()) {
                ComponentsReference.Part p = parts.get(name);
                if (p == null || !p.page().equals(page)) {
                    problems.add(page + " has an entry for " + name + ", which " + (p == null
                        ? "is no component this server has" : "belongs on " + p.page()));
                }
            }
            if (!fresh.isEmpty()) {
                if (WRITE) {
                    Files.writeString(GUIDES.resolve(page), ComponentsReference.withParts(text, fresh),
                        StandardCharsets.UTF_8);
                } else {
                    problems.add(page + ": out of date for " + new TreeSet<>(fresh.keySet()) + ". Run ./gradlew "
                        + ":server:writeReference and commit the pages");
                }
            }
        }
        assertEquals(List.of(), problems);
    }

    /**
     * Every setting's help, as the panel and the reference show it, reads as a sentence: it starts
     * with a capital, not with a value such as "create fails if ..." or "false keeps ...".
     */
    @Test
    void everySettingsHelpStartsAsASentence() {
        List<String> lower = new ArrayList<>();
        for (Map.Entry<String, ComponentsReference.Part> e : REF.parts().entrySet()) {
            for (String row : e.getValue().text().split("\n")) {
                if (!row.startsWith("| `")) {
                    continue;
                }
                String[] cells = row.split("(?<!\\\\)\\|");
                String help = cells[cells.length - 1].trim();
                if (!help.isEmpty() && Character.isLowerCase(help.charAt(0))) {
                    lower.add(e.getKey() + ": " + help);
                }
            }
        }
        assertEquals(List.of(), lower);
    }

    /** The text of the section (from its ## heading to the next) holding the named generated part. */
    private static String section(String page, String part) {
        int at = page.indexOf("[generated: " + part + "]: #");
        assertTrue(at >= 0, "no part " + part);
        int start = page.lastIndexOf("\n## ", at);
        int end = page.indexOf("\n## ", at);
        return page.substring(start < 0 ? 0 : start, end < 0 ? page.length() : end);
    }

    @Test
    void everyEntryHasAnExample() throws Exception {
        for (Map.Entry<String, ComponentsReference.Part> e : REF.parts().entrySet()) {
            if (e.getKey().startsWith("index ") || e.getKey().equals("source settings")) {
                continue;
            }
            String s = section(read(e.getValue().page()), e.getKey());
            String fence = e.getKey().startsWith("rule ") ? "```json" : "```yaml";
            assertTrue(s.contains("\n" + fence + "\n"), e.getKey() + " has no " + fence.substring(3) + " example");
        }
    }

    @Test
    void eachFlowNodeTableNamesEverySettingItTakes() throws Exception {
        String page = read(ComponentsReference.FLOW_NODES);
        for (String type : FlowDefinition.TYPES) {
            Set<String> documented = firstColumn(section(page, "flow-node " + type), "| Setting |");
            Set<String> expected = new TreeSet<>(FlowDefinition.TYPE_KEYS.get(type));
            assertEquals(expected, documented, "the settings table of " + type);
        }
    }

    @Test
    void eachRuleTableNamesTheKeysAGateTakes() throws Exception {
        String page = read(ComponentsReference.RULES);
        for (RuleType t : RuleType.values()) {
            Map<String, String> keys = GateRules.PARAMS.get(t);
            if (keys == null) {
                continue;
            }
            String s = section(page, "rule " + t.id());
            Map<String, String> documented = new TreeMap<>();
            for (List<String> row : rows(s, "| On a gate |")) {
                documented.put(code(row.get(0)), code(row.get(1)));
            }
            assertEquals(new TreeMap<>(keys), documented, "the settings table of " + t.id());
        }
    }

    @Test
    void everyExpressionFunctionIsInTheGuide() throws Exception {
        // The first cells of the tables headed "| Function |": each names one function or more, as `f(x)`.
        Set<String> documented = new TreeSet<>();
        Pattern call = Pattern.compile("`([a-z_][a-z0-9_]*)\\(");
        boolean inTable = false;
        for (String line : read(ComponentsReference.EXPRESSIONS).split("\n")) {
            if (line.startsWith("| Function |")) {
                inTable = true;
            } else if (!line.startsWith("|")) {
                inTable = false;
            } else if (inTable) {
                Matcher m = call.matcher(line.substring(1, Math.max(1, line.indexOf(" | ", 1))));
                while (m.find()) {
                    documented.add(m.group(1));
                }
            }
        }
        Set<String> expected = new TreeSet<>(Expression.functions());
        expected.addAll(Expression.aggregates());
        expected.addAll(Expression.windowFunctions());
        assertEquals(expected, documented, "the functions in " + ComponentsReference.EXPRESSIONS + "'s tables");
    }

    /**
     * The drivers guide names each driver Kilnmark ships at the version the build ships
     * (gradle/libs.versions.toml), and every connector that ships one is in its table.
     */
    @Test
    void theDriversGuideNamesTheDriversShippedAtTheirVersions() throws Exception {
        String toml = Files.readString(REPO.resolve("gradle/libs.versions.toml"), StandardCharsets.UTF_8);
        List<List<String>> table = rows(read(ComponentsReference.DRIVERS), "| Driver | Version | Licence | Used by |");
        Map<String, String> shipped = Map.of("postgresql", "the PostgreSQL JDBC driver", "mssql-jdbc",
            "the Microsoft JDBC Driver for SQL Server", "mariadb-java-client", "MariaDB Connector/J");
        for (Map.Entry<String, String> e : shipped.entrySet()) {
            Matcher m = Pattern.compile("(?m)^" + Pattern.quote(e.getKey()) + " = \"([^\"]+)\"").matcher(toml);
            assertTrue(m.find(), "no version of " + e.getKey() + " in libs.versions.toml");
            String version = m.group(1);
            assertTrue(table.stream().anyMatch(r -> r.get(0).contains(e.getValue().replace("the ", ""))
                && code(r.get(1)).equals(version)), ComponentsReference.DRIVERS + " does not list " + e.getValue()
                + " at " + version + ": " + table);
        }
        for (Map.Entry<String, String> e : ComponentsReference.SHIPPED_DRIVERS.entrySet()) {
            if (!e.getValue().isEmpty()) {
                String name = e.getValue().replaceAll(" \\(.*$", "").replace("the ", "");
                assertTrue(table.stream().anyMatch(r -> r.get(0).contains(name) && r.get(3).contains(e.getKey())),
                    ComponentsReference.DRIVERS + " does not say " + name + " is used by " + e.getKey());
            }
        }
    }

    @Test
    void theVersionsTestedAreTheOnesTheTestsUse() throws Exception {
        String page = read(ComponentsReference.CONNECTORS);
        Pattern image = Pattern.compile("\"((?:docker\\.io|mcr\\.microsoft\\.com|ghcr\\.io)/[\\w./-]+:[\\w.-]+)\"");
        Set<String> images = new TreeSet<>();
        try (Stream<Path> files = Files.walk(REPO.resolve("kiln-plugins/src/test/java/io/kilnmark/kiln/plugins"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = image.matcher(Files.readString(f));
                while (m.find()) {
                    images.add(m.group(1));
                }
            }
        }
        assertTrue(images.size() >= 5, "the integration tests' images: " + images);
        for (String i : images) {
            assertTrue(page.contains("`" + i + "`"), ComponentsReference.CONNECTORS + " does not name the image the "
                + "tests use, " + i + ", under Versions tested");
        }
        Set<String> named = new TreeSet<>();
        for (List<String> row : rows(page, "| Connector | Tested against | Image |")) {
            if (row.get(2).startsWith("`")) {
                named.add(code(row.get(2)));
            }
        }
        assertEquals(images, named, "the images under Versions tested, against those the tests use");
    }

    @Test
    void theIndexAndTheRulePageNameEveryFlowNodeAndCommonKey() throws Exception {
        String index = read(ComponentsReference.INDEX);
        Set<String> flowNodes = new TreeSet<>();
        for (List<String> row : rows(index, "| Node | What it does |")) {
            flowNodes.add(row.get(0).replaceAll("^\\[`([^`]+)`\\].*$", "$1"));
        }
        assertEquals(new TreeSet<>(FlowDefinition.TYPES), flowNodes, "the flow nodes in " + ComponentsReference.INDEX);
        Set<String> common = new TreeSet<>();
        for (List<String> row : rows(read(ComponentsReference.RULES), "| On a gate | In the API | Type | Default |")) {
            if (!row.get(0).isEmpty()) {
                common.add(code(row.get(0)));
            }
        }
        assertEquals(new TreeSet<>(GateRules.COMMON), common, "the keys every rule has on a gate");
    }

    // ---- the examples ----

    /** The sources the examples name, with the columns of the tables and files they read. */
    static FakeSources exampleSources() {
        FakeSources s = new FakeSources();
        s.connectors.clear();
        REF.connectors.forEach(c -> s.connectors.put(c.kind(), c));
        Map<String, String> rw = Map.of("allow_writing", "true");
        s.add("sales-db", "postgresql", Map.of("host", "db.example.com", "database", "sales", "username", "kiln",
            "allow_sql", "true", "allow_writing", "true"), "x");
        s.add("warehouse", "postgresql", Map.of("host", "dw.example.com", "database", "dw", "username", "kiln",
            "allow_sql", "true", "allow_writing", "true"), "x");
        s.add("finance-db", "sqlserver", with(rw, "host", "finance.example.com"), "x");
        s.add("shop-db", "mysql", with(rw, "host", "shop.example.com"), "x");
        s.add("stock-db", "mariadb", with(rw, "host", "stock.example.com"), "x");
        s.add("ledger-db", "oracle", with(rw, "host", "ledger.example.com"), "x");
        Map<String, String> legacy = new HashMap<>(with(rw, "url", "jdbc:example://legacy.example.com/db"));
        legacy.put("read_only", "false");
        s.add("legacy-db", "jdbc", legacy, "x");
        s.add("files", "files", with(rw, "path", "/data/kilnmark"), null);
        s.add("landing", "files", Map.of("path", "/data/landing"), null);
        s.add("lake", "s3", with(rw, "bucket", "lake"), "x");
        s.add("sample-data", "sample-data", Map.of(), null);
        String[] customers = {"customer_id:int64", "first_name:text", "last_name:text", "email:text", "country:text",
            "status:text", "balance:decimal", "created_at:timestamp_tz"};
        String[] orders = {"order_id:int64", "customer_id:int64", "amount:decimal", "country:text", "status:text",
            "ordered_at:timestamp_tz"};
        for (String source : List.of("sales-db", "warehouse", "finance-db", "shop-db", "stock-db", "ledger-db",
            "legacy-db")) {
            s.asset("urn:kilnmark:" + source + ":crm.customers", customers);
            s.asset("urn:kilnmark:" + source + ":sales.orders", orders);
        }
        s.asset("urn:kilnmark:sales-db:crm.customers_archive", customers);
        s.asset("urn:kilnmark:sales-db:crm.countries", "country:text", "country_name:text", "region:text");
        for (String source : List.of("files", "landing", "lake")) {
            s.asset("urn:kilnmark:" + source + ":incoming/orders.csv", orders);
            s.asset("urn:kilnmark:" + source + ":incoming/customers.parquet", customers);
        }
        try {
            Connector sample = REF.connectors.stream().filter(c -> c.kind().equals("sample-data")).findFirst()
                .orElseThrow();
            for (io.kilnmark.kiln.connect.AssetInfo a : sample.catalog(new io.kilnmark.kiln.connect.SourceConfig(
                "sample-data", "sample-data", Map.of(), null))) {
                s.asset("urn:kilnmark:sample-data:" + a.path(), a.fields().stream().map(f -> f.name() + ":" + f.kind())
                    .toArray(String[]::new));
            }
        } catch (io.kilnmark.kiln.connect.ConnectorException e) {
            throw new AssertionError(e);
        }
        return s;
    }

    private static Map<String, String> with(Map<String, String> base, String key, String value) {
        Map<String, String> out = new HashMap<>(base);
        out.put(key, value);
        return out;
    }

    @Test
    void everyExampleIsValidAsSavingChecksIt() throws Exception {
        FakeSources sources = exampleSources();
        NodeFactories nodes = NodeFactories.discover().register(new ReadNodeFactory(sources))
            .register(new WriteNodeFactory(sources)).register(new GateNodeFactory(sources));
        Map<String, PipelineDefinition> pipelines = new LinkedHashMap<>();
        List<String[]> flows = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (String page : ComponentsReference.PAGES) {
            Matcher m = FENCE.matcher(read(page));
            while (m.find()) {
                String where = page + " line " + read(page).substring(0, m.start()).split("\n", -1).length;
                String text = m.group(2);
                checked++;
                if (m.group(1).equals("json")) {
                    problems.addAll(rule(where, text));
                } else if (text.contains("kilnmark: pipeline/v1")) {
                    PipelineCompilerPort.Compilation c = new KilnPipelineCompiler().check(text, nodes, Map.of(), Map.of());
                    c.problems().forEach(p -> problems.add(where + ": " + p));
                    if (c.problems().isEmpty()) {
                        for (PipelineCompilerPort.NodeOutline n : c.nodes()) {
                            if (!"sink".equals(n.kind()) && !ReadNodeFactory.isQuery(c.configs().getOrDefault(n.id(),
                                Map.of())) && n.columns().get("out") == null) {
                                problems.add(where + ": the columns leaving " + n.id() + " are not known: give its "
                                    + "source's table or file columns in exampleSources");
                            }
                        }
                        if (pipelines.put(c.name(), PipelineDefinition.parse(text).definition()) != null) {
                            problems.add(where + ": another example is called " + c.name() + " too");
                        }
                    }
                } else if (text.contains("kilnmark: flow/v1")) {
                    flows.add(new String[] {where, text});
                } else {
                    problems.add(where + ": every YAML example is a whole pipeline or flow, so it can be checked");
                }
            }
        }
        for (String[] f : flows) {
            problems.addAll(flow(f[0], f[1], pipelines, sources));
        }
        assertEquals(List.of(), problems);
        assertTrue(checked > 40, "examples checked: " + checked);
    }

    /** What saving would say about a flow, with the examples' pipelines as the saved ones. */
    private static List<String> flow(String where, String yaml, Map<String, PipelineDefinition> pipelines,
                                     FakeSources sources) {
        List<String> out = new ArrayList<>();
        FlowDefinition.Parsed parsed = FlowDefinition.parse(yaml);
        parsed.problems().forEach(p -> out.add(where + ": " + p));
        if (!parsed.valid()) {
            return out;
        }
        FlowSourceChecks.problems(parsed.definition(), sources).forEach(p -> out.add(where + ": " + p));
        FlowSourceChecks.pipelineProblems(parsed.definition(), pipelines).forEach(p -> out.add(where + ": " + p));
        return out;
    }

    /** A rule as POST /api/v1/quality/rules takes it, checked as the server checks it. */
    private static List<String> rule(String where, String json) {
        try {
            RuleDraft d = new ObjectMapper().readValue(json, RuleDraft.class);
            d.check(new RuleDraft.Context() {
                @Override
                public Map<String, String> fields() {
                    return Map.of("customer_id", "int64", "email", "text", "country", "text", "status", "text",
                        "amount", "decimal", "ordered_at", "timestamp_tz", "order_id", "int64", "postcode", "text");
                }

                @Override
                public String sourceKind() {
                    return "postgresql";
                }

                @Override
                public boolean sql() {
                    return true;
                }

                @Override
                public Optional<Map<String, String>> fieldsOf(String urn) {
                    return urn.equals("urn:kilnmark:sales-db:crm.customers")
                        ? Optional.of(Map.of("customer_id", "int64", "email", "text")) : Optional.empty();
                }

                @Override
                public List<String> webhookAllowList() {
                    return List.of();
                }

                @Override
                public boolean customSqlEnabled() {
                    return true;
                }
            }, "reference");
            return List.of();
        } catch (Exception e) {
            return List.of(where + ": " + e.getMessage());
        }
    }

    // ---- tables written by hand ----

    /** The rows (cells, trimmed) of the first table in {@code text} whose header starts {@code header}. */
    private static List<List<String>> rows(String text, String header) {
        int at = text.indexOf("\n" + header);
        if (at < 0) {
            fail("no table starting " + header + " in:\n" + text);
        }
        List<List<String>> out = new ArrayList<>();
        String[] lines = text.substring(at + 1).split("\n");
        for (int i = 2; i < lines.length && lines[i].startsWith("|"); i++) {
            List<String> cells = new ArrayList<>();
            for (String c : lines[i].substring(1).split("(?<!\\\\)\\|")) {
                cells.add(c.strip());
            }
            out.add(cells);
        }
        return out;
    }

    private static Set<String> firstColumn(String text, String header) {
        Set<String> out = new TreeSet<>();
        rows(text, header).forEach(r -> out.add(code(r.get(0))));
        return out;
    }

    /** A cell's text without the backticks around it. */
    private static String code(String cell) {
        return cell.replaceAll("^`|`$", "");
    }
}

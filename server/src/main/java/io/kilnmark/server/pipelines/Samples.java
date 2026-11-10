package io.kilnmark.server.pipelines;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.jboss.logging.Logger;

import io.kilnmark.graph.store.GraphStore;
import io.kilnmark.server.flows.FlowService;
import io.kilnmark.server.flows.FlowStore;
import io.kilnmark.graph.store.Records;
import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.quality.Sights;
import io.kilnmark.server.variables.VariableService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/**
 * The samples that ship with the server: the small pipelines and flows in {@code samples/},
 * copied into the server's resources at build time (server/build.gradle.kts) with a list of them.
 * They name their sources by placeholders ({@link #PLACEHOLDERS}), never a real source; adding them
 * maps each placeholder to one of the person's saved sources. A sample already there is left as it
 * is, so changes made to it are kept. Pipelines and flows added from here are marked as samples
 * ({@link #LIBRARY}): the Library page lists them, and the Pipelines and Flows pages, which hold a
 * team's own work, do not (ADR 0043).
 *
 * <p>The tables the samples read come with them: Kilnmark's sample data ({@code samples/data}, the
 * {@code sample-data} source kind). {@link #createTables} copies them into one schema of a database
 * the person chooses, with a pipeline of its own ({@value #SETUP}), named {@code <group>_<table>}
 * (crm_customers, shop_orders, ...) so only that one schema is needed; adding the samples with that
 * schema points each sample's {@code asset} at them.
 */
@ApplicationScoped
public class Samples {

    private static final Logger LOG = Logger.getLogger(Samples.class);
    static final String ROOT = "kilnmark/samples/";
    /** The library mark of a pipeline or flow added from the samples (ADR 0043). */
    public static final String LIBRARY = "sample";

    /** A source name the samples use, what kind of source it stands for, and what it should hold. */
    public record Placeholder(String name, String category, String description) {
    }

    /** The placeholders, in the order the samples' README and the Add samples dialog list them. */
    public static final List<Placeholder> PLACEHOLDERS = List.of(
        new Placeholder("crm-db", "databases",
            "A database with customer and billing tables: schemas crm (customers, customers_archive_2023, "
                + "accounts) and billing (transactions, invoices, periods)"),
        new Placeholder("shop-db", "databases",
            "A database with shop orders and stock: schemas shop (customers, orders, order_lines) and stock "
                + "(products, supplier_feed, product_costs)"),
        new Placeholder("files", "files",
            "File storage the samples write to, under samples/; a designer or an admin must turn on Allow "
                + "writing for it"));

    /**
     * One bundled sample: its file under samples/, what it is, the placeholders it reads or writes,
     * and its explanation, read from the comment it starts with.
     */
    public record Sample(String file, String name, String type, String description, List<String> sources,
                         String yaml, Explanation explanation) {
    }

    /**
     * What a sample or template explains about itself, read from the comment at the top of its file,
     * which stays the one place it is written: a title, what it shows (or, for a template, what it
     * is for), why it matters (the need it meets and who typically has it), what to look for when it
     * runs, and what it needs (sources, settings, rights). A part the comment leaves out is null.
     * Lines are joined into sentences; a line indented under the {@code #} (an example to copy) keeps
     * a line of its own.
     */
    public record Explanation(String title, String shows, String why, String lookFor, String needs) {

        /** Whether every part is there and not blank. */
        public boolean complete() {
            return Stream.of(title, shows, why, lookFor, needs).allMatch(p -> p != null && !p.isBlank());
        }
    }

    /** {@code Why it matters:}, {@code Look for:} and {@code Needs:}, each starting a part of the explanation. */
    private static final Pattern PART = Pattern.compile("^(Why it matters|Look for|Needs):\\s*(.*)$");
    /** The first line's tail, saying what the file is. */
    private static final Pattern KIND_TAIL = Pattern.compile("\\s*\\(a Kilnmark (sample|template)\\)\\.?$");

    /** The explanation in the comment {@code yaml} starts with; every part null when it starts with none. */
    public static Explanation explanation(String yaml) {
        List<String> comment = new ArrayList<>();
        for (String line : yaml.split("\r?\n", -1)) {
            if (!line.startsWith("#")) {
                break;
            }
            comment.add(line.substring(1).startsWith(" ") ? line.substring(2) : line.substring(1));
        }
        if (comment.isEmpty()) {
            return new Explanation(null, null, null, null, null);
        }
        String title = KIND_TAIL.matcher(comment.get(0).strip()).replaceFirst("").replaceFirst("\\.$", "");
        Map<String, StringBuilder> parts = new LinkedHashMap<>();
        StringBuilder current = parts.computeIfAbsent("shows", k -> new StringBuilder());
        for (String line : comment.subList(1, comment.size())) {
            Matcher m = PART.matcher(line.strip());
            String text;
            if (m.matches() && !line.startsWith(" ")) {
                current = parts.computeIfAbsent(m.group(1), k -> new StringBuilder());
                text = m.group(2);
            } else {
                text = line;
            }
            if (text.isBlank()) {
                continue;
            }
            boolean newLine = current.isEmpty() || current.charAt(current.length() - 1) == '\n';
            if (text.startsWith(" ")) {
                // an example to copy: a line of its own
                current.append(newLine ? "" : "\n").append(text.strip()).append('\n');
            } else {
                current.append(newLine ? "" : " ").append(text.strip());
            }
        }
        return new Explanation(title.isBlank() ? null : title, part(parts.get("shows")),
            part(parts.get("Why it matters")), part(parts.get("Look for")), part(parts.get("Needs")));
    }

    private static String part(StringBuilder b) {
        return b == null || b.toString().isBlank() ? null : b.toString().strip();
    }

    /** {@code source: <name>} as a node's setting, the name in group 3, optionally quoted (group 2). */
    static final Pattern SOURCE_LINE =
        Pattern.compile("(?m)^(\\s*(?:-\\s+)?source:\\s*)([\"']?)([A-Za-z0-9_.-]+)\\2(\\s*(?:#.*)?)$");

    /** The source of Kilnmark's sample data, made when the sample tables are first created. */
    public static final String SAMPLE_DATA = "sample-data";
    /** The pipeline that copies the sample data into a database. */
    public static final String SETUP = "sample-tables";
    /** The tables the samples read, as group.table: the sample data's folder and file. */
    public static final List<String> TABLES = List.of("crm.customers", "crm.customers_archive_2023", "crm.accounts",
        "billing.transactions", "billing.invoices", "billing.periods", "shop.customers", "shop.orders",
        "shop.order_lines", "stock.products", "stock.supplier_feed", "stock.product_costs");
    /** A schema to create the sample tables in: a plain name, so it needs no quoting anywhere. */
    private static final Pattern SCHEMA = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");
    /** {@code asset: group.table} for one of {@link #TABLES}, optionally quoted: group and table in groups 3 and 4. */
    private static final Pattern ASSET_LINE = Pattern.compile(
        "(?m)^(\\s*(?:-\\s+)?asset:\\s*)([\"']?)(crm|billing|shop|stock)\\.([a-z0-9_]+)\\2(\\s*(?:#.*)?)$");

    /** The server variable the sample sample-server-variable reads, and the value the starter pack gives it. */
    public static final String REGION_VARIABLE = "sample_region";
    static final String REGION = "EMEA";

    private final PipelineService pipelines;
    private final PipelineStore pipelineStore;
    private final FlowService flows;
    private final FlowStore flowStore;
    private final PipelineSources sources;
    private final GraphStore store;
    private final VariableService variables;

    public Samples(PipelineService pipelines, PipelineStore pipelineStore, FlowService flows, FlowStore flowStore,
                   PipelineSources sources, GraphStore store, VariableService variables) {
        this.pipelines = pipelines;
        this.pipelineStore = pipelineStore;
        this.flows = flows;
        this.flowStore = flowStore;
        this.sources = sources;
        this.store = store;
        this.variables = variables;
    }

    /** The folder under samples/ that holds the templates: starting points, never added as samples. */
    public static final String TEMPLATES = "templates/";

    /** Every bundled sample, in the list's order (by folder), without the templates. */
    public static List<Sample> bundled() {
        return everything().stream().filter(s -> !s.file().startsWith(TEMPLATES)).toList();
    }

    /**
     * The templates (samples/templates): pipelines and flows to start one's own from, with
     * {@code <...>} where a source, table or pipeline of one's own goes. Never added as samples: the
     * Library page opens one in the editor under a name the person gives (ADR 0043).
     */
    public static List<Sample> templates() {
        return everything().stream().filter(s -> s.file().startsWith(TEMPLATES)).toList();
    }

    /**
     * What a pipeline or flow added from the samples says about itself, for its own page: the sample's
     * file under samples/, its explanation (the comment its file starts with, read from the bundled
     * file, so the page and the Library never differ), and whether it has been changed since it was
     * added (a later version whose YAML, leaving out where the canvas draws the nodes and its notes, is
     * not the one added).
     */
    public record About(String file, Explanation explanation, boolean changed) {
    }

    /**
     * The {@link About} of a saved pipeline or flow, or null when it was not added from the samples:
     * marked {@link #LIBRARY} when it was added, and named as a bundled sample of its type is. A sample
     * no longer bundled (a later release dropped it) has none. {@code first} gives version 1's YAML,
     * asked for only when there are later versions; null when it cannot be read, which counts as changed.
     */
    public static About about(String library, String name, String type, int latestVersion, String latestYaml,
                              java.util.function.Supplier<String> first) {
        if (!LIBRARY.equals(library) || name == null) {
            return null;
        }
        Sample s = bundled().stream().filter(x -> name.equals(x.name()) && type.equals(x.type())).findFirst()
            .orElse(null);
        if (s == null) {
            return null;
        }
        boolean changed = false;
        if (latestVersion > 1) {
            String added = first.get();
            changed = added == null || latestYaml == null || !withoutCanvas(added).equals(withoutCanvas(latestYaml));
        }
        return new About(s.file(), s.explanation(), changed);
    }

    /**
     * The YAML without what only the canvas reads: where it draws each node ({@code layout:}), its
     * notes ({@code notes:}) and each node's {@code note:}, as the pipeline page compares versions
     * (ui/src/pipelines/template.ts). Moving nodes and writing notes changes nothing a run does.
     */
    static String withoutCanvas(String yaml) {
        return io.kilnmark.kiln.yaml.CanvasNotes.withoutCanvas(yaml);
    }

    /** The bundled files, read once: they are the server's own resources and never change while it runs. */
    private static volatile List<Sample> everythingRead;

    /** Every bundled file, samples and templates, in the list's order. Missing resources give an empty list. */
    private static List<Sample> everything() {
        List<Sample> read = everythingRead;
        if (read != null) {
            return read;
        }
        List<Sample> out = readEverything();
        if (!out.isEmpty()) {
            everythingRead = List.copyOf(out);
        }
        return out;
    }

    private static List<Sample> readEverything() {
        String list = read(ROOT + "samples.list");
        List<Sample> out = new ArrayList<>();
        if (list == null) {
            return out;
        }
        for (String line : list.split("\n")) {
            String file = line.strip();
            if (!file.endsWith(".yaml") || file.contains("..") || file.startsWith("/") || file.contains("\\")) {
                continue;
            }
            String yaml = read(ROOT + file);
            if (yaml != null) {
                out.add(sample(file, yaml));
            }
        }
        return out;
    }

    /** A sample from its file and YAML: its name, type and description from the top-level keys. */
    public static Sample sample(String file, String yaml) {
        String type = Pattern.compile("(?m)^kilnmark:\\s*flow/").matcher(yaml).find() ? "flow" : "pipeline";
        return new Sample(file, topLevel(yaml, "name"), type, topLevel(yaml, "description"), sourcesOf(yaml), yaml,
            explanation(yaml));
    }

    /** The placeholders a sample's nodes name, in the order of {@link #PLACEHOLDERS}. */
    public static List<String> sourcesOf(String yaml) {
        Set<String> named = new LinkedHashSet<>();
        Matcher m = SOURCE_LINE.matcher(yaml);
        while (m.find()) {
            named.add(m.group(3));
        }
        return PLACEHOLDERS.stream().map(Placeholder::name).filter(named::contains).toList();
    }

    /** The YAML with every node's {@code source:} placeholder replaced as {@code mapping} says; others left. */
    public static String withSources(String yaml, Map<String, String> mapping) {
        Matcher m = SOURCE_LINE.matcher(yaml);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String to = mapping.get(m.group(3));
            String quote = m.group(2).isEmpty() && needsQuotes(to) ? "\"" : m.group(2);
            String line = to == null ? m.group() : m.group(1) + quote + to + quote + m.group(4);
            m.appendReplacement(out, Matcher.quoteReplacement(line));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** YAML would read a source called null, true, no, on or 123 as something other than text. */
    private static final Pattern PLAIN_TEXT = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*");
    private static final Set<String> YAML_WORDS = Set.of("null", "true", "false", "yes", "no", "on", "off", "y",
        "n");

    static boolean needsQuotes(String name) {
        return name != null && (!PLAIN_TEXT.matcher(name).matches() || YAML_WORDS.contains(name.toLowerCase(
            java.util.Locale.ROOT)));
    }

    /** The YAML with each sample table it reads ({@code crm.customers}) as created in {@code schema}. */
    public static String withTables(String yaml, String schema) {
        Matcher m = ASSET_LINE.matcher(checkedTablesIn(queriesWithTables(yaml, schema), schema));
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String table = m.group(3) + "." + m.group(4);
            String line = TABLES.contains(table)
                ? m.group(1) + m.group(2) + tableIn(schema, table) + m.group(2) + m.group(5) : m.group();
            m.appendReplacement(out, Matcher.quoteReplacement(line));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * A check-source node's {@code tables: [...]}, written on one line as the bundled samples write it,
     * the names unquoted, in group 2. A list in another form is left as it is.
     */
    private static final Pattern TABLES_LINE = Pattern.compile("(?m)^(\\s*tables:\\s*\\[)([^\\]\\n]*)(\\].*)$");

    /** Each check-source {@code tables: [...]} with the sample tables it names as created in {@code schema}. */
    private static String checkedTablesIn(String yaml, String schema) {
        Matcher m = TABLES_LINE.matcher(yaml);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            List<String> names = new ArrayList<>();
            for (String t : m.group(2).split(",", -1)) {
                String name = t.strip();
                names.add(TABLES.contains(name) ? tableIn(schema, name) : name);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + String.join(", ", names) + m.group(3)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** {@code pipeline: <name>} in a flow: the pipeline a run-pipeline or for-each node runs. */
    private static final Pattern PIPELINE_LINE = Pattern.compile("(?m)^\\s*(?:-\\s+)?pipeline:\\s*([a-z][a-z0-9-]*)\\s*$");

    /** The pipelines a flow sample runs, by name. */
    static List<String> pipelinesRun(String yaml) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = PIPELINE_LINE.matcher(yaml);
        while (m.find()) {
            out.add(m.group(1));
        }
        return List.copyOf(out);
    }

    /** A {@code query:} setting's first line: its indent in group 1, the rest in group 2. */
    private static final Pattern QUERY_LINE = Pattern.compile("^( *)query\\s*:(.*)$");

    /** Whether a read in {@code yaml} runs a query (ADR 0028). */
    static boolean readsWithQuery(String yaml) {
        return yaml.lines().anyMatch(l -> QUERY_LINE.matcher(l).matches());
    }

    /** A {@code query:} or a sql node's {@code statements:}: its indent in group 1, its key in 2, the rest in 3. */
    private static final Pattern SQL_LINE = Pattern.compile("^( *)(query|statements)\\s*:(.*)$");

    /**
     * Each {@code query:} and each sql node's {@code statements:} (the first line and the lines
     * indented under it) with the sample tables it names as created in {@code schema}, as
     * {@link #withTables} does for {@code asset:}.
     */
    static String queriesWithTables(String yaml, String schema) {
        String[] lines = yaml.split("\n", -1);
        int under = -1;
        for (int i = 0; i < lines.length; i++) {
            Matcher q = SQL_LINE.matcher(lines[i]);
            if (q.matches()) {
                under = q.group(1).length();
                lines[i] = q.group(1) + q.group(2) + ":" + tablesIn(q.group(3), schema);
            } else if (under >= 0 && (lines[i].isBlank() || indent(lines[i]) > under)) {
                lines[i] = tablesIn(lines[i], schema);
            } else {
                under = -1;
            }
        }
        return String.join("\n", lines);
    }

    private static int indent(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') {
            n++;
        }
        return n;
    }

    /** {@code sql} with each of {@link #TABLES} it names, as a whole name, as created in {@code schema}. */
    private static String tablesIn(String sql, String schema) {
        String out = sql;
        for (String t : TABLES) {
            out = Pattern.compile("(?<![\\w.])" + Pattern.quote(t) + "(?!\\w)").matcher(out)
                .replaceAll(Matcher.quoteReplacement(tableIn(schema, t)));
        }
        return out;
    }

    /**
     * Why a sample whose read runs a query, or a flow sample with sql nodes (a flow names sources only
     * for those), cannot be added with {@code mapping}, or null: each database it uses must be a
     * PostgreSQL source with Allow SQL on (ADR 0028).
     */
    private String queryProblem(Sample s, Map<String, String> mapping) {
        boolean flow = "flow".equals(s.type());
        List<String> sql = flow ? sqlSources(s.yaml()) : readsWithQuery(s.yaml()) ? s.sources() : List.of();
        for (String p : sql) {
            boolean database = PLACEHOLDERS.stream().anyMatch(x -> x.name().equals(p) && "databases".equals(x.category()));
            if (!database) {
                continue;
            }
            Records.Source src = sources.source(mapping.get(p)).orElse(null);
            if (src == null || !"postgresql".equals(src.kind())
                || !io.kilnmark.server.sources.CommonSettings.allowsSql(src.settings())) {
                return (flow ? "it runs SQL" : "it reads with a query") + ": choose a PostgreSQL source with Allow "
                    + "SQL on for " + p;
            }
        }
        return null;
    }

    /**
     * The placeholders a flow sample runs SQL on: those its sql nodes and its wait-for nodes with a
     * query name. A check-source node, or a wait-for node waiting for a file, runs none.
     */
    static List<String> sqlSources(String yaml) {
        io.kilnmark.kiln.yaml.FlowDefinition.Parsed parsed = io.kilnmark.kiln.yaml.FlowDefinition.parse(yaml);
        io.kilnmark.kiln.yaml.FlowDefinition def = parsed.definition();
        if (def == null || !parsed.problems().isEmpty()) {
            // A flow that does not read cleanly is checked as if every source it names ran SQL: never less.
            return sourcesOf(yaml);
        }
        Set<String> named = new LinkedHashSet<>();
        for (io.kilnmark.kiln.yaml.FlowDefinition.Node n : def.nodes()) {
            boolean sql = io.kilnmark.kiln.yaml.FlowDefinition.SQL.equals(n.type())
                || (io.kilnmark.kiln.yaml.FlowDefinition.WAIT_FOR.equals(n.type()) && n.keys().containsKey("query"));
            if (sql && n.source() != null) {
                named.add(n.source());
            }
        }
        return PLACEHOLDERS.stream().map(Placeholder::name).filter(named::contains).toList();
    }

    /** Where one of {@link #TABLES} is created: {@code <schema>.<group>_<table>}. */
    static String tableIn(String schema, String table) {
        return schema + "." + table.replace('.', '_');
    }

    /** The pipeline copying every sample table from the sample data into {@code source}'s {@code schema}. */
    public static String setupYaml(String source, String schema) {
        StringBuilder y = new StringBuilder();
        y.append("# Copies Kilnmark's sample data into ").append(source).append(", schema ").append(schema)
            .append(", as the samples read it.\n")
            .append("# Each table is replaced: run it again to put the sample tables back as they were.\n")
            .append("kilnmark: pipeline/v1\n")
            .append("name: ").append(SETUP).append('\n')
            .append("description: The sample tables, in ").append(source).append('.').append(schema).append(".\n\n")
            .append("nodes:\n");
        for (String t : TABLES) {
            String id = t.replace('.', '_');
            y.append("  - id: ").append(id).append('\n')
                .append("    type: read\n")
                .append("    source: ").append(SAMPLE_DATA).append('\n')
                .append("    asset: ").append(t.replace('.', '/')).append(".csv\n\n")
                .append("  - id: ").append(id).append("_out\n")
                .append("    type: write\n")
                .append("    input: ").append(id).append('\n')
                .append("    source: ").append(source).append('\n')
                .append("    table: ").append(tableIn(schema, t)).append('\n')
                .append("    mode: replace\n\n");
        }
        return y.toString().stripTrailing() + "\n";
    }

    private static String topLevel(String yaml, String key) {
        Matcher m = Pattern.compile("(?m)^" + key + ":[ \\t]*(.*?)[ \\t]*$").matcher(yaml);
        return m.find() ? m.group(1) : null;
    }

    // ---- what the API shows and does ----

    /**
     * A sample as the API lists it: without its YAML, with its explanation and whether a pipeline or
     * flow of its name exists.
     */
    public record SampleView(String file, String name, String type, String description, List<String> sources,
                             boolean exists, Explanation explanation) {
    }

    /** Where the sample tables were last created (the {@value #SETUP} pipeline's target), and its last run. */
    public record Tables(String source, String schema, PipelineViews.RunSummary lastRun) {
    }

    /** {@code GET /api/v1/samples}: the placeholders, the samples, and the sample tables if created. */
    public record Catalogue(List<Placeholder> placeholders, List<SampleView> samples, Tables tables) {
    }

    /**
     * {@code POST /api/v1/samples}: placeholder to saved source; a placeholder left out skips its
     * samples. With {@code schema}, the samples read the sample tables created there.
     */
    public record AddRequest(Map<String, String> sources, String schema) {
    }

    /** {@code POST /api/v1/samples/tables}: the database and schema to create the sample tables in. */
    public record TablesRequest(String source, String schema) {
    }

    /** The setup pipeline saved and its run queued. */
    public record TablesResult(String pipeline, PipelineViews.RunSummary run, String source, String schema) {
    }

    /** What happened to one sample: added, exists (left as it is), skipped or failed, and why. */
    public record Outcome(String name, String file, String outcome, String reason, Boolean valid) {
    }

    public record AddResult(List<Outcome> samples, int added) {
    }

    public Catalogue catalogue(Sights sights) {
        Set<String> existing = new java.util.HashSet<>();
        pipelines.list(sights).forEach(p -> existing.add(p.name()));
        Set<String> existingFlows = new java.util.HashSet<>();
        flowStore.summaries().forEach(f -> existingFlows.add(f.flow().name()));
        return new Catalogue(PLACEHOLDERS, bundled().stream().map(s -> new SampleView(s.file(), s.name(), s.type(),
            s.description(), s.sources(), "flow".equals(s.type()) ? existingFlows.contains(s.name())
                : existing.contains(s.name()), s.explanation())).toList(),
            existing.contains(SETUP) ? tables(sights) : null);
    }

    /** The {@value #SETUP} pipeline, or null when there is none. */
    private PipelineViews.PipelineDetail existingSetup(Sights sights) {
        try {
            return pipelines.get(SETUP, sights);
        } catch (WebApplicationException e) {
            if (e.getResponse().getStatus() == Response.Status.NOT_FOUND.getStatusCode()) {
                return null;
            }
            throw e;
        }
    }

    /** Where the {@value #SETUP} pipeline writes, read from its latest version; null if it cannot be told. */
    private Tables tables(Sights sights) {
        PipelineViews.PipelineDetail d = existingSetup(sights);
        return d == null ? null : tables(d);
    }

    private static Tables tables(PipelineViews.PipelineDetail d) {
        String yaml = d.latest() == null ? "" : d.latest().yaml();
        Matcher write = Pattern.compile("(?m)^\\s*source:\\s*(\\S+)\\s*\\n\\s*table:\\s*([A-Za-z0-9_]+)\\.")
            .matcher(yaml);
        return write.find() ? new Tables(write.group(1), write.group(2), d.lastRun()) : null;
    }

    /**
     * Saves the {@value #SETUP} pipeline, copying the sample data into {@code schema} of the
     * database {@code source}, and queues a run of it. The database must be a saved database source
     * that allows writing. Running it again replaces the tables; it is refused while the tables are
     * being created, and when a pipeline called {@value #SETUP} is there that is not one this wrote
     * (someone's own, or one changed since), which it would otherwise overwrite.
     *
     * <p>The sample data's own source is added first if it is not there, and so is the server
     * variable {@value #REGION_VARIABLE} that one sample reads, if it is not set. Adding a source is
     * otherwise for admins; this one is let through for stewards because it has no settings, no
     * secret and reads only Kilnmark's own files (ADR 0024).
     */
    public TablesResult createTables(TablesRequest body, String by, AccessRole role, Sights sights) {
        String name = body == null || body.source() == null ? "" : body.source().strip();
        String schema = body == null || body.schema() == null ? "" : body.schema().strip();
        Records.Source target = sources.source(name).orElseThrow(() -> new IllegalArgumentException(name.isEmpty()
            ? "source: choose the database to create the sample tables in"
            : "source: there is no saved source of that name"));
        String category = sources.connector(target.kind()).map(c -> c.category()).orElse("other");
        if (!"databases".equals(category)) {
            throw new IllegalArgumentException("source: the source chosen is not a database; the sample tables go in a "
                + "database, such as PostgreSQL, MySQL, MariaDB or SQL Server");
        }
        if (!"true".equals(target.settings().get("allow_writing"))) {
            throw new IllegalArgumentException("source: the source chosen does not allow writing; a designer or an admin "
                + "turns on Allow writing for it first");
        }
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalArgumentException("schema: the schema to create the tables in, such as public or dbo: "
                + "letters, digits and _, starting with a letter or _");
        }
        PipelineViews.PipelineDetail existing = existingSetup(sights);
        if (existing != null) {
            PipelineViews.RunSummary last = existing.lastRun();
            if (last != null && List.of("queued", "running").contains(last.status())) {
                throw PipelineService.fail(Response.Status.CONFLICT, "The sample tables are being created now (run "
                    + last.id() + "): wait for it to finish");
            }
            Tables was = tables(existing);
            if (was == null || existing.latest() == null
                || !setupYaml(was.source(), was.schema()).equals(existing.latest().yaml())) {
                throw PipelineService.fail(Response.Status.CONFLICT, "A pipeline called " + SETUP + " is here that "
                    + "Kilnmark did not write, or that has been changed since: rename or delete it to create the "
                    + "sample tables again");
            }
        }
        ensureSampleData(by);
        if (!variables.snapshot().containsKey(REGION_VARIABLE)) {
            variables.set(REGION_VARIABLE, "text", REGION, "The region the sample sample-server-variable names its "
                + "file after; set by the starter pack", by, role);
        }
        String yaml = setupYaml(name, schema);
        String message = "The sample tables, in " + name + "." + schema;
        if (existing == null) {
            pipelines.create(new PipelineViews.NewPipeline(SETUP, "Sample tables", null, yaml, message), by, sights,
                LIBRARY);
        } else {
            // Saved over the version read above only: one saved since is not overwritten (409).
            pipelines.save(SETUP, existing.latestVersion(), new PipelineViews.PipelineChange(yaml, message, null, null),
                by, sights);
        }
        PipelineViews.RunSummary run = pipelines.queueRun(SETUP, null, by, role);
        LOG.infof("Sample tables requested in %s.%s by %s: run %d", name, schema, by, run.id());
        return new TablesResult(SETUP, run, name, schema);
    }

    /**
     * The sample data's own source, added if it is not there: it has no settings and no secret and
     * reads only Kilnmark's own files, so stewards may have it added (ADR 0024). A source of that
     * name that is something else is refused, never replaced.
     */
    private void ensureSampleData(String by) {
        if (sources.source(SAMPLE_DATA).isEmpty()) {
            try {
                store.addSource(new Records.NewSource(SAMPLE_DATA, SAMPLE_DATA, Map.of(), null,
                    "Kilnmark sample data"));
                LOG.infof("Source %s added for the samples, by %s", SAMPLE_DATA, by);
            } catch (RuntimeException e) {
                if (sources.source(SAMPLE_DATA).isEmpty()) {
                    throw e;
                }
                // added by someone else at the same moment
            }
        } else if (!SAMPLE_DATA.equals(sources.source(SAMPLE_DATA).get().kind())) {
            throw new IllegalArgumentException("A source called " + SAMPLE_DATA + " already exists but is not "
                + "Kilnmark's sample data: rename or remove it first");
        }
    }

    /** Whether a sample reads Kilnmark's sample data itself (the volume sample does). */
    static boolean readsSampleData(String yaml) {
        Matcher m = SOURCE_LINE.matcher(yaml);
        while (m.find()) {
            if (SAMPLE_DATA.equals(m.group(3))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Adds every bundled pipeline, then every bundled flow, whose placeholders are all mapped, each to
     * a saved source, marked as samples ({@link #LIBRARY}). A sample whose name is taken is left as it
     * is; one that checks with problems is saved as a draft, as any pipeline or flow is. A sample whose
     * read runs a query is skipped unless the caller, seen through {@code sights}, may see unmasked
     * values (ADR 0028), and always when {@code sights} is null. Flows go after the pipelines they run.
     */
    public AddResult add(AddRequest body, String by, AccessRole role, io.kilnmark.server.quality.Sights sights) {
        Map<String, String> mapping = new LinkedHashMap<>();
        if (body != null && body.sources() != null) {
            for (var e : body.sources().entrySet()) {
                if (PLACEHOLDERS.stream().noneMatch(p -> p.name().equals(e.getKey()))) {
                    throw new IllegalArgumentException("sources: a name that is not a placeholder the "
                        + "samples use; they use " + String.join(", ", PLACEHOLDERS.stream().map(Placeholder::name)
                            .toList()));
                }
                String to = e.getValue() == null ? "" : e.getValue().strip();
                if (to.isEmpty()) {
                    continue;
                }
                if (sources.source(to).isEmpty()) {
                    throw new IllegalArgumentException("sources: " + e.getKey() + " names a source that is not saved");
                }
                mapping.put(e.getKey(), to);
            }
        }
        if (mapping.isEmpty()) {
            throw new IllegalArgumentException("sources: choose a saved source for at least one of "
                + String.join(", ", PLACEHOLDERS.stream().map(Placeholder::name).toList()));
        }
        String schema = body.schema() == null || body.schema().isBlank() ? null : body.schema().strip();
        if (schema != null && !SCHEMA.matcher(schema).matches()) {
            throw new IllegalArgumentException("schema: the schema the sample tables are in, such as public: "
                + "letters, digits and _, starting with a letter or _");
        }
        List<Outcome> out = new ArrayList<>();
        int added = 0;
        boolean dataNeeded = bundled().stream().anyMatch(s -> "pipeline".equals(s.type()) && readsSampleData(s.yaml())
            && s.sources().stream().allMatch(mapping::containsKey));
        if (dataNeeded) {
            ensureSampleData(by);
        }
        List<Sample> ordered = new ArrayList<>(bundled().stream().filter(s -> !"flow".equals(s.type())).toList());
        ordered.addAll(bundled().stream().filter(s -> "flow".equals(s.type())).toList());
        for (Sample s : ordered) {
            List<String> missing = s.sources().stream().filter(p -> !mapping.containsKey(p)).toList();
            if (!missing.isEmpty()) {
                out.add(new Outcome(s.name(), s.file(), "skipped", "no source chosen for " + String.join(", ", missing),
                    null));
                continue;
            }
            List<String> notThere = "flow".equals(s.type()) ? pipelinesRun(s.yaml()).stream()
                .filter(p -> pipelineStore.pipeline(p).isEmpty()).toList() : List.of();
            if (!notThere.isEmpty()) {
                out.add(new Outcome(s.name(), s.file(), "skipped", "it runs " + String.join(", ", notThere)
                    + ", which is not here: choose sources for it too", null));
                continue;
            }
            String sql = sights == null && readsWithQuery(s.yaml())
                ? "it reads with a query: only a designer who may see unmasked values adds it" : queryProblem(s, mapping);
            if (sql != null) {
                out.add(new Outcome(s.name(), s.file(), "skipped", sql, null));
                continue;
            }
            try {
                String yaml = withSources(s.yaml(), mapping);
                if ("flow".equals(s.type())) {
                    // A check-source node's tables, like a pipeline's assets, are the sample tables' names.
                    flows.createInLibrary(schema == null ? yaml : withTables(yaml, schema),
                        "Added from the samples (" + s.file() + ")", by, role,
                        sights != null && sights.mayUnmask(), LIBRARY);
                    Boolean valid = flowStore.summaries().stream().filter(f -> f.flow().name().equals(s.name()))
                        .map(FlowStore.Summary::valid).findFirst().orElse(null);
                    out.add(new Outcome(s.name(), s.file(), "added", null, valid));
                    added++;
                    continue;
                }
                PipelineViews.PipelineDetail d = pipelines.create(new PipelineViews.NewPipeline(s.name(), null, null,
                    schema == null ? yaml : withTables(yaml, schema), "Added from the samples (" + s.file() + ")"), by,
                    sights, LIBRARY);
                Boolean valid = d.latest() == null ? null : d.latest().valid();
                out.add(new Outcome(s.name(), s.file(), "added", null, valid));
                added++;
            } catch (WebApplicationException e) {
                if (e.getResponse().getStatus() == Response.Status.CONFLICT.getStatusCode()) {
                    out.add(new Outcome(s.name(), s.file(), "exists", "already there: left as it is", null));
                } else if (e.getResponse().getStatus() == Response.Status.FORBIDDEN.getStatusCode()) {
                    out.add(new Outcome(s.name(), s.file(), "skipped", ("flow".equals(s.type())
                        ? "it runs SQL" : "it reads with a query") + ": only a designer who may see unmasked values "
                        + "adds it", null));
                } else {
                    out.add(failed(s, e.getResponse().getEntity() instanceof io.kilnmark.server.api.ApiError a
                        ? a.error() : e.getMessage(), e));
                }
            } catch (RuntimeException e) {
                // One sample that cannot be saved does not stop the others; what was added is reported.
                out.add(failed(s, e.getMessage(), e));
            }
        }
        LOG.infof("%d sample(s) added by %s", added, by);
        return new AddResult(out, added);
    }

    private static Outcome failed(Sample s, String reason, Exception e) {
        LOG.warnf(e, "Sample %s could not be added", s.name());
        return new Outcome(s.name(), s.file(), "failed", reason == null ? "it could not be saved" : reason, null);
    }

    private static String read(String resource) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = Samples.class.getClassLoader();
        }
        try (InputStream in = cl.getResourceAsStream(resource)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

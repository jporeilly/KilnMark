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

import org.jboss.logging.Logger;

import io.kilnmark.graph.store.GraphStore;
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
 * is, so changes made to it are kept. Flows are listed, to read, but not added until the flows API
 * comes.
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

    /** A source name the samples use, what kind of source it stands for, and what it should hold. */
    public record Placeholder(String name, String category, String description) {
    }

    /** The placeholders, in the order the samples' README and the Add samples dialog list them. */
    public static final List<Placeholder> PLACEHOLDERS = List.of(
        new Placeholder("crm-db", "databases",
            "A database with customer and billing tables: schemas crm (customers, customers_archive_2023, "
                + "accounts) and billing (transactions, invoices)"),
        new Placeholder("shop-db", "databases",
            "A database with shop orders and stock: schemas shop (customers, orders, order_lines) and stock "
                + "(products, supplier_feed)"),
        new Placeholder("files", "files",
            "File storage the samples write to, under samples/; an admin must turn on Allow writing for it"));

    /** One bundled sample: its file under samples/, what it is, and the placeholders it reads or writes. */
    public record Sample(String file, String name, String type, String description, List<String> sources,
                         String yaml) {
    }

    /** {@code source: <name>} as a node's setting, the name in group 3, optionally quoted (group 2). */
    static final Pattern SOURCE_LINE =
        Pattern.compile("(?m)^(\\s*(?:-\\s+)?source:\\s*)([\"']?)([A-Za-z0-9_.-]+)\\2(\\s*(?:#.*)?)$");

    /** The source of Kilnmark's sample data, made when the sample tables are first created. */
    public static final String SAMPLE_DATA = "sample-data";
    /** The pipeline that copies the sample data into a database. */
    public static final String SETUP = "sample-tables";
    /** The tables the samples read, as group.table: the sample data's folder and file, and the lab's. */
    public static final List<String> TABLES = List.of("crm.customers", "crm.customers_archive_2023", "crm.accounts",
        "billing.transactions", "billing.invoices", "shop.customers", "shop.orders", "shop.order_lines",
        "stock.products", "stock.supplier_feed");
    /** A schema to create the sample tables in: a plain name, so it needs no quoting anywhere. */
    private static final Pattern SCHEMA = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");
    /** {@code asset: group.table} for one of {@link #TABLES}, with the group and table in groups 2 and 3. */
    private static final Pattern ASSET_LINE =
        Pattern.compile("(?m)^(\\s*(?:-\\s+)?asset:\\s*)(crm|billing|shop|stock)\\.([a-z0-9_]+)(\\s*(?:#.*)?)$");

    /** The server variable the sample sample-server-variable reads, and the value the starter pack gives it. */
    public static final String REGION_VARIABLE = "sample_region";
    static final String REGION = "EMEA";

    private final PipelineService pipelines;
    private final PipelineSources sources;
    private final GraphStore store;
    private final VariableService variables;

    public Samples(PipelineService pipelines, PipelineSources sources, GraphStore store, VariableService variables) {
        this.pipelines = pipelines;
        this.sources = sources;
        this.store = store;
        this.variables = variables;
    }

    /** Every bundled sample, in the list's order (by folder). Missing resources give an empty list. */
    public static List<Sample> bundled() {
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
        return new Sample(file, topLevel(yaml, "name"), type, topLevel(yaml, "description"), sourcesOf(yaml), yaml);
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
        Matcher m = ASSET_LINE.matcher(yaml);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String line = TABLES.contains(m.group(2) + "." + m.group(3))
                ? m.group(1) + tableIn(schema, m.group(2) + "." + m.group(3)) + m.group(4) : m.group();
            m.appendReplacement(out, Matcher.quoteReplacement(line));
        }
        m.appendTail(out);
        return out.toString();
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

    /** A sample as the API lists it: without its YAML, with whether a pipeline of its name exists. */
    public record SampleView(String file, String name, String type, String description, List<String> sources,
                             boolean exists) {
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
        return new Catalogue(PLACEHOLDERS, bundled().stream().map(s -> new SampleView(s.file(), s.name(), s.type(),
            s.description(), s.sources(), "pipeline".equals(s.type()) && existing.contains(s.name()))).toList(),
            existing.contains(SETUP) ? tables(sights) : null);
    }

    /** Where the {@value #SETUP} pipeline writes, read from its latest version; null if it cannot be told. */
    private Tables tables(Sights sights) {
        PipelineViews.PipelineDetail d = pipelines.get(SETUP, sights);
        String yaml = d.latest() == null ? "" : d.latest().yaml();
        Matcher write = Pattern.compile("(?m)^\\s*source:\\s*(\\S+)\\s*\\n\\s*table:\\s*([A-Za-z0-9_]+)\\.")
            .matcher(yaml);
        return write.find() ? new Tables(write.group(1), write.group(2), d.lastRun()) : null;
    }

    /**
     * Saves the {@value #SETUP} pipeline, copying the sample data into {@code schema} of the
     * database {@code source}, and queues a run of it. The database must be a saved database source
     * that allows writing; the sample data's own source is added first if it is not there (it reads
     * only Kilnmark's own files), and so is the server variable {@value #REGION_VARIABLE} that one
     * sample reads, if it is not set. Running it again replaces the tables.
     */
    public TablesResult createTables(TablesRequest body, String by, AccessRole role, Sights sights) {
        String name = body == null || body.source() == null ? "" : body.source().strip();
        String schema = body == null || body.schema() == null ? "" : body.schema().strip();
        Records.Source target = sources.source(name).orElseThrow(() -> new IllegalArgumentException(name.isEmpty()
            ? "source: choose the database to create the sample tables in"
            : "source: there is no saved source called '" + name + "'"));
        String category = sources.connector(target.kind()).map(c -> c.category()).orElse("other");
        if (!"databases".equals(category)) {
            throw new IllegalArgumentException("source: " + name + " is not a database; the sample tables go in a "
                + "database, such as PostgreSQL, MySQL, MariaDB or SQL Server");
        }
        if (!"true".equals(target.settings().get("allow_writing"))) {
            throw new IllegalArgumentException("source: " + name + " does not allow writing; an admin turns on Allow "
                + "writing for it first");
        }
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalArgumentException("schema: the schema to create the tables in, such as public or dbo: "
                + "letters, digits and _, starting with a letter or _");
        }
        if (sources.source(SAMPLE_DATA).isEmpty()) {
            try {
                store.addSource(new Records.NewSource(SAMPLE_DATA, SAMPLE_DATA, Map.of(), null, "Kilnmark sample data"));
                LOG.infof("Source %s added for the sample tables, by %s", SAMPLE_DATA, by);
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
        if (!variables.snapshot().containsKey(REGION_VARIABLE)) {
            variables.set(REGION_VARIABLE, "text", REGION, "The region the sample sample-server-variable names its "
                + "file after; set by the starter pack", by, role);
        }
        String yaml = setupYaml(name, schema);
        String message = "The sample tables, in " + name + "." + schema;
        try {
            pipelines.create(new PipelineViews.NewPipeline(SETUP, "Sample tables", null, yaml, message), by);
        } catch (WebApplicationException e) {
            if (e.getResponse().getStatus() != Response.Status.CONFLICT.getStatusCode()) {
                throw e;
            }
            pipelines.save(SETUP, null, new PipelineViews.PipelineChange(yaml, message, null, null), by, sights);
        }
        PipelineViews.RunSummary run = pipelines.queueRun(SETUP, null, by, role);
        LOG.infof("Sample tables requested in %s.%s by %s: run %d", name, schema, by, run.id());
        return new TablesResult(SETUP, run, name, schema);
    }

    /**
     * Adds every bundled pipeline whose placeholders are all mapped, each to a saved source. A
     * sample whose name is taken is left as it is; one that checks with problems is saved as a
     * draft, as any pipeline is.
     */
    public AddResult add(AddRequest body, String by) {
        Map<String, String> mapping = new LinkedHashMap<>();
        if (body != null && body.sources() != null) {
            for (var e : body.sources().entrySet()) {
                if (PLACEHOLDERS.stream().noneMatch(p -> p.name().equals(e.getKey()))) {
                    throw new IllegalArgumentException("sources: '" + e.getKey() + "' is not a placeholder the "
                        + "samples use; they use " + String.join(", ", PLACEHOLDERS.stream().map(Placeholder::name)
                            .toList()));
                }
                String to = e.getValue() == null ? "" : e.getValue().strip();
                if (to.isEmpty()) {
                    continue;
                }
                if (sources.source(to).isEmpty()) {
                    throw new IllegalArgumentException("sources: there is no saved source called '" + to + "'");
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
        for (Sample s : bundled()) {
            if ("flow".equals(s.type())) {
                out.add(new Outcome(s.name(), s.file(), "skipped", "a flow: flows are added once the flows API comes",
                    null));
                continue;
            }
            List<String> missing = s.sources().stream().filter(p -> !mapping.containsKey(p)).toList();
            if (!missing.isEmpty()) {
                out.add(new Outcome(s.name(), s.file(), "skipped", "no source chosen for " + String.join(", ", missing),
                    null));
                continue;
            }
            try {
                String yaml = withSources(s.yaml(), mapping);
                PipelineViews.PipelineDetail d = pipelines.create(new PipelineViews.NewPipeline(s.name(), null, null,
                    schema == null ? yaml : withTables(yaml, schema), "Added from the samples (" + s.file() + ")"), by);
                Boolean valid = d.latest() == null ? null : d.latest().valid();
                out.add(new Outcome(s.name(), s.file(), "added", null, valid));
                added++;
            } catch (WebApplicationException e) {
                if (e.getResponse().getStatus() == Response.Status.CONFLICT.getStatusCode()) {
                    out.add(new Outcome(s.name(), s.file(), "exists", "already there: left as it is", null));
                } else {
                    out.add(failed(s, e.getResponse().getEntity() instanceof io.kilnmark.server.api.ApiError a
                        ? a.error() : e.getMessage(), e));
                }
            } catch (RuntimeException e) {
                // One sample that cannot be saved does not stop the others; what was added is reported.
                out.add(failed(s, e.getMessage(), e));
            }
        }
        LOG.infof("%d sample pipeline(s) added by %s", added, by);
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

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

import io.kilnmark.server.quality.Sights;
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

    /** {@code source: <name>} as a node's setting, the name in group 2, optionally quoted. */
    private static final Pattern SOURCE_LINE =
        Pattern.compile("(?m)^(\\s*(?:-\\s+)?source:\\s*)([\"']?)([A-Za-z0-9_.-]+)\\2(\\s*(?:#.*)?)$");

    private final PipelineService pipelines;
    private final PipelineSources sources;

    public Samples(PipelineService pipelines, PipelineSources sources) {
        this.pipelines = pipelines;
        this.sources = sources;
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
            String line = to == null ? m.group() : m.group(1) + m.group(2) + to + m.group(2) + m.group(4);
            m.appendReplacement(out, Matcher.quoteReplacement(line));
        }
        m.appendTail(out);
        return out.toString();
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

    /** {@code GET /api/v1/samples}: the placeholders and the samples. */
    public record Catalogue(List<Placeholder> placeholders, List<SampleView> samples) {
    }

    /** {@code POST /api/v1/samples}: placeholder to saved source; a placeholder left out skips its samples. */
    public record AddRequest(Map<String, String> sources) {
    }

    /** What happened to one sample: added, exists (left as it is) or skipped, and why. */
    public record Outcome(String name, String file, String outcome, String reason, Boolean valid) {
    }

    public record AddResult(List<Outcome> samples, int added) {
    }

    public Catalogue catalogue(Sights sights) {
        Set<String> existing = new java.util.HashSet<>();
        pipelines.list(sights).forEach(p -> existing.add(p.name()));
        return new Catalogue(PLACEHOLDERS, bundled().stream().map(s -> new SampleView(s.file(), s.name(), s.type(),
            s.description(), s.sources(), "pipeline".equals(s.type()) && existing.contains(s.name()))).toList());
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
                PipelineViews.PipelineDetail d = pipelines.create(new PipelineViews.NewPipeline(s.name(), null, null,
                    withSources(s.yaml(), mapping), "Added from the samples (" + s.file() + ")"), by);
                Boolean valid = d.latest() == null ? null : d.latest().valid();
                out.add(new Outcome(s.name(), s.file(), "added", null, valid));
                added++;
            } catch (WebApplicationException e) {
                if (e.getResponse().getStatus() != Response.Status.CONFLICT.getStatusCode()) {
                    throw e;
                }
                out.add(new Outcome(s.name(), s.file(), "exists", "already there: left as it is", null));
            }
        }
        LOG.infof("%d sample pipeline(s) added by %s", added, by);
        return new AddResult(out, added);
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

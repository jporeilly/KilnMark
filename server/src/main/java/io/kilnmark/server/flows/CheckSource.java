package io.kilnmark.server.flows;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jboss.logging.Logger;

import io.kilnmark.graph.store.Records;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.yaml.FlowDefinition;
import io.kilnmark.server.pipelines.PipelineSources;

/**
 * A flow's {@code check-source} node (ADR 0041, docs/design/flow-controls.md C1): that a saved
 * source can be reached and holds the tables and files the node names. It connects as the source's
 * own test does, then looks each name up as the catalogue would record it ({@link Connector#asset}):
 * a table's columns from the database's metadata, a file's from its start (as a scan reads it, up
 * to 1 MiB). Nothing it reads is kept or shown.
 *
 * <p>What it says is in the flow's own words: the source's name and the names the node wrote. Never
 * the source's settings or what a driver said, which can name hosts and users.
 */
final class CheckSource {

    private static final Logger LOG = Logger.getLogger(CheckSource.class);

    /** The most names a message lists before saying how many more there are. */
    static final int NAMED = 5;

    /** How long a check may take when its node sets no {@code timeout}: a hung source never holds a flow. */
    static final Duration DEFAULT_LIMIT = Duration.ofMinutes(10);

    /**
     * How a check ended. {@code missing} is how many of the tables and files named were not there,
     * or null when the source could not be reached, so nothing was looked for.
     */
    record Outcome(boolean succeeded, Integer missing, String message) {
        static Outcome unreachable(String message) {
            return new Outcome(false, null, message);
        }
    }

    private CheckSource() {
    }

    /**
     * What is wrong with a check-source node on this server: its source must exist, and be one that
     * holds tables to look for tables, and files to look for files. Null when nothing is.
     */
    static String sourceRefusal(FlowDefinition.Node n, PipelineSources sources) {
        Optional<Records.Source> src = sources == null ? Optional.empty() : sources.source(n.source());
        if (src.isEmpty()) {
            return "Node " + n.id() + " checks the source '" + n.source() + "', which does not exist";
        }
        Optional<Connector> c = sources.connector(src.get().kind());
        if (c.isEmpty()) {
            return "Node " + n.id() + " checks " + n.source() + ", but this server has no connector for its kind";
        }
        String category = c.get().category();
        if (!n.check().tables().isEmpty() && !"databases".equals(category)) {
            return "Node " + n.id() + " looks for tables, but " + n.source() + " holds files, not tables: list them under "
                + "files";
        }
        if (!n.check().files().isEmpty() && !"files".equals(category)) {
            return "Node " + n.id() + " looks for files, but " + n.source() + " is not a file or object-store source: "
                + "list its tables under tables";
        }
        return null;
    }

    /**
     * Runs the check: connects within {@code connectTimeout}, then looks for each name in turn. An
     * interrupt (the flow stopped, or the node's timeout passed) ends it between names.
     */
    static Outcome run(FlowDefinition.Node n, PipelineSources sources, Duration connectTimeout) {
        String name = n.source();
        Optional<Records.Source> src = sources == null ? Optional.empty() : sources.source(name);
        if (src.isEmpty()) {
            return Outcome.unreachable("Node " + n.id() + ": there is no saved source called " + name);
        }
        Optional<Connector> found = sources.connector(src.get().kind());
        if (found.isEmpty()) {
            return Outcome.unreachable("Node " + n.id() + ": this server has no connector for " + name);
        }
        Connector c = found.get();
        SourceConfig config = new SourceConfig(src.get().name(), src.get().kind(), src.get().settings(),
            sources.secret(src.get()));
        if (sources.hosts().encryptionRefusal(c, config) != null) {
            return Outcome.unreachable("Could not reach " + name + ": this server connects to sources only over "
                + "encrypted connections, and this one is not set up for one");
        }
        if (sources.hosts().refusal(c, config) != null) {
            return Outcome.unreachable("Could not reach " + name + ": its host is not one this server may connect to");
        }
        try {
            c.test(config, connectTimeout);
        } catch (ConnectorException | RuntimeException e) {
            LOG.debugf("check-source %s: %s could not be reached (%s)", n.id(), name, e.getClass().getSimpleName());
            return Outcome.unreachable("Could not reach " + name);
        }
        List<String> tables = missing(c, config, n.check().tables());
        List<String> files = missing(c, config, n.check().files());
        if (Thread.currentThread().isInterrupted()) {
            return Outcome.unreachable("Stopped before " + name + " was checked to the end");
        }
        int gone = tables.size() + files.size();
        if (gone > 0 && gone == n.check().tables().size() + n.check().files().size()) {
            // Nothing found at all: the source may have stopped answering since the test. Test again to tell.
            try {
                c.test(config, connectTimeout);
            } catch (ConnectorException | RuntimeException e) {
                return Outcome.unreachable("Could not reach " + name);
            }
        }
        if (gone == 0) {
            return new Outcome(true, 0, name + " can be reached" + found(n.check()));
        }
        List<String> parts = new ArrayList<>();
        if (!tables.isEmpty()) {
            parts.add(of(tables.size(), n.check().tables().size(), "table", "tables"));
        }
        if (!files.isEmpty()) {
            parts.add(of(files.size(), n.check().files().size(), "file", "files"));
        }
        List<String> names = new ArrayList<>(tables);
        names.addAll(files);
        String listed = String.join(", ", names.subList(0, Math.min(NAMED, names.size())))
            + (names.size() > NAMED ? " and " + (names.size() - NAMED) + " more" : "");
        return new Outcome(false, gone, String.join(" and ", parts) + (gone == 1 ? " is" : " are") + " missing on "
            + name + ": " + listed);
    }

    /** The names {@code connector} cannot find on the source, in the order written; none once interrupted. */
    private static List<String> missing(Connector connector, SourceConfig config, List<String> names) {
        List<String> out = new ArrayList<>();
        for (String name : names) {
            if (Thread.currentThread().isInterrupted()) {
                return out;
            }
            try {
                connector.asset(config, name);
            } catch (ConnectorException | RuntimeException e) {
                // A file's path as the catalogue records it (%23 for #) names the file on disk too, as a read node finds it.
                String onDisk = io.kilnmark.kiln.plugins.files.FileSourceConnector.unescape(name);
                if (onDisk.equals(name) || !found(connector, config, onDisk)) {
                    out.add(name);
                }
            }
        }
        return out;
    }

    private static boolean found(Connector connector, SourceConfig config, String path) {
        try {
            connector.asset(config, path);
            return true;
        } catch (ConnectorException | RuntimeException e) {
            return false;
        }
    }

    /** "2 of 3 tables", "1 of 1 file". */
    private static String of(int missing, int of, String one, String many) {
        return missing + " of " + of + " " + (of == 1 ? one : many);
    }

    /** What was found, when everything was: "; its 2 tables and 1 file are there". */
    private static String found(FlowDefinition.Check check) {
        int t = check.tables().size();
        int f = check.files().size();
        if (t + f == 0) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (t > 0) {
            parts.add(t + (t == 1 ? " table" : " tables"));
        }
        if (f > 0) {
            parts.add(f + (f == 1 ? " file" : " files"));
        }
        return "; the " + String.join(" and ", parts) + (t + f == 1 ? " it names is" : " it names are") + " there";
    }
}

package io.kilnmark.server.pipelines;

import java.util.Map;
import java.util.Optional;

import io.kilnmark.graph.Urn;
import io.kilnmark.graph.store.Records;
import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.server.sources.HostPolicy;

/**
 * What the {@code read} and {@code write} nodes need from the server: saved sources, their
 * connectors and secrets, the catalogue, and the administrator's allowed hosts. An interface so
 * the node factories can be tested without a database.
 */
public interface PipelineSources {

    Optional<Records.Source> source(String name);

    Optional<Connector> connector(String kind);

    /** The source's secret, decrypted, or null when it has none. */
    String secret(Records.Source source);

    Optional<Records.AssetDetail> asset(String urn);

    /** The allowed hosts ({@code kilnmark.sources.allowed-hosts}). */
    HostPolicy hosts();

    /** Every connector, for the write options their writers declare. */
    default java.util.List<Connector> connectors() {
        return java.util.List.of();
    }

    /**
     * The source's own profiling settings for what a write writes (whether values are kept, sampling),
     * as the profile node names them; empty when there are none to pass.
     */
    default Map<String, String> profileSettings(Records.Source source, Connector connector, String target,
                                                java.util.List<String> key, String urn) {
        return Map.of();
    }

    /**
     * As {@link #profileSettings(Records.Source, Connector, String, java.util.List, String)}, knowing
     * the columns arriving at the write, so key-like columns keep the distinct-value sketches that
     * relationship discovery reads, as a scan's profile does.
     */
    default Map<String, String> profileSettings(Records.Source source, Connector connector, String target,
                                                java.util.List<String> key, String urn, java.util.List<String> columns) {
        return profileSettings(source, connector, target, key, urn);
    }

    /** The enabled quality rules of a catalogued asset, for a gate node; empty when it has none. */
    default java.util.List<io.kilnmark.server.quality.Rule> rules(String urn) {
        return java.util.List.of();
    }

    /** An asset's row count at its last scan or write, for a gate's row_count_change rules; null if unknown. */
    default Long rowCount(String urn) {
        return null;
    }

    /** A saved source ready to connect to: checked against the allowed hosts, its secret noted for scrubbing. */
    record Resolved(Records.Source source, Connector connector, SourceConfig config) {
    }

    /**
     * Resolves a saved source for a run, re-checking the allowed hosts now (an administrator may
     * have narrowed them since the pipeline was saved). Messages never hold the secret.
     */
    default Resolved resolve(String node, String name, RunContext run) throws NodeException {
        Records.Source s = source(name).orElseThrow(() -> new NodeException("Node " + node + ": there is no saved "
            + "source called '" + name + "'"));
        Connector c = connector(s.kind()).orElseThrow(() -> new NodeException("Node " + node + ": this server has no "
            + "connector for sources of kind '" + s.kind() + "'"));
        String secret = secret(s);
        run.secret(secret);
        SourceConfig config = new SourceConfig(s.name(), s.kind(), s.settings(), secret);
        try {
            hosts().check(c, config);
        } catch (IllegalArgumentException e) {
            throw new NodeException("Node " + node + " cannot use source " + s.name() + ": " + run.scrub(e.getMessage()));
        }
        return new Resolved(s, c, config);
    }

    /** The URN an asset of a source is recorded under, as a scan records it; null if either is not valid. */
    static String urn(String source, String path) {
        if (source == null || path == null || path.isBlank()) {
            return null;
        }
        try {
            return new Urn(source, path).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A setting's text, or null when absent, blank or not a single value. */
    static String text(Map<String, Object> config, String key) {
        Object v = config.get(key);
        if (v instanceof String s) {
            return s.isBlank() ? null : s.strip();
        }
        if (v instanceof Number || v instanceof Boolean) {
            return v.toString();
        }
        return null;
    }
}

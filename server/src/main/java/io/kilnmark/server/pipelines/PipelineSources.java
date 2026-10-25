package io.kilnmark.server.pipelines;

import java.util.Map;
import java.util.Optional;

import io.kilnmark.graph.Urn;
import io.kilnmark.graph.store.Records;
import io.kilnmark.kiln.StepException;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.server.sources.HostPolicy;

/**
 * What the {@code read} and {@code write} steps need from the server: saved sources, their
 * connectors and secrets, the catalogue, and the administrator's allowed hosts. An interface so
 * the step factories can be tested without a database.
 */
public interface PipelineSources {

    Optional<Records.Source> source(String name);

    Optional<Connector> connector(String kind);

    /** The source's secret, decrypted, or null when it has none. */
    String secret(Records.Source source);

    Optional<Records.AssetDetail> asset(String urn);

    /** The allowed hosts ({@code kilnmark.sources.allowed-hosts}). */
    HostPolicy hosts();

    /** A saved source ready to connect to: checked against the allowed hosts, its secret noted for scrubbing. */
    record Resolved(Records.Source source, Connector connector, SourceConfig config) {
    }

    /**
     * Resolves a saved source for a run, re-checking the allowed hosts now (an administrator may
     * have narrowed them since the pipeline was saved). Messages never hold the secret.
     */
    default Resolved resolve(String step, String name, RunContext run) throws StepException {
        Records.Source s = source(name).orElseThrow(() -> new StepException("Step " + step + ": there is no saved "
            + "source called '" + name + "'"));
        Connector c = connector(s.kind()).orElseThrow(() -> new StepException("Step " + step + ": this server has no "
            + "connector for sources of kind '" + s.kind() + "'"));
        String secret = secret(s);
        run.secret(secret);
        SourceConfig config = new SourceConfig(s.name(), s.kind(), s.settings(), secret);
        try {
            hosts().check(c, config);
        } catch (IllegalArgumentException e) {
            throw new StepException("Step " + step + " cannot use source " + s.name() + ": " + run.scrub(e.getMessage()));
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

package io.kilnmark.server.pipelines;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.arrow.vector.types.pojo.Schema;

import io.kilnmark.graph.store.Records;
import io.kilnmark.kiln.SettingSpec;
import io.kilnmark.kiln.Step;
import io.kilnmark.kiln.StepException;
import io.kilnmark.kiln.StepFactory;
import io.kilnmark.kiln.StepKind;
import io.kilnmark.kiln.StepSpec;
import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.ConnectorException;

/**
 * The {@code read} step: a table, view or file of a saved source, read with its connector's
 * reader (docs/design/pipelines.md section 4). The server registers it, since it needs the saved
 * sources and their secrets; the YAML only names the source. Its columns, for validation and the
 * canvas, are the asset's as last catalogued.
 */
public final class ReadStepFactory implements StepFactory {

    public static final String TYPE = "read";
    static final int BATCH_ROWS = 8192;

    private final PipelineSources sources;

    public ReadStepFactory(PipelineSources sources) {
        this.sources = sources;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public StepKind kind() {
        return StepKind.SOURCE;
    }

    @Override
    public String label() {
        return "Read a table or file";
    }

    @Override
    public String help() {
        return "Reads a table, view or file from a saved source. The source holds the credentials, so the pipeline "
            + "only names it.";
    }

    @Override
    public List<String> inputPorts() {
        return List.of();
    }

    @Override
    public List<String> outputPorts() {
        return List.of("out");
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
            SettingSpec.required("source", "Source", SettingSpec.Kind.SOURCE, "The saved source to read from"),
            SettingSpec.required("asset", "Table or file", SettingSpec.Kind.ASSET,
                "A table or view as schema.table, or a file's path inside the source"),
            SettingSpec.optional("row_limit", "Row limit", SettingSpec.Kind.NUMBER, 0,
                "Reads at most this many rows; 0 reads them all. Handy while trying a pipeline out"));
    }

    @Override
    public List<String> check(Map<String, Object> config) {
        List<String> problems = new ArrayList<>();
        String name = PipelineSources.text(config, "source");
        if (name != null) {
            Optional<Records.Source> s = sources.source(name);
            if (s.isEmpty()) {
                problems.add("source: there is no saved source called '" + name + "'");
            } else {
                Optional<Connector> c = sources.connector(s.get().kind());
                if (c.isEmpty()) {
                    problems.add("source: this server has no connector for sources of kind '" + s.get().kind() + "'");
                } else if (!c.get().capabilities().contains(Connector.Capability.READ)) {
                    problems.add("source: " + c.get().displayName() + " sources cannot be read in pipelines yet");
                }
            }
        }
        if (config.get("asset") != null && PipelineSources.text(config, "asset") == null) {
            problems.add("asset: one table or file, such as crm.customers or exports/orders.csv");
        }
        if (rowLimit(config) < 0) {
            problems.add("row_limit: a whole number of rows, 0 or more (0 reads every row)");
        }
        return problems;
    }

    /** The row limit set, 0 when none, or -1 when it is not a whole number of 0 or more. */
    static long rowLimit(Map<String, Object> config) {
        Object v = config.get("row_limit");
        if (v == null) {
            return 0;
        }
        if (v instanceof Long || v instanceof Integer) {
            long n = ((Number) v).longValue();
            return n < 0 ? -1 : n;
        }
        if (v instanceof String s) {
            try {
                long n = Long.parseLong(s.strip());
                return n < 0 ? -1 : n;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    /** The URN of the asset this step reads, as the catalogue records it; null if the settings do not say. */
    public static String urn(Map<String, Object> config) {
        return PipelineSources.urn(PipelineSources.text(config, "source"), PipelineSources.text(config, "asset"));
    }

    @Override
    public Schema outputSchema(String port, Map<String, Schema> inputs, Map<String, Object> config) {
        String urn = urn(config);
        if (urn == null) {
            return null;
        }
        return sources.asset(urn).filter(a -> !a.fields().isEmpty()).map(a -> ArrowKinds.schema(a.fields()))
            .orElse(null);
    }

    @Override
    public Step create(Map<String, Object> config) throws StepException {
        RunContext run = RunContext.current().orElseThrow(() -> new StepException(
            "A read step is set up only when its pipeline runs"));
        String source = PipelineSources.text(config, "source");
        String path = PipelineSources.text(config, "asset");
        if (source == null || path == null) {
            throw new StepException("A read step needs a source and an asset");
        }
        String step = "read " + path;
        PipelineSources.Resolved r = sources.resolve(step, source, run);
        AssetInfo asset;
        try {
            asset = r.connector().asset(r.config(), path);
        } catch (ConnectorException e) {
            throw new StepException("Cannot read " + path + " from " + source + ": " + run.scrub(e.getMessage()));
        } catch (RuntimeException e) {
            throw new StepException("Cannot read " + path + " from " + source + ": " + run.scrub(String.valueOf(
                e.getMessage())));
        }
        if (!asset.readable()) {
            throw new StepException(path + " in " + source + " cannot be read as rows");
        }
        String urn = PipelineSources.urn(source, asset.path());
        run.read(urn);
        StepSpec spec;
        try {
            spec = r.connector().reader(r.config(), asset, new Connector.ReadOptions(urn, Math.max(0, rowLimit(config)),
                BATCH_ROWS));
        } catch (RuntimeException e) {
            throw new StepException("Cannot read " + path + " from " + source + ": " + run.scrub(String.valueOf(
                e.getMessage())));
        }
        return new WithSettings(spec.step(), spec.settings());
    }
}

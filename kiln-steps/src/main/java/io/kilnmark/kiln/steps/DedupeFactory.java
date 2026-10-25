package io.kilnmark.kiln.steps;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;

import io.kilnmark.kiln.SettingSpec;
import io.kilnmark.kiln.Step;
import io.kilnmark.kiln.StepContext;
import io.kilnmark.kiln.StepException;
import io.kilnmark.kiln.StepFactory;
import io.kilnmark.kiln.StepKind;
import io.kilnmark.kiln.steps.expr.Names;

/**
 * {@code dedupe}: keeps the first row for each key and sends later rows with the same key to
 * {@code rejected}. Nulls count as equal to each other, as in SQL's {@code distinct}. The keys
 * seen are held in memory, up to {@code memory_limit_mb}.
 */
public final class DedupeFactory implements StepFactory {

    public static final String TYPE = "dedupe";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public StepKind kind() {
        return StepKind.TRANSFORM;
    }

    @Override
    public String label() {
        return "Remove duplicates";
    }

    @Override
    public String help() {
        return "Keeps the first row for each key. Later rows with the same key go to the rejected port, if "
            + "something is connected to it.";
    }

    @Override
    public List<String> inputPorts() {
        return List.of("in");
    }

    @Override
    public List<String> outputPorts() {
        return List.of("out", "rejected");
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
            SettingSpec.optional("key", "Key", SettingSpec.Kind.COLUMNS, null,
                "The columns that make rows duplicates of each other. Leave empty to compare whole rows."),
            SettingSpec.optional("memory_limit_mb", "Memory limit (MB)", SettingSpec.Kind.NUMBER, 256,
                "The most memory the keys seen so far may take."));
    }

    @Override
    public List<String> check(Map<String, Object> config) {
        Config c = new Config(config);
        c.columns("key", false);
        c.number("memory_limit_mb", 256, 1, 1_048_576);
        return c.problems();
    }

    @Override
    public Step create(Map<String, Object> config) throws StepException {
        Config c = new Config(config);
        List<String> key = c.columns("key", false);
        long mb = c.number("memory_limit_mb", 256, 1, 1_048_576);
        c.orThrow(TYPE);
        return new DedupeStep(key == null || key.isEmpty() ? null : key, BaseStep.megabytes(mb), mb);
    }

    @Override
    public Schema outputSchema(String port, Map<String, Schema> inputs, Map<String, Object> config)
        throws StepException {
        if (!outputPorts().contains(port)) {
            throw new StepException("A dedupe step has no output port '" + port + "'");
        }
        Schema in = inputs.get("in");
        if (in == null) {
            return null;
        }
        Config c = new Config(config);
        List<String> key = c.columns("key", false);
        c.orThrow(TYPE);
        checkKey(in, key, "");
        return in;
    }

    static void checkKey(Schema in, List<String> key, String prefix) throws StepException {
        if (key == null) {
            return;
        }
        for (String k : key) {
            if (BaseStep.field(in, k) == null) {
                String near = Names.suggest(k, BaseStep.names(in));
                throw new StepException(prefix + "key: unknown column '" + k + "'"
                    + (near != null ? "; did you mean " + near + "?" : ""));
            }
        }
    }

    /** The running step. */
    static final class DedupeStep extends BaseStep {
        private static final List<String> PORTS = List.of("out", "rejected");
        private final List<String> key;
        private final long limitBytes;
        private final long limitMb;
        private final Set<Object> seen = new HashSet<>();
        private long bytes;
        private Schema checked;

        DedupeStep(List<String> key, long limitBytes, long limitMb) {
            super(TYPE);
            this.key = key;
            this.limitBytes = limitBytes;
            this.limitMb = limitMb;
        }

        @Override
        public boolean process(StepContext ctx) throws StepException {
            VectorSchemaRoot batch = take(ctx, "in");
            if (batch == null) {
                return false;
            }
            int[] target;
            try {
                if (!batch.getSchema().equals(checked)) {
                    checkKey(batch.getSchema(), key, "Step " + name + ", ");
                    checked = batch.getSchema();
                }
                List<String> columns = key != null ? key : names(batch.getSchema());
                Keys.Reader[] readers = Keys.readers(batch, columns, null);
                int n = batch.getRowCount();
                target = new int[n];
                for (int i = 0; i < n; i++) {
                    Object k = Keys.of(readers, i, false);
                    if (seen.add(k)) {
                        bytes += 40 + Keys.size(k);
                    } else {
                        target[i] = 1;
                    }
                }
                if (bytes > limitBytes) {
                    throw fail("the keys seen so far take more than " + limitMb + " MB (" + count(seen.size())
                        + " keys); raise memory_limit_mb, or use fewer or shorter key columns");
                }
            } catch (StepException | RuntimeException e) {
                batch.close();
                throw e;
            }
            distribute(ctx, batch, target, PORTS);
            return true;
        }
    }
}

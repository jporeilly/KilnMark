package io.kilnmark.server.pipelines;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.apache.arrow.memory.BufferAllocator;

import io.kilnmark.kiln.MetadataEmitter;
import io.kilnmark.kiln.RowSet;
import io.kilnmark.kiln.Step;
import io.kilnmark.kiln.StepContext;
import io.kilnmark.kiln.StepException;

/**
 * A step that reads its settings from {@link StepContext#settings()} (a connector's reader, the
 * profile step), given the settings it was built with instead of the pipeline YAML's: whatever
 * runner runs the graph, the step sees what its connector meant it to see (which may hold the
 * source's secret, so they never appear in the YAML, the graph's config or a log). Everything else
 * comes from the runner's context.
 */
final class WithSettings implements Step {

    private final Step inner;
    private final Map<String, String> settings;
    private StepContext lastOuter;
    private StepContext lastWrapped;

    WithSettings(Step inner, Map<String, String> settings) {
        this.inner = inner;
        this.settings = Map.copyOf(settings);
    }

    /** The wrapped step, for tests. */
    Step inner() {
        return inner;
    }

    @Override
    public String type() {
        return inner.type();
    }

    @Override
    public void init(StepContext context) throws StepException {
        inner.init(wrap(context));
    }

    @Override
    public boolean process(StepContext context) throws StepException {
        return inner.process(wrap(context));
    }

    @Override
    public void close() {
        inner.close();
    }

    private StepContext wrap(StepContext outer) {
        if (outer != lastOuter) {
            lastOuter = outer;
            lastWrapped = new Context(outer, settings);
        }
        return lastWrapped;
    }

    private record Context(StepContext outer, Map<String, String> settings) implements StepContext {

        @Override
        public String stepName() {
            return outer.stepName();
        }

        @Override
        public MetadataEmitter metadata() {
            return outer.metadata();
        }

        @Override
        public BufferAllocator allocator() {
            return outer.allocator();
        }

        @Override
        public RowSet input() {
            return outer.input();
        }

        @Override
        public RowSet output() {
            return outer.output();
        }

        @Override
        public boolean hasInput() {
            return outer.hasInput();
        }

        @Override
        public boolean hasOutput() {
            return outer.hasOutput();
        }

        @Override
        public Map<String, Object> config() {
            return new LinkedHashMap<>(settings);
        }

        @Override
        public Set<String> inputPorts() {
            return outer.inputPorts();
        }

        @Override
        public Set<String> outputPorts() {
            return outer.outputPorts();
        }

        @Override
        public RowSet input(String port) throws StepException {
            return outer.input(port);
        }

        @Override
        public RowSet output(String port) throws StepException {
            return outer.output(port);
        }

        @Override
        public String toString() {
            return "StepContext[" + outer.stepName() + "]"; // never the settings: they may hold a secret
        }
    }
}

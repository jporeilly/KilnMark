package io.kilnmark.server.pipelines;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.apache.arrow.memory.BufferAllocator;

import io.kilnmark.kiln.MetadataEmitter;
import io.kilnmark.kiln.RowSet;
import io.kilnmark.kiln.Node;
import io.kilnmark.kiln.NodeContext;
import io.kilnmark.kiln.NodeException;

/**
 * A node that reads its settings from {@link NodeContext#settings()} (a connector's reader, the
 * profile node), given the settings it was built with instead of the pipeline YAML's: whatever
 * runner runs the graph, the node sees what its connector meant it to see (which may hold the
 * source's secret, so they never appear in the YAML, the graph's config or a log). Everything else
 * comes from the runner's context.
 */
final class WithSettings implements Node {

    private final Node inner;
    private final Map<String, String> settings;
    private NodeContext lastOuter;
    private NodeContext lastWrapped;

    WithSettings(Node inner, Map<String, String> settings) {
        this.inner = inner;
        this.settings = Map.copyOf(settings);
    }

    /** The wrapped node, for tests. */
    Node inner() {
        return inner;
    }

    @Override
    public String type() {
        return inner.type();
    }

    @Override
    public void init(NodeContext context) throws NodeException {
        inner.init(wrap(context));
    }

    @Override
    public boolean process(NodeContext context) throws NodeException {
        return inner.process(wrap(context));
    }

    @Override
    public void close() {
        inner.close();
    }

    @SuppressWarnings("ReferenceEquality") // remembers the wrapper for the very context it was made for
    private NodeContext wrap(NodeContext outer) {
        if (outer != lastOuter) {
            lastOuter = outer;
            lastWrapped = new Context(outer, settings);
        }
        return lastWrapped;
    }

    private record Context(NodeContext outer, Map<String, String> settings) implements NodeContext {

        @Override
        public String nodeName() {
            return outer.nodeName();
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
        public RowSet input(String port) throws NodeException {
            return outer.input(port);
        }

        @Override
        public RowSet output(String port) throws NodeException {
            return outer.output(port);
        }

        @Override
        public String toString() {
            return "NodeContext[" + outer.nodeName() + "]"; // never the settings: they may hold a secret
        }
    }
}

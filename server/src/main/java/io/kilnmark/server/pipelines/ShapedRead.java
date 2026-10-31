package io.kilnmark.server.pipelines;

import java.util.Map;
import java.util.Set;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;

import io.kilnmark.kiln.MetadataEmitter;
import io.kilnmark.kiln.Node;
import io.kilnmark.kiln.NodeContext;
import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.RowSet;
import io.kilnmark.kiln.nodes.ReadShaping;

/**
 * A read node with {@code columns} or {@code where} (docs/design/pipelines.md, "The read node's
 * columns and where"): its source's reader puts its batches here, and what the read passes on is
 * those rows meeting the whole condition, with only the chosen columns and at most the row limit.
 * The reader may already have narrowed its read; the rows passed on are the same either way.
 */
final class ShapedRead implements Node {

    /** Batches between the reader and the shaping: each reader puts one per call. */
    private static final int BETWEEN = 4;

    private final Node reader;
    private final ReadShaping shaping;
    private RowSet between;
    private NodeContext readerContext;
    private boolean readerDone;
    private boolean sentAny;

    ShapedRead(Node reader, ReadShaping shaping) {
        this.reader = reader;
        this.shaping = shaping;
    }

    @Override
    public String type() {
        return reader.type();
    }

    @Override
    public void init(NodeContext context) throws NodeException {
        between = new RowSet(context.nodeName() + " (as read)", BETWEEN);
        readerContext = new Context(context, between);
        reader.init(readerContext);
    }

    @Override
    public boolean process(NodeContext context) throws NodeException {
        try {
            if (!readerDone && !reader.process(readerContext)) {
                readerDone = true;
                between.finish();
            }
            while (between.queued() > 0) {
                VectorSchemaRoot batch = between.take(context.allocator());
                if (batch == null) {
                    break; // the end
                }
                VectorSchemaRoot shaped = shaping.apply(batch, context.allocator(), !sentAny);
                if (shaped != null) {
                    sentAny = true;
                    context.output().put(shaped);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NodeException("Stopped while reading", e);
        }
        if (shaping.full()) {
            return false; // as many rows as asked for: the rest is not read
        }
        return !readerDone || between.queued() > 0;
    }

    @Override
    public void close() {
        reader.close();
        if (between != null) {
            try {
                VectorSchemaRoot left;
                while (between.queued() > 0 && (left = between.take(readerContext.allocator())) != null) {
                    left.close();
                }
            } catch (InterruptedException | NodeException | RuntimeException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                // nothing more to free
            }
        }
    }

    /** The read node's context, with the reader's output going to {@code between}. */
    private record Context(NodeContext outer, RowSet between) implements NodeContext {

        @Override
        public String nodeName() {
            return outer.nodeName();
        }

        @Override
        public Map<String, String> settings() {
            return outer.settings();
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
            return between;
        }

        @Override
        public boolean hasInput() {
            return outer.hasInput();
        }

        @Override
        public boolean hasOutput() {
            return true;
        }

        @Override
        public Map<String, Object> config() {
            return outer.config();
        }

        @Override
        public Set<String> inputPorts() {
            return outer.inputPorts();
        }

        @Override
        public Set<String> outputPorts() {
            return Set.of("out");
        }

        @Override
        public RowSet input(String port) throws NodeException {
            return outer.input(port);
        }

        @Override
        public RowSet output(String port) throws NodeException {
            if ("out".equals(port)) {
                return between;
            }
            throw new NodeException("Node " + outer.nodeName() + " has no output '" + port + "'");
        }

        @Override
        public String toString() {
            return "NodeContext[" + outer.nodeName() + "]"; // never the settings: they may hold a secret
        }
    }
}

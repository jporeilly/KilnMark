package io.kilnmark.server.pipelines;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;

import io.kilnmark.kiln.MetadataEmitter;
import io.kilnmark.kiln.Node;
import io.kilnmark.kiln.NodeContext;
import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.RowSet;
import io.kilnmark.kiln.nodes.WhichRow;

/**
 * An upsert write that states which row of a repeated key wins ({@link WhichRow}): it sits in front
 * of the source's writer and hands it, batch by batch, only the rows that make the table end with
 * each key's winner. An upsert keeps the last row it is sent, so {@code last} (the default) needs no
 * node of this kind and the writer is used as it is.
 *
 * <p>The writer reads its batches from a row set of this node's own, one per call, as the node
 * contract says a node with an input does; everything else it asks of its context is the run's.
 */
final class UpsertWinners implements Node {

    private final Node writer;
    private final WhichRow.Settings settings;
    private final WhichRow.Upserts upserts;
    private RowSet feed;
    private NodeContext inner;
    private boolean ended;

    UpsertWinners(Node writer, WhichRow.Settings settings, List<String> key, long limitMb) {
        this.writer = writer;
        this.settings = settings;
        this.upserts = new WhichRow.Upserts(settings, key, limitMb);
    }

    @Override
    public String type() {
        return writer.type();
    }

    @Override
    public void init(NodeContext context) throws NodeException {
        feed = new RowSet(context.nodeName() + ".in", 1);
        inner = new Fed(context, feed);
        writer.init(inner);
    }

    @Override
    public boolean process(NodeContext context) throws NodeException {
        if (ended) {
            return writer.process(inner);
        }
        VectorSchemaRoot batch;
        try {
            batch = context.input().take(context.allocator());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NodeException("Interrupted", e);
        }
        try {
            if (batch == null) {
                ended = true;
                feed.finish();
                note(context);
                return writer.process(inner);
            }
            // A batch of no rows is handed on too, as it would be without a rule: a read sends one to
            // say its columns, and the writer checks the table against them.
            feed.put(upserts.choose(batch, context.allocator()));
            return writer.process(inner);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NodeException("Interrupted", e);
        }
    }

    /** Says in the run's log how many rows the rule left out or let replace an earlier one. */
    private void note(NodeContext context) {
        long out = upserts.leftOut();
        long replacing = upserts.replacing();
        if (out == 0 && replacing == 0) {
            return;
        }
        String rule = settings.words();
        StringBuilder b = new StringBuilder("Which row wins (" + rule + "): ");
        if (out > 0) {
            b.append(String.format(java.util.Locale.UK, "%,d", out)).append(out == 1 ? " row" : " rows")
                .append(" left out because an earlier row of the same key wins");
        }
        if (replacing > 0) {
            b.append(out > 0 ? "; " : "").append(String.format(java.util.Locale.UK, "%,d", replacing))
                .append(replacing == 1 ? " row" : " rows")
                .append(" written again over an earlier row of the same key that it beats");
        }
        context.note(b.toString());
    }

    @Override
    public void close() {
        try {
            writer.close();
        } finally {
            if (feed != null) {
                feed.cancel();
            }
        }
    }

    /** The run's context, with the writer's input read from the node's own row set. */
    private static final class Fed implements NodeContext {
        private final NodeContext run;
        private final RowSet feed;

        Fed(NodeContext run, RowSet feed) {
            this.run = run;
            this.feed = feed;
        }

        @Override
        public String nodeName() {
            return run.nodeName();
        }

        @Override
        public Map<String, String> settings() {
            return run.settings();
        }

        @Override
        public MetadataEmitter metadata() {
            return run.metadata();
        }

        @Override
        public BufferAllocator allocator() {
            return run.allocator();
        }

        @Override
        public RowSet input() {
            return feed;
        }

        @Override
        public RowSet output() {
            return run.output();
        }

        @Override
        public boolean hasInput() {
            return true;
        }

        @Override
        public boolean hasOutput() {
            return run.hasOutput();
        }

        @Override
        public Map<String, Object> config() {
            return run.config();
        }

        @Override
        public Set<String> inputPorts() {
            return Set.of("in");
        }

        @Override
        public Set<String> outputPorts() {
            return run.outputPorts();
        }

        @Override
        public RowSet input(String port) throws NodeException {
            if ("in".equals(port)) {
                return feed;
            }
            return run.input(port);
        }

        @Override
        public RowSet output(String port) throws NodeException {
            return run.output(port);
        }

        @Override
        public List<RowSet> inputs(String port) {
            return "in".equals(port) ? List.of(feed) : run.inputs(port);
        }

        @Override
        public Schema inputSchema(String port) {
            return run.inputSchema(port);
        }

        @Override
        public Schema inputSchema(String port, int index) {
            return run.inputSchema(port, index);
        }

        @Override
        public void spilled(long bytes) {
            run.spilled(bytes);
        }

        @Override
        public void note(String text) {
            run.note(text);
        }

        @Override
        public void emit(String port, VectorSchemaRoot batch) throws NodeException, InterruptedException {
            run.emit(port, batch);
        }
    }
}

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
import io.kilnmark.kiln.RowErrors;
import io.kilnmark.kiln.RowSet;
import io.kilnmark.kiln.nodes.WhichRow;

/**
 * What sits in front of the source's writer when a write needs more than the writer does:
 *
 * <ul>
 *   <li>An upsert's rule for which row of a repeated key wins ({@link WhichRow}): the writer is
 *       handed, batch by batch, only the rows that make the table end with each key's winner. An
 *       upsert keeps the last row it is sent, so {@code last} (the default) needs nothing here.</li>
 *   <li>The write's error tolerance (ADR 0054): rows the writer sends to its errors port are counted
 *       as they go, against {@code max_errors}, and when the input ends their share is judged
 *       against {@code max_error_share} <em>before</em> the writer is told, so a write past its
 *       tolerance never commits.</li>
 * </ul>
 *
 * <p>The writer reads its batches from a row set of this node's own, one per call, as the node
 * contract says a node with an input does; everything else it asks of its context is the run's.
 */
final class WriteGuard implements Node {

    private final Node writer;
    private final WhichRow.Settings settings;
    private final WhichRow.Upserts upserts;
    private final RowErrors.Tally tally;
    private RowSet feed;
    private NodeContext inner;
    private boolean ended;

    /**
     * @param settings the upsert's rule, or null for none (any write but an upsert, or {@code last})
     * @param key      the upsert's key columns
     * @param tally    the write's error tolerance, or null for none
     */
    WriteGuard(Node writer, WhichRow.Settings settings, List<String> key, long limitMb, RowErrors.Tally tally) {
        this.writer = writer;
        this.settings = settings;
        this.upserts = settings == null ? null : new WhichRow.Upserts(settings, key, limitMb);
        this.tally = tally;
    }

    /** An upsert with its rule and no tolerance. */
    WriteGuard(Node writer, WhichRow.Settings settings, List<String> key, long limitMb) {
        this(writer, settings, key, limitMb, null);
    }

    @Override
    public String type() {
        return writer.type();
    }

    @Override
    public void init(NodeContext context) throws NodeException {
        feed = new RowSet(context.nodeName() + ".in", 1);
        inner = new Fed(context, feed, tally);
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
                if (tally != null) {
                    tally.finish(); // before the writer hears of the end, so it never commits past the share
                }
                feed.finish();
                note(context);
                return writer.process(inner);
            }
            // A batch of no rows is handed on too, as it would be without a rule: a read sends one to
            // say its columns, and the writer checks the table against them.
            VectorSchemaRoot chosen = upserts == null ? batch : upserts.choose(batch, context.allocator());
            if (tally != null) {
                tally.arrived(chosen.getRowCount()); // the rows the writer is given: those the rule leaves out are not
            }
            feed.put(chosen);
            return writer.process(inner);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NodeException("Interrupted", e);
        }
    }

    /** Says in the run's log how many rows the rule left out or let replace an earlier one. */
    private void note(NodeContext context) {
        if (upserts == null) {
            return;
        }
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

    /**
     * The run's context, with the writer's input read from the node's own row set, and the rows it
     * sends to its errors port counted against the tolerance.
     */
    private static final class Fed implements NodeContext {
        private final NodeContext run;
        private final RowSet feed;
        private final RowErrors.Tally tally;

        Fed(NodeContext run, RowSet feed, RowErrors.Tally tally) {
            this.run = run;
            this.feed = feed;
            this.tally = tally;
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
            int rows = batch.getRowCount();
            run.emit(port, batch);
            if (tally != null && RowErrors.PORT.equals(port)) {
                tally.failed(rows);
            }
        }
    }
}

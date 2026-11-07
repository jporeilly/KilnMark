package io.kilnmark.kiln.nodes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;

import io.kilnmark.kiln.Node;
import io.kilnmark.kiln.NodeCategories;
import io.kilnmark.kiln.NodeContext;
import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.NodeFactory;
import io.kilnmark.kiln.NodeKind;
import io.kilnmark.kiln.SettingSpec;
import io.kilnmark.kiln.nodes.expr.Names;
import io.kilnmark.kiln.nodes.expr.Type;

/**
 * {@code sort}: orders rows by one or more columns, each ascending or descending, with nulls last
 * unless a column says {@code nulls first}. Values order as the expression language's {@code <}
 * orders them: numbers by value, text by Unicode code point (no collation), dates and timestamps in
 * time, false before true. The sort is stable: rows with equal values keep the order they arrived in.
 *
 * <p>Memory: rows are held, with their keys, up to {@code memory_limit_mb}; past it they are sorted
 * and written to disk as a sorted run (encrypted, in a private temporary folder), and at the end the
 * runs are merged (an external merge sort, {@link ExternalSorter}). The node writes nothing until its
 * input has ended. Its files are deleted when it ends, fails or is stopped.
 */
public final class SortFactory implements NodeFactory {

    public static final String TYPE = "sort";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public NodeKind kind() {
        return NodeKind.TRANSFORM;
    }

    @Override
    public String category() {
        return NodeCategories.TRANSFORM;
    }

    @Override
    public String label() {
        return "Sort rows";
    }

    @Override
    public String help() {
        return "Orders rows by one or more columns, each ascending or descending. Rows with equal values keep their "
            + "order. Past its memory limit it sorts on disk, so any number of rows can be sorted.";
    }

    @Override
    public List<String> inputPorts() {
        return List.of("in");
    }

    @Override
    public List<String> outputPorts() {
        return List.of("out");
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
            SettingSpec.required("by", "Order by", SettingSpec.Kind.ORDER,
                "Columns to order by, the first deciding most. Add desc for largest first, and nulls first to put "
                    + "empty values first (they go last by default)."),
            SettingSpec.optional("memory_limit_mb", "Memory limit (MB)", SettingSpec.Kind.NUMBER, 256,
                "The most memory rows may take while they are sorted. Past it, sorted parts are written to disk "
                    + "and merged."));
    }

    private record Settings(List<Config.OrderTerm> by, long limitMb) {
    }

    private static Settings read(Config c) {
        List<Config.OrderTerm> by = c.order("by", true);
        long mb = c.number("memory_limit_mb", 256, 1, 1_048_576);
        return new Settings(by, mb);
    }

    @Override
    public List<String> check(Map<String, Object> config) {
        Config c = new Config(config);
        read(c);
        return c.problems();
    }

    @Override
    public Node create(Map<String, Object> config) throws NodeException {
        Config c = new Config(config);
        Settings s = read(c);
        c.orThrow(TYPE);
        return new SortNode(s.by(), s.limitMb());
    }

    @Override
    public Schema outputSchema(String port, Map<String, Schema> inputs, Map<String, Object> config)
        throws NodeException {
        if (!outputPorts().contains(port)) {
            throw new NodeException("A sort node has no output port '" + port + "'");
        }
        Config c = new Config(config);
        Settings s = read(c);
        c.orThrow(TYPE);
        Schema in = inputs.get("in");
        if (in == null) {
            return null;
        }
        codec(in, s.by(), "");
        return in;
    }

    /** The key for these columns, checked against the input's: each must exist and hold values that can be ordered. */
    static KeyCodec codec(Schema in, List<Config.OrderTerm> by, String prefix) throws NodeException {
        return codec(in, by, prefix, "by", "put in order");
    }

    /** As above, for the setting {@code key}, saying what the node does with the columns ("put in order"). */
    static KeyCodec codec(Schema in, List<Config.OrderTerm> by, String prefix, String key, String doing)
        throws NodeException {
        List<KeyCodec.Part> parts = new ArrayList<>();
        for (Config.OrderTerm t : by) {
            Field f = BaseNode.field(in, t.column());
            if (f == null) {
                String near = Names.suggest(t.column(), BaseNode.names(in));
                throw new NodeException(prefix + key + ": the input has no column '" + t.column() + "'"
                    + (near != null ? "; did you mean " + near + "?" : ""));
            }
            Type type = Type.of(f.getType());
            if (type == null) {
                throw new NodeException(prefix + key + ": the column '" + t.column() + "' holds " + f.getType()
                    + " values, which cannot be " + doing);
            }
            parts.add(new KeyCodec.Part(t.column(), type, t.descending(), t.nullsFirst()));
        }
        return new KeyCodec(parts, false);
    }

    /** The running node. */
    static final class SortNode extends BaseNode {
        private final List<Config.OrderTerm> by;
        private final long limitMb;
        private ExternalSorter sorter;
        private ExternalSorter.Sorted sorted;
        private boolean inputEnded;

        SortNode(List<Config.OrderTerm> by, long limitMb) {
            super(TYPE);
            this.by = by;
            this.limitMb = limitMb;
        }

        /** The folder its files are in, once it has spilled; for tests. */
        java.nio.file.Path spillFolder() {
            return sorter == null ? null : sorter.folder();
        }

        @Override
        public boolean process(NodeContext ctx) throws NodeException {
            try {
                if (!inputEnded) {
                    VectorSchemaRoot batch = take(ctx, "in");
                    if (batch == null) {
                        inputEnded = true;
                        if (sorter != null) {
                            sorted = sorter.finish(ctx.allocator(), false);
                            if (sorter.runsWritten() > 0) {
                                ctx.note("Sorted " + count(sorter.rows()) + " rows on disk: they took more than "
                                    + limitMb + " MB, so " + sorter.runsWritten() + " sorted parts ("
                                    + megabytesText(sorter.spilledBytes()) + ") were written to a temporary folder "
                                    + "and merged.");
                            }
                        }
                        return sorted != null;
                    }
                    add(batch, ctx);
                    return true;
                }
                ExternalSorter.Chunk c = sorted.next();
                if (c == null) {
                    return false;
                }
                put(ctx, "out", c.rows());
                return true;
            } catch (IOException e) {
                throw spillFailed(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NodeException("Interrupted", e);
            }
        }

        private void add(VectorSchemaRoot batch, NodeContext ctx) throws NodeException, IOException,
            InterruptedException {
            if (sorter == null) {
                KeyCodec codec;
                try {
                    codec = codec(batch.getSchema(), by, "");
                } catch (NodeException | RuntimeException e) {
                    batch.close();
                    throw e;
                }
                sorter = new ExternalSorter("rows", codec, ctx.allocator(), megabytes(limitMb), ctx::spilled);
            }
            sorter.add(batch);
            if (sorter.held() > megabytes(limitMb)) {
                sorter.spill();
            }
        }

        @Override
        public void close() {
            if (sorter != null) {
                sorter.close();
            }
        }
    }

    /**
     * What a failure of the temporary folder becomes: a stop when the node was being stopped (an
     * interrupt closes the file it was using), otherwise a failure that says what went wrong.
     */
    static NodeException spillFailed(IOException e) {
        if (e instanceof java.nio.channels.ClosedByInterruptException || e instanceof java.io.InterruptedIOException
            || Thread.currentThread().isInterrupted()) {
            Thread.currentThread().interrupt();
            return new NodeException("Interrupted", e);
        }
        String why = e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
        return new NodeException("could not use the temporary folder for rows that do not fit in memory: " + why, e);
    }

    /** Bytes in words: "12.3 MB". */
    static String megabytesText(long bytes) {
        return String.format(java.util.Locale.UK, "%,.1f MB", bytes / (1024.0 * 1024.0));
    }
}

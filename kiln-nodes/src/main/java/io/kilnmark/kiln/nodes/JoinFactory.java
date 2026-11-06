package io.kilnmark.kiln.nodes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;

import io.kilnmark.kiln.Node;
import io.kilnmark.kiln.NodeCategories;
import io.kilnmark.kiln.NodeContext;
import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.NodeFactory;
import io.kilnmark.kiln.NodeKind;
import io.kilnmark.kiln.RowSet;
import io.kilnmark.kiln.SettingSpec;
import io.kilnmark.kiln.nodes.KeyCodec.KeyBlock;
import io.kilnmark.kiln.nodes.expr.Names;
import io.kilnmark.kiln.nodes.expr.Type;

/**
 * {@code join}: the rows of a {@code left} and a {@code right} input whose key columns are equal,
 * as SQL joins them: {@code inner}, {@code left}, {@code right} or {@code full}. Keys match by value
 * as lookup's do (1, 1.0 and 1.00 match; a date matches a timestamp at midnight); text never matches
 * a number; a null key never matches anything, not even another null.
 *
 * <p>Every column of both inputs comes out, with its type: the left's first, then the right's. A
 * right column whose name a left column already has (ignoring case) is renamed with
 * {@code right_prefix} ({@code right_} by default). Columns of a side that may have no match are
 * nullable.
 *
 * <p>A sort-merge join: each input is sorted by its keys ({@link ExternalSorter}, sharing
 * {@code memory_limit_mb} and spilling to disk past it, so either side may be larger than memory),
 * then the two sorted streams are merged. Rows come out in order of their keys, with rows whose key
 * has a null last (the left's, then the right's); within a key, each left row in the order it
 * arrived, with the right rows it matches in the order they arrived. The right rows of one key are
 * held while the left rows of that key are joined to them, and written to disk too past a quarter
 * of the limit.
 */
public final class JoinFactory implements NodeFactory {

    public static final String TYPE = "join";
    static final List<String> HOWS = List.of("inner", "left", "right", "full");
    static final String DEFAULT_PREFIX = "right_";

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
        return NodeCategories.COMBINE;
    }

    @Override
    public String label() {
        return "Join";
    }

    @Override
    public String help() {
        return "Joins rows of the left and right inputs whose key columns are equal: inner, left, right or full. "
            + "Either input may be larger than memory. To add a few columns from a small table, a lookup is quicker.";
    }

    @Override
    public List<String> inputPorts() {
        return List.of("left", "right");
    }

    @Override
    public List<String> outputPorts() {
        return List.of("out");
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
            SettingSpec.required("on", "Match on", SettingSpec.Kind.COLUMN_MAP,
                "Key columns: each left column and the right column it must equal."),
            SettingSpec.choice("how", "Join", false, "inner",
                "Which rows are kept: inner keeps the pairs of rows that match; left also keeps left rows with no "
                    + "match, right keeps right rows with none, and full keeps both, with the other side's columns "
                    + "empty.", HOWS),
            SettingSpec.optional("right_prefix", "Right prefix", SettingSpec.Kind.TEXT, DEFAULT_PREFIX,
                "Put in front of the name of a right column that the left already has (ignoring case), so both are "
                    + "kept.").withParameters(SettingSpec.Parameters.NONE),
            SettingSpec.optional("memory_limit_mb", "Memory limit (MB)", SettingSpec.Kind.NUMBER, 256,
                "The most memory the two inputs may take while they are sorted. Past it, they are sorted on disk."));
    }

    record Settings(LinkedHashMap<String, String> on, String how, String prefix, long limitMb) {
        boolean keepLeft() {
            return how.equals("left") || how.equals("full");
        }

        boolean keepRight() {
            return how.equals("right") || how.equals("full");
        }
    }

    static Settings read(Config c) {
        LinkedHashMap<String, String> on = c.map("on", true);
        String how = c.choice("how", HOWS, "inner");
        String prefix = c.text("right_prefix", false);
        if (prefix == null) {
            prefix = DEFAULT_PREFIX;
        } else if (prefix.length() > 64 || prefix.chars().anyMatch(ch -> ch < 0x20)) {
            c.problem("right_prefix", "must be at most 64 characters, with no control characters");
        }
        long mb = c.number("memory_limit_mb", 256, 1, 1_048_576);
        return new Settings(on, how, prefix, mb);
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
        return new JoinNode(s);
    }

    @Override
    public Schema outputSchema(String port, Map<String, Schema> inputs, Map<String, Object> config)
        throws NodeException {
        if (!outputPorts().contains(port)) {
            throw new NodeException("A join node has no output port '" + port + "'");
        }
        Config c = new Config(config);
        Settings s = read(c);
        c.orThrow(TYPE);
        Schema left = inputs.get("left");
        Schema right = inputs.get("right");
        if (left == null || right == null) {
            return null;
        }
        return plan(left, right, s).out();
    }

    /** What the two inputs make: the output's columns, checked keys, and each side's key. */
    record Plan(Schema out, KeyCodec leftKey, KeyCodec rightKey) {
    }

    static Plan plan(Schema left, Schema right, Settings s) throws NodeException {
        List<KeyCodec.Part> lk = new ArrayList<>();
        List<KeyCodec.Part> rk = new ArrayList<>();
        for (Map.Entry<String, String> e : s.on().entrySet()) {
            Type lt = keyType(left, e.getKey(), "left");
            Type rt = keyType(right, e.getValue(), "right");
            if (Type.common(lt, rt) == null || !KeyCodec.matchable(lt, rt)) {
                throw new NodeException("on: the left column '" + e.getKey() + "' (" + lt.family()
                    + ") cannot be matched with the right column '" + e.getValue() + "' (" + rt.family()
                    + "); cast one of them first");
            }
            lk.add(new KeyCodec.Part(e.getKey(), lt, false, false));
            rk.add(new KeyCodec.Part(e.getValue(), rt, false, false));
        }
        List<Field> fields = new ArrayList<>();
        for (Field f : left.getFields()) {
            fields.add(s.keepRight() ? BaseNode.nullable(f) : f);
        }
        List<String> names;
        try {
            names = rightNames(BaseNode.names(left), BaseNode.names(right), s.prefix());
        } catch (IllegalArgumentException e) {
            throw new NodeException(e.getMessage(), e);
        }
        for (int i = 0; i < right.getFields().size(); i++) {
            Field f = right.getFields().get(i);
            fields.add(new Field(names.get(i), new FieldType(s.keepLeft() || f.isNullable(), f.getType(),
                f.getDictionary(), f.getMetadata()), f.getChildren()));
        }
        return new Plan(new Schema(fields), new KeyCodec(lk, true), new KeyCodec(rk, true));
    }

    /**
     * The names the right columns leave a join by, in order: a right column whose name (ignoring
     * case) the left has, or an earlier right column has already left by, gets {@code prefix} in
     * front. The one walk both the node and anything following its columns (a preview's masking,
     * marks) use, so they always agree.
     *
     * @throws IllegalArgumentException naming a clash the prefix cannot clear
     */
    public static List<String> rightNames(List<String> left, List<String> right, String prefix) {
        Set<String> taken = new HashSet<>();
        left.forEach(n -> taken.add(n.toLowerCase(Locale.ROOT)));
        Set<String> rightLow = new HashSet<>();
        right.forEach(n -> rightLow.add(n.toLowerCase(Locale.ROOT)));
        List<String> out = new ArrayList<>();
        for (String original : right) {
            String name = original;
            if (taken.contains(name.toLowerCase(Locale.ROOT))) {
                name = prefix + name;
                String low = name.toLowerCase(Locale.ROOT);
                if (taken.contains(low) || rightLow.contains(low)) {
                    throw new IllegalArgumentException("right_prefix: the right column '" + original + "' would be "
                        + "called '" + name + "', which " + (taken.contains(low) ? "the output has already"
                        : "the right input has too") + "; choose another right_prefix, or rename a column with a "
                        + "select node first");
                }
            }
            taken.add(name.toLowerCase(Locale.ROOT));
            out.add(name);
        }
        return out;
    }

    private static Type keyType(Schema in, String column, String side) throws NodeException {
        Field f = BaseNode.field(in, column);
        if (f == null) {
            String near = Names.suggest(column, BaseNode.names(in));
            throw new NodeException("on: the " + side + " input has no column '" + column + "'"
                + (near != null ? "; did you mean " + near + "?" : ""));
        }
        Type t = Type.of(f.getType());
        if (t == null) {
            throw new NodeException("on: the " + side + " column '" + column + "' holds " + f.getType()
                + " values, which cannot be matched");
        }
        return t;
    }

    /** The running node. */
    static final class JoinNode extends BaseNode {
        private final Settings settings;
        private RowSet left;
        private RowSet right;
        private Schema leftSchema;
        private Schema rightSchema;
        private ExternalSorter leftSorter;
        private ExternalSorter rightSorter;
        private boolean merged;
        /** Keys whose right rows were more than a quarter of the limit, so were held on disk. */
        private int groupsOnDisk;
        /** Right rows copied to be joined to their key's left rows (none when the right side is in memory). */
        private long groupRowsCopied;
        private Group group;

        JoinNode(Settings settings) {
            super(TYPE);
            this.settings = settings;
        }

        int groupsOnDisk() {
            return groupsOnDisk;
        }

        long groupRowsCopied() {
            return groupRowsCopied;
        }

        /** The folders its files are in, once each side has spilled; for tests. */
        List<java.nio.file.Path> spillFolders() {
            List<java.nio.file.Path> out = new ArrayList<>();
            for (ExternalSorter s : new ExternalSorter[] {leftSorter, rightSorter}) {
                if (s != null && s.folder() != null) {
                    out.add(s.folder());
                }
            }
            if (group != null && group.folder() != null) {
                out.add(group.folder());
            }
            return out;
        }

        @Override
        public void init(NodeContext ctx) throws NodeException {
            super.init(ctx);
            left = ctx.input("left");
            right = ctx.input("right");
        }

        @Override
        public boolean process(NodeContext ctx) throws NodeException {
            try {
                if (merged) {
                    return false;
                }
                if (nothingCanComeOut()) {
                    // an inner join with an empty side, or the side every row comes from empty: the
                    // other side need not be read at all
                    merged = true;
                    return false;
                }
                RowSet.Taken t = RowSet.takeAny(List.of(left, right), ctx.allocator());
                if (t != null) {
                    add(t.index() == 0, t.batch(), ctx);
                    return true;
                }
                merge(ctx);
                merged = true;
                return false;
            } catch (IOException e) {
                throw SortFactory.spillFailed(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NodeException("Interrupted", e);
            }
        }

        private long limit() {
            return megabytes(settings.limitMb());
        }

        /** Whether a side has ended with no rows where that means no row can come out. */
        private boolean nothingCanComeOut() {
            boolean leftEmpty = left.atEnd() && (leftSorter == null || leftSorter.rows() == 0);
            boolean rightEmpty = right.atEnd() && (rightSorter == null || rightSorter.rows() == 0);
            return switch (settings.how()) {
                case "inner" -> leftEmpty || rightEmpty;
                case "left" -> leftEmpty;
                case "right" -> rightEmpty;
                default -> leftEmpty && rightEmpty;
            };
        }

        /** Adds a batch to its side's sorter, checking the plan once both sides' columns are known. */
        private void add(boolean isLeft, VectorSchemaRoot batch, NodeContext ctx)
            throws NodeException, IOException, InterruptedException {
            try {
                if (isLeft && leftSchema == null) {
                    leftSchema = batch.getSchema();
                    check(ctx, false);
                } else if (!isLeft && rightSchema == null) {
                    rightSchema = batch.getSchema();
                    check(ctx, false);
                }
                if (isLeft && leftSorter == null) {
                    leftSorter = sorter("the left input", leftSchema, true, ctx);
                } else if (!isLeft && rightSorter == null) {
                    rightSorter = sorter("the right input", rightSchema, false, ctx);
                }
            } catch (NodeException | RuntimeException e) {
                batch.close();
                throw e;
            }
            (isLeft ? leftSorter : rightSorter).add(batch);
            long held = (leftSorter == null ? 0 : leftSorter.held()) + (rightSorter == null ? 0 : rightSorter.held());
            if (held > limit()) {
                ExternalSorter bigger = rightSorter == null || (leftSorter != null && leftSorter.held() >= rightSorter.held())
                    ? leftSorter : rightSorter;
                bigger.spill();
            }
        }

        private ExternalSorter sorter(String label, Schema schema, boolean isLeft, NodeContext ctx)
            throws NodeException {
            List<KeyCodec.Part> parts = new ArrayList<>();
            for (Map.Entry<String, String> e : settings.on().entrySet()) {
                String column = isLeft ? e.getKey() : e.getValue();
                parts.add(new KeyCodec.Part(column, keyType(schema, column, isLeft ? "left" : "right"), false, false));
            }
            return new ExternalSorter(label, new KeyCodec(parts, true), ctx.allocator(), limit() / 2, ctx::spilled);
        }

        /** Checks the keys and names once both sides' columns are known: the first batch of each, or what the check worked out. */
        private Plan check(NodeContext ctx, boolean ending) throws NodeException {
            Schema l = leftSchema != null ? leftSchema : ending ? ctx.inputSchema("left") : null;
            Schema r = rightSchema != null ? rightSchema : ending ? ctx.inputSchema("right") : null;
            if (l == null || r == null) {
                return null;
            }
            return plan(l, r, settings);
        }

        private void merge(NodeContext ctx) throws NodeException, IOException, InterruptedException {
            Plan plan = check(ctx, true);
            Schema l = leftSchema != null ? leftSchema : ctx.inputSchema("left");
            Schema r = rightSchema != null ? rightSchema : ctx.inputSchema("right");
            if (plan == null) {
                // a side sent nothing and its columns could not be known before the run: no row can
                // match, and its columns cannot be added
                if (settings.keepLeft() && leftSorter != null) {
                    drain(leftSorter, ctx);
                } else if (settings.keepRight() && rightSorter != null) {
                    drain(rightSorter, ctx);
                }
                return;
            }
            ExternalSorter.Sorted ls = leftSorter == null ? null : leftSorter.finish(ctx.allocator(), true);
            ExternalSorter.Sorted rs = rightSorter == null ? null : rightSorter.finish(ctx.allocator(), true);
            note(ctx);
            int leftColumns = l.getFields().size();
            try (Cursor lc = new Cursor(ls); Cursor rc = new Cursor(rs);
                 Output out = new Output(plan.out(), leftColumns, ctx);
                 Group g = new Group(r, ctx.allocator(), Math.max(1L << 20, limit() / 4))) {
                group = g;
                join(lc, rc, out, g);
                out.flush();
                groupsOnDisk = g.filesWritten;
                groupRowsCopied = g.rowsCopied;
                if (g.bytesWritten > 0) {
                    ctx.spilled(g.bytesWritten);
                    ctx.note(count(groupsOnDisk) + (groupsOnDisk == 1 ? " key had" : " keys had") + " more right rows than "
                        + "a quarter of the memory limit, so they were held on disk ("
                        + SortFactory.megabytesText(g.bytesWritten) + ") while the left rows were joined to them.");
                }
            }
        }

        private void note(NodeContext ctx) {
            long bytes = 0;
            int runs = 0;
            List<String> sides = new ArrayList<>();
            for (ExternalSorter s : new ExternalSorter[] {leftSorter, rightSorter}) {
                if (s != null && s.runsWritten() > 0) {
                    bytes += s.spilledBytes();
                    runs += s.runsWritten();
                    sides.add(s == leftSorter ? "left" : "right");
                }
            }
            if (runs > 0) {
                ctx.note("Sorted the " + String.join(" and the ", sides) + " input" + (sides.size() > 1 ? "s" : "")
                    + " on disk: the two took more than " + settings.limitMb() + " MB, so " + runs + " sorted parts ("
                    + SortFactory.megabytesText(bytes) + ") were written to a temporary folder and merged.");
            }
        }

        /** One side, in key order, with nothing from the other: when the other's columns are unknown. */
        private void drain(ExternalSorter sorter, NodeContext ctx)
            throws NodeException, IOException, InterruptedException {
            ExternalSorter.Sorted s = sorter.finish(ctx.allocator(), false);
            for (ExternalSorter.Chunk next = s.next(); next != null; next = s.next()) {
                put(ctx, "out", next.rows());
            }
        }

        /** The merge: both sides in key order, null keys last. */
        private void join(Cursor l, Cursor r, Output out, Group group)
            throws NodeException, IOException, InterruptedException {
            boolean keepLeft = settings.keepLeft();
            boolean keepRight = settings.keepRight();
            long steps = 0;
            while (l.has() && !l.nullKey()) {
                if ((++steps & 0xFFFF) == 0) {
                    ExternalSorter.checkInterrupt();
                }
                while (r.has() && !r.nullKey() && r.compare(l) < 0) {
                    if (keepRight) {
                        out.right(r.rows(), r.pos);
                    }
                    r.advance();
                }
                if (!r.has() || r.nullKey()) {
                    if (!keepLeft && !keepRight) {
                        return; // inner: nothing left on the right can match
                    }
                    if (!keepLeft) {
                        break;
                    }
                }
                if (r.has() && !r.nullKey() && r.compare(l) == 0) {
                    KeyBlock key = l.key();
                    int end = r.groupEnd(key);
                    if (end < r.rows().getRowCount()) {
                        // the right rows of this key all lie in the batch at hand: joined from it directly
                        VectorSchemaRoot rr = r.rows();
                        int from = r.pos;
                        while (l.has() && !l.nullKey() && l.sameKey(key)) {
                            for (int i = from; i < end; i++) {
                                out.both(l.rows(), l.pos, rr, i);
                            }
                            l.advance();
                        }
                        r.skipTo(end);
                        continue;
                    }
                    group.clear();
                    while (r.has() && !r.nullKey() && r.sameKey(key)) {
                        group.add(r.rows(), r.pos, r.source());
                        r.advance();
                    }
                    group.seal();
                    while (l.has() && !l.nullKey() && l.sameKey(key)) {
                        VectorSchemaRoot lr = l.rows();
                        int lp = l.pos;
                        group.forEach((root, row) -> out.both(lr, lp, root, row));
                        l.advance();
                    }
                } else {
                    if (keepLeft) {
                        out.left(l.rows(), l.pos);
                    }
                    l.advance();
                }
            }
            if (keepRight) {
                while (r.has() && !r.nullKey()) {
                    out.right(r.rows(), r.pos);
                    r.advance();
                }
            }
            if (keepLeft) {
                while (l.has()) {
                    out.left(l.rows(), l.pos);
                    l.advance();
                }
            }
            if (keepRight) {
                while (r.has()) {
                    out.right(r.rows(), r.pos);
                    r.advance();
                }
            }
        }

        @Override
        public void close() {
            if (leftSorter != null) {
                leftSorter.close();
            }
            if (rightSorter != null) {
                rightSorter.close();
            }
        }
    }

    /** A sorted side, a row at a time. */
    static final class Cursor implements AutoCloseable {
        private final ExternalSorter.Sorted src;
        private ExternalSorter.Chunk chunk;
        int pos;
        private boolean ended;

        Cursor(ExternalSorter.Sorted src) throws IOException, InterruptedException {
            this.src = src;
            ended = src == null;
            load();
        }

        private void load() throws IOException, InterruptedException {
            while (!ended && (chunk == null || pos >= chunk.rows().getRowCount())) {
                if (chunk != null) {
                    chunk.close();
                    chunk = null;
                }
                chunk = src.next();
                pos = 0;
                if (chunk == null) {
                    ended = true;
                }
            }
        }

        boolean has() {
            return !ended;
        }

        VectorSchemaRoot rows() {
            return chunk.rows();
        }

        /** Where the rows of the batch at hand are held, when they come from memory; else null. */
        ExternalSorter.RowSource source() {
            return chunk.source();
        }

        boolean nullKey() {
            return chunk.keys().isNullKey(pos);
        }

        int compare(Cursor other) {
            return KeyBlock.compare(chunk.keys(), pos, other.chunk.keys(), other.pos);
        }

        /** This row's key, copied, as a block of one. */
        KeyBlock key() {
            KeyBlock k = chunk.keys();
            int from = k.offsets()[pos];
            int to = k.offsets()[pos + 1];
            return new KeyBlock(java.util.Arrays.copyOfRange(k.data(), from, to), new int[] {0, to - from});
        }

        boolean sameKey(KeyBlock key) {
            return KeyBlock.equal(chunk.keys(), pos, key, 0);
        }

        void advance() throws IOException, InterruptedException {
            pos++;
            load();
        }

        /** Where the rows with this key end in the batch at hand: the first row after them, or the batch's size. */
        int groupEnd(KeyBlock key) {
            KeyBlock k = chunk.keys();
            int n = chunk.rows().getRowCount();
            int i = pos;
            while (i < n && KeyBlock.equal(k, i, key, 0)) {
                i++;
            }
            return i;
        }

        /** Moves to row {@code row} of the batch at hand (or past it, to the next batch). */
        void skipTo(int row) throws IOException, InterruptedException {
            pos = row;
            load();
        }

        @Override
        public void close() {
            if (chunk != null) {
                chunk.close();
                chunk = null;
            }
            if (src != null) {
                src.close();
            }
        }
    }

    /** The rows going out, a batch at a time: left columns, then right columns. */
    static final class Output implements AutoCloseable {
        private final Schema schema;
        private final int leftColumns;
        private final NodeContext ctx;
        private List<FieldVector> vectors;
        private int count;

        Output(Schema schema, int leftColumns, NodeContext ctx) {
            this.schema = schema;
            this.leftColumns = leftColumns;
            this.ctx = ctx;
        }

        private void start() {
            vectors = new ArrayList<>();
            try {
                for (Field f : schema.getFields()) {
                    FieldVector v = f.createVector(ctx.allocator());
                    vectors.add(v);
                    v.setInitialCapacity(ExternalSorter.OUT_ROWS);
                    v.allocateNew();
                }
            } catch (RuntimeException e) {
                vectors.forEach(FieldVector::close);
                vectors = null;
                throw e;
            }
            count = 0;
        }

        void left(VectorSchemaRoot l, int lr) throws NodeException {
            row(l, lr, null, 0);
        }

        void right(VectorSchemaRoot r, int rr) throws NodeException {
            row(null, 0, r, rr);
        }

        void both(VectorSchemaRoot l, int lr, VectorSchemaRoot r, int rr) throws NodeException {
            row(l, lr, r, rr);
        }

        private void row(VectorSchemaRoot l, int lr, VectorSchemaRoot r, int rr) throws NodeException {
            if (vectors == null) {
                start();
            }
            for (int c = 0; c < vectors.size(); c++) {
                FieldVector v = vectors.get(c);
                boolean isLeft = c < leftColumns;
                VectorSchemaRoot from = isLeft ? l : r;
                if (from == null) {
                    v.setNull(count);
                } else {
                    v.copyFromSafe(isLeft ? lr : rr, count, from.getVector(isLeft ? c : c - leftColumns));
                }
            }
            if (++count == ExternalSorter.OUT_ROWS) {
                flush();
            }
        }

        void flush() throws NodeException {
            if (vectors == null) {
                return;
            }
            List<FieldVector> done = vectors;
            vectors = null;
            done.forEach(v -> v.setValueCount(count));
            BaseNode.put(ctx, "out", new VectorSchemaRoot(schema.getFields(), done, count));
        }

        @Override
        public void close() {
            if (vectors != null) {
                vectors.forEach(FieldVector::close);
                vectors = null;
            }
        }
    }

    /**
     * The right rows of one key, so the left rows of that key can be joined to each of them. Rows
     * still held by the right side's sorter (it did not spill) are only pointed at; others are
     * copied, and the copies written to disk (encrypted, as runs are) once they pass {@code budget}
     * bytes, checked as each row is added.
     */
    static final class Group implements AutoCloseable {
        private static final int GROUP_ROWS = 1024;
        private static final int FIRST_CAPACITY = 64;
        private final Schema schema;
        private final BufferAllocator memory;
        private final long budget;
        private final List<VectorSchemaRoot> heldRoots = new ArrayList<>();
        private int[] heldRows = new int[16];
        private final List<VectorSchemaRoot> batches = new ArrayList<>();
        private List<FieldVector> building;
        private int buildingRows;
        private SpillFolder spill;
        private java.nio.file.Path folder;
        private java.nio.file.Path file;
        private SpillFolder.Out fileOut;
        private VectorSchemaRoot fileRoot;
        private org.apache.arrow.vector.ipc.ArrowStreamWriter writer;
        private long fileRows;
        private byte[] fileDigest;
        int filesWritten;
        long bytesWritten;
        long rowsCopied;

        interface Visitor {
            void row(VectorSchemaRoot root, int row) throws NodeException;
        }

        Group(Schema schema, BufferAllocator parent, long budget) {
            this.schema = schema;
            this.memory = parent.newChildAllocator("join-group", 0, Long.MAX_VALUE);
            this.budget = budget;
        }

        /** The folder its file goes in, once it has written one; for tests. */
        java.nio.file.Path folder() {
            return folder;
        }

        /** Whether its rows are going to disk. */
        boolean onDisk() {
            return file != null;
        }

        /** Lets every row go, and its file. Never throws. */
        void clear() {
            closeWriter();
            heldRoots.clear();
            for (VectorSchemaRoot b : batches) {
                try {
                    b.close();
                } catch (RuntimeException e) {
                    // the allocator says so when it closes
                }
            }
            batches.clear();
            if (building != null) {
                building.forEach(FieldVector::close);
                building = null;
            }
            if (file != null) {
                try {
                    java.nio.file.Files.deleteIfExists(file);
                } catch (IOException e) {
                    file.toFile().deleteOnExit();
                }
                file = null;
            }
            fileRows = 0;
            fileDigest = null;
        }

        void add(VectorSchemaRoot from, int row, ExternalSorter.RowSource source) throws IOException {
            if (source != null && building == null && batches.isEmpty() && file == null) {
                // still held by the sorter: point at it
                if (heldRoots.size() == heldRows.length) {
                    heldRows = java.util.Arrays.copyOf(heldRows, heldRows.length * 2);
                }
                heldRows[heldRoots.size()] = source.row(row);
                heldRoots.add(source.root(row));
                return;
            }
            if (building == null) {
                building = new ArrayList<>();
                for (Field f : schema.getFields()) {
                    FieldVector v = f.createVector(memory);
                    building.add(v);
                    v.setInitialCapacity(FIRST_CAPACITY);
                    v.allocateNew();
                }
                buildingRows = 0;
            }
            for (int c = 0; c < building.size(); c++) {
                building.get(c).copyFromSafe(row, buildingRows, from.getVector(c));
            }
            rowsCopied++;
            if (++buildingRows == GROUP_ROWS || (file == null && memory.getAllocatedMemory() > budget)) {
                finishBatch();
            }
        }

        private void finishBatch() throws IOException {
            if (building == null) {
                return;
            }
            building.forEach(v -> v.setValueCount(buildingRows));
            VectorSchemaRoot b = new VectorSchemaRoot(schema.getFields(), building, buildingRows);
            building = null;
            if (file != null) {
                write(b);
                return;
            }
            batches.add(b);
            if (memory.getAllocatedMemory() > budget) {
                startFile();
            }
        }

        private void startFile() throws IOException {
            if (spill == null) {
                spill = SpillFolder.create();
                folder = spill.path();
            }
            file = spill.newFile("group");
            fileOut = spill.write(file);
            fileRoot = VectorSchemaRoot.create(schema, memory);
            writer = new org.apache.arrow.vector.ipc.ArrowStreamWriter(fileRoot,
                new org.apache.arrow.vector.dictionary.DictionaryProvider.MapDictionaryProvider(),
                java.nio.channels.Channels.newChannel(fileOut));
            writer.start();
            List<VectorSchemaRoot> waiting = new ArrayList<>(batches);
            batches.clear();
            for (int i = 0; i < waiting.size(); i++) {
                try {
                    write(waiting.get(i));
                } catch (IOException | RuntimeException e) {
                    for (int j = i + 1; j < waiting.size(); j++) {
                        waiting.get(j).close();
                    }
                    throw e;
                }
            }
        }

        private void write(VectorSchemaRoot b) throws IOException {
            try (b) {
                for (int c = 0; c < schema.getFields().size(); c++) {
                    b.getVector(c).makeTransferPair(fileRoot.getVector(c)).transfer();
                }
                fileRoot.setRowCount(b.getRowCount());
                writer.writeBatch();
                fileRows += b.getRowCount();
            }
        }

        /** No more rows for this key. */
        void seal() throws IOException {
            finishBatch();
            if (writer != null) {
                writer.end();
                SpillFolder.Out o = fileOut;
                closeWriter();
                fileDigest = o.digest();
                filesWritten++;
                bytesWritten += java.nio.file.Files.size(file);
            }
        }

        /**
         * Closes the file being written, each part on its own: after a stop or a full disk the writer
         * fails again as it closes, and the rest must still be closed. Never throws.
         */
        private void closeWriter() {
            try {
                if (writer != null) {
                    writer.close();
                }
            } catch (RuntimeException e) {
                // a stop or a full disk: the file is deleted with the group
            }
            writer = null;
            try {
                if (fileRoot != null) {
                    fileRoot.close();
                }
            } catch (RuntimeException e) {
                // the allocator says so when it closes
            }
            fileRoot = null;
            try {
                if (fileOut != null) {
                    fileOut.close();
                }
            } catch (IOException | RuntimeException e) {
                // the file is deleted with the group
            }
            fileOut = null;
        }

        /** Every row, in the order added. */
        void forEach(Visitor v) throws NodeException, IOException, InterruptedException {
            for (int i = 0; i < heldRoots.size(); i++) {
                v.row(heldRoots.get(i), heldRows[i]);
            }
            if (file == null) {
                for (VectorSchemaRoot b : batches) {
                    for (int i = 0; i < b.getRowCount(); i++) {
                        v.row(b, i);
                    }
                }
                return;
            }
            long read = 0;
            try (SpillFolder.In in = spill.read(file);
                 org.apache.arrow.vector.ipc.ArrowStreamReader reader =
                     new org.apache.arrow.vector.ipc.ArrowStreamReader(in, memory)) {
                VectorSchemaRoot root = reader.getVectorSchemaRoot();
                while (reader.loadNextBatch()) {
                    for (int i = 0; i < root.getRowCount(); i++) {
                        v.row(root, i);
                    }
                    read += root.getRowCount();
                    ExternalSorter.checkInterrupt();
                }
                if (read != fileRows || !in.matches(fileDigest)) {
                    throw new IOException("A temporary file of a join's rows was cut short or changed while it was "
                        + "on disk");
                }
            }
        }

        /** Frees everything and deletes its folder, whatever went wrong before. */
        @Override
        public void close() {
            try {
                clear();
            } finally {
                try {
                    if (spill != null) {
                        spill.close();
                    }
                } finally {
                    memory.close();
                }
            }
        }
    }
}

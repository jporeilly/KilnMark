package io.kilnmark.kiln.nodes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;

import io.kilnmark.kiln.RowSet;
import io.kilnmark.kiln.SettingSpec;
import io.kilnmark.kiln.Node;
import io.kilnmark.kiln.NodeContext;
import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.NodeFactory;
import io.kilnmark.kiln.NodeKind;
import io.kilnmark.kiln.nodes.expr.ArrowIO;
import io.kilnmark.kiln.nodes.expr.ExprException;
import io.kilnmark.kiln.nodes.expr.Type;

/**
 * {@code union}: appends the rows of every input, matching columns by name. A column missing from
 * an input is null in its rows; a column whose type differs between inputs is widened to a type
 * that holds both (int32 and int64 to int64, whole numbers and decimals to decimal, and so on), or
 * is an error. With {@code strict}, every input must have the same columns.
 *
 * <p>Inputs are read as batches arrive, whichever input they come from ({@link RowSet#takeAny}).
 * The output's columns come from the inputs' columns as the pipeline check worked them out
 * ({@link NodeContext#inputSchema(String, int)}). When they are not known, batches wait in memory
 * (up to {@code memory_limit_mb}) until every input has sent its first batch or ended, and an input
 * that sends nothing adds no columns.
 *
 * <p>{@code mode: except} and {@code mode: intersect} compare instead of appending (ADR 0056): the
 * rows of the first input found in no other input, or in every other input. Whole rows are compared,
 * or the columns of {@code compare_on}; nulls are equal to each other, as in SQL's {@code EXCEPT}
 * and {@code INTERSECT}. Unlike SQL's, each row of the first input comes out or does not: rows are
 * never folded into one, so a repeat in the first input stays a repeat. Only the first input's
 * columns come out. Every input is sorted by the compared columns ({@link ExternalSorter}, sharing
 * {@code memory_limit_mb} and spilling to disk past it), then the sorted streams are walked
 * together, so rows come out in the order of those columns, nulls last.
 */
public final class UnionFactory implements NodeFactory {

    public static final String TYPE = "union";
    static final List<String> MODES = List.of("all", "except", "intersect");

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
        return io.kilnmark.kiln.NodeCategories.COMBINE;
    }

    @Override
    public String label() {
        return "Union";
    }

    @Override
    public String help() {
        return "Appends the rows of all its inputs, matching columns by name. Columns an input lacks are left "
            + "empty (null). Or, with mode, keeps the rows of the first input that the other inputs lack (except) "
            + "or have too (intersect).";
    }

    @Override
    public List<String> inputPorts() {
        return List.of("in*");
    }

    @Override
    public List<String> outputPorts() {
        return List.of("out");
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
            SettingSpec.choice("mode", "Mode", false, "all",
                "Which rows come out: all appends the rows of every input. Except keeps the rows of the first "
                    + "input found in no other input, and intersect those found in every other input, comparing "
                    + "whole rows; only the first input's rows and columns come out.", MODES),
            SettingSpec.optional("compare_on", "Compare on", SettingSpec.Kind.COLUMNS, null,
                "For except and intersect: the columns to compare, when not every column. Leave out a column only "
                    + "one input has, or one that differs on every row, such as a load time."),
            SettingSpec.optional("strict", "Same columns only", SettingSpec.Kind.BOOLEAN, false,
                "Fail unless every input has exactly the same columns, with the same types."),
            SettingSpec.optional("memory_limit_mb", "Memory limit (MB)", SettingSpec.Kind.NUMBER, 256,
                "The most memory batches may take while the union waits for every input's first batch. For except "
                    + "and intersect, the most the inputs may take while they are sorted; past it, they are sorted "
                    + "on disk."));
    }

    /** What a union is set to do. {@code compareOn} is null when whole rows are compared. */
    record Settings(String mode, List<String> compareOn, boolean strict, long limitMb) {
        boolean all() {
            return mode.equals("all");
        }

        boolean except() {
            return mode.equals("except");
        }
    }

    static Settings read(Config c) {
        String mode = c.choice("mode", MODES, "all");
        List<String> on = c.columns("compare_on", false);
        if (on != null && on.isEmpty()) {
            on = null;
        }
        if (on != null && mode.equals("all")) {
            c.problem("compare_on", "is for mode: except and mode: intersect; mode: all compares nothing");
        }
        boolean strict = c.bool("strict", false);
        long mb = c.number("memory_limit_mb", 256, 1, 1_048_576);
        return new Settings(mode, on, strict, mb);
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
        return s.all() ? new UnionNode(s.strict(), s.limitMb()) : new SetNode(s);
    }

    /**
     * Every input's columns: union has one port taking many edges, so every schema in
     * {@code inputs} is one of its inputs, keyed {@code in[0]}, {@code in[1]} ... in listed order.
     */
    @Override
    public Schema outputSchema(String port, Map<String, Schema> inputs, Map<String, Object> config)
        throws NodeException {
        if (!port.equals("out")) {
            throw new NodeException("A union node has no output port '" + port + "'");
        }
        Config c = new Config(config);
        Settings s = read(c);
        c.orThrow(TYPE);
        if (inputs.isEmpty() || inputs.containsValue(null)) {
            return null;
        }
        List<Map.Entry<String, Schema>> entries = new ArrayList<>(inputs.entrySet());
        entries.sort(java.util.Comparator.comparingInt(e -> index(e.getKey())));
        List<Schema> schemas = entries.stream().map(Map.Entry::getValue).toList();
        if (s.all()) {
            return combine(schemas, s.strict(), "");
        }
        if (schemas.size() < 2) {
            throw new NodeException("mode: " + s.mode() + " compares the first input with the others, and this union "
                + "has one input; connect another, or set mode: all");
        }
        for (int i = 1; i < schemas.size(); i++) {
            comparison(schemas.getFirst(), schemas.get(i), i, s);
        }
        // the first input's rows as they are: nothing of the other inputs comes out
        return schemas.getFirst();
    }

    /**
     * How the rows of the first input and of input {@code index} (from 0) are compared under
     * {@code except} and {@code intersect}: the key of each, over {@code compare_on} or, without it,
     * every column, which both must then have, whatever their order. A column is matched by name and
     * compared by value, as a join's keys are (1 and 1.00 are equal; text never equals a number), but
     * a null equals a null, as SQL's {@code EXCEPT} and {@code INTERSECT} count them.
     *
     * @throws NodeException saying which column cannot be compared, and what to do
     */
    static KeyCodec[] comparison(Schema first, Schema other, int index, Settings s) throws NodeException {
        String input = "input " + (index + 1);
        if (s.strict()) {
            String diff = difference(first, other);
            if (diff != null) {
                throw new NodeException("strict: " + input + " differs from input 1: " + diff);
            }
        }
        List<String> columns = s.compareOn() != null ? s.compareOn() : BaseNode.names(first);
        String key = s.compareOn() != null ? "compare_on" : "mode";
        String advice = s.compareOn() != null ? "" : "; name the columns to compare with compare_on, leaving this one out";
        if (s.compareOn() == null) {
            for (Field g : other.getFields()) {
                if (BaseNode.field(first, g.getName()) == null) {
                    throw new NodeException("mode: " + s.mode() + " compares whole rows, and " + input + " has a "
                        + "column '" + g.getName() + "' that input 1 lacks, so no row would be the same" + advice);
                }
            }
        }
        List<KeyCodec.Part> mine = new ArrayList<>();
        List<KeyCodec.Part> theirs = new ArrayList<>();
        for (String column : columns) {
            Field f = BaseNode.field(first, column);
            if (f == null) {
                String near = io.kilnmark.kiln.nodes.expr.Names.suggest(column, BaseNode.names(first));
                throw new NodeException("compare_on: input 1 has no column '" + column + "'"
                    + (near != null ? "; did you mean " + near + "?" : ""));
            }
            Field g = BaseNode.field(other, column);
            if (g == null) {
                if (s.compareOn() != null) {
                    String near = io.kilnmark.kiln.nodes.expr.Names.suggest(column, BaseNode.names(other));
                    throw new NodeException("compare_on: " + input + " has no column '" + column + "'"
                        + (near != null ? "; did you mean " + near + "?" : ""));
                }
                throw new NodeException("mode: " + s.mode() + " compares whole rows, and " + input + " has no column '"
                    + column + "', so no row would be the same" + advice);
            }
            Type a = Type.of(f.getType());
            Type b = Type.of(g.getType());
            if (a == null || b == null) {
                throw new NodeException(key + ": the column '" + column + "' holds " + (a == null ? f : g).getType()
                    + " values, which cannot be compared" + advice);
            }
            if (!KeyCodec.matchable(a, b)) {
                throw new NodeException(key + ": the column '" + column + "' is " + a.family() + " in input 1 and "
                    + b.family() + " in " + input + ", which are never equal; cast one of them with a select node "
                    + "first");
            }
            mine.add(new KeyCodec.Part(column, a, false, false));
            theirs.add(new KeyCodec.Part(column, b, false, false));
        }
        return new KeyCodec[] {KeyCodec.rows(mine), KeyCodec.rows(theirs)};
    }

    /** k for a key {@code in[k]}; keys of other forms keep their place. */
    private static int index(String key) {
        int open = key.indexOf('[');
        if (open >= 0 && key.endsWith("]")) {
            try {
                return Integer.parseInt(key.substring(open + 1, key.length() - 1));
            } catch (NumberFormatException e) {
                return Integer.MAX_VALUE;
            }
        }
        return Integer.MAX_VALUE;
    }

    /** The output columns for these inputs' columns. */
    static Schema combine(List<Schema> inputs, boolean strict, String prefix) throws NodeException {
        if (strict) {
            Schema first = inputs.getFirst();
            for (int i = 1; i < inputs.size(); i++) {
                String diff = difference(first, inputs.get(i));
                if (diff != null) {
                    throw new NodeException(prefix + "strict: input " + (i + 1) + " differs from input 1: " + diff);
                }
            }
        }
        LinkedHashMap<String, Field> fields = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> seenIn = new LinkedHashMap<>();
        for (Schema s : inputs) {
            for (Field f : s.getFields()) {
                seenIn.merge(f.getName(), 1, Integer::sum);
                Field before = fields.get(f.getName());
                if (before == null) {
                    fields.put(f.getName(), f);
                    continue;
                }
                if (before.getType().equals(f.getType())) {
                    if (f.isNullable() && !before.isNullable()) {
                        fields.put(f.getName(), BaseNode.nullable(before));
                    }
                    continue;
                }
                Type a = Type.of(before.getType());
                Type b = Type.of(f.getType());
                Type common = a == null || b == null ? null : Type.common(a, b);
                if (common == null || common.isNull()) {
                    throw new NodeException(prefix + "the column '" + f.getName() + "' is " + describe(before)
                        + " in one input and " + describe(f) + " in another, and these do not combine; cast one of "
                        + "them with a select node first");
                }
                fields.put(f.getName(), common.field(f.getName()));
            }
        }
        List<Field> out = new ArrayList<>();
        for (Field f : fields.values()) {
            boolean everywhere = seenIn.get(f.getName()) == inputs.size();
            out.add(everywhere ? f : BaseNode.nullable(f));
        }
        return new Schema(out);
    }

    private static String describe(Field f) {
        Type t = Type.of(f.getType());
        return t == null ? f.getType().toString() : t.toString();
    }

    /** How two inputs' columns differ, or null if they have the same names and types. */
    private static String difference(Schema a, Schema b) {
        for (Field f : a.getFields()) {
            Field g = BaseNode.field(b, f.getName());
            if (g == null) {
                return "it has no column '" + f.getName() + "'";
            }
            if (!Objects.equals(f.getType(), g.getType())) {
                return "the column '" + f.getName() + "' is " + describe(g) + ", not " + describe(f);
            }
        }
        for (Field g : b.getFields()) {
            if (BaseNode.field(a, g.getName()) == null) {
                return "it has an extra column '" + g.getName() + "'";
            }
        }
        return null;
    }

    /** The running node. */
    static final class UnionNode extends BaseNode {
        private final boolean strict;
        private final long limitMb;
        private List<RowSet> sources;
        private Schema[] schemas;
        private Schema[] declared;
        private final List<VectorSchemaRoot> waiting = new ArrayList<>();
        private Schema out;

        UnionNode(boolean strict, long limitMb) {
            super(TYPE);
            this.strict = strict;
            this.limitMb = limitMb;
        }

        @Override
        public void init(NodeContext ctx) throws NodeException {
            super.init(ctx);
            sources = ctx.inputs("in");
            schemas = new Schema[sources.size()];
            declared = new Schema[sources.size()];
            boolean allDeclared = true;
            for (int i = 0; i < declared.length; i++) {
                declared[i] = ctx.inputSchema("in", i);
                allDeclared &= declared[i] != null;
            }
            if (allDeclared && declared.length > 0) {
                // the pipeline check worked out every input's columns: no need to wait for first batches
                out = combine(List.of(declared), strict, "");
            }
        }

        @Override
        public boolean process(NodeContext ctx) throws NodeException {
            RowSet.Taken taken;
            try {
                // whichever input has a batch: reading them in turn stalls inputs that share a node upstream
                taken = RowSet.takeAny(sources, ctx.allocator());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NodeException("Interrupted", e);
            }
            if (taken == null) {
                if (out == null) {
                    decide();
                }
                flush(ctx);
                return false;
            }
            if (out != null) {
                put(ctx, "out", conform(taken.batch(), ctx));
                return true;
            }
            if (schemas[taken.index()] == null) {
                schemas[taken.index()] = taken.batch().getSchema();
            }
            waiting.add(taken.batch());
            if (ctx.allocator().getAllocatedMemory() > megabytes(limitMb)) {
                throw fail("batches waiting for every input's first batch take more than " + limitMb
                    + " MB; raise memory_limit_mb");
            }
            if (known()) {
                decide();
                flush(ctx);
            }
            return true;
        }

        /** Whether every input has sent a batch or ended. */
        private boolean known() {
            for (int i = 0; i < schemas.length; i++) {
                if (schemas[i] == null && !sources.get(i).atEnd()) {
                    return false;
                }
            }
            return true;
        }

        private void decide() throws NodeException {
            List<Schema> seen = new ArrayList<>();
            for (int i = 0; i < schemas.length; i++) {
                // an input that sent nothing still adds its columns, when the pipeline check knows them
                Schema s = schemas[i] != null ? schemas[i] : declared[i];
                if (s != null) {
                    seen.add(s);
                }
            }
            if (!seen.isEmpty()) {
                out = combine(seen, strict, "");
            }
        }

        private void flush(NodeContext ctx) throws NodeException {
            while (!waiting.isEmpty()) {
                VectorSchemaRoot b = waiting.removeFirst();
                put(ctx, "out", conform(b, ctx));
            }
        }

        /** The batch with the output's columns, in its order; the batch itself is closed. */
        private VectorSchemaRoot conform(VectorSchemaRoot batch, NodeContext ctx) throws NodeException {
            List<FieldVector> vectors = new ArrayList<>();
            try {
                int n = batch.getRowCount();
                for (Field f : out.getFields()) {
                    FieldVector v = batch.getVector(f.getName());
                    if (v == null) {
                        vectors.add(nulls(f, n, ctx.allocator()));
                    } else if (v.getField().getType().equals(f.getType())) {
                        vectors.add(move(v, f, ctx.allocator()));
                    } else {
                        try {
                            vectors.add(ArrowIO.write(ArrowIO.read(v), f, ctx.allocator()));
                        } catch (ExprException | IllegalArgumentException e) {
                            throw fail("the column '" + f.getName() + "' could not be converted to " + describe(f) + ": "
                                + e.getMessage());
                        }
                    }
                }
                return new VectorSchemaRoot(out, vectors, n);
            } catch (NodeException | RuntimeException e) {
                vectors.forEach(FieldVector::close);
                throw e;
            } finally {
                batch.close();
            }
        }

        @Override
        public void close() {
            waiting.forEach(VectorSchemaRoot::close);
            waiting.clear();
        }
    }

    /**
     * The running node for {@code except} and {@code intersect}: every input sorted by the compared
     * columns, then the first input walked beside the others, a row at a time.
     */
    static final class SetNode extends BaseNode {
        private final Settings settings;
        private List<RowSet> sources;
        /** Each input's columns: its first batch's, or until then what the pipeline check worked out. */
        private Schema[] schemas;
        private boolean[] seen;
        private ExternalSorter[] sorters;
        /** Batches of other inputs that came before the first input's columns were known. */
        private final List<RowSet.Taken> waiting = new ArrayList<>();
        private boolean done;

        SetNode(Settings settings) {
            super(TYPE);
            this.settings = settings;
        }

        /** The folders its files are in, once inputs have spilled; for tests. */
        List<java.nio.file.Path> spillFolders() {
            List<java.nio.file.Path> out = new ArrayList<>();
            for (ExternalSorter s : sorters) {
                if (s != null && s.folder() != null) {
                    out.add(s.folder());
                }
            }
            return out;
        }

        @Override
        public void init(NodeContext ctx) throws NodeException {
            super.init(ctx);
            sources = ctx.inputs("in");
            schemas = new Schema[sources.size()];
            seen = new boolean[sources.size()];
            sorters = new ExternalSorter[sources.size()];
            for (int i = 0; i < schemas.length; i++) {
                schemas[i] = ctx.inputSchema("in", i);
            }
        }

        @Override
        public boolean process(NodeContext ctx) throws NodeException {
            try {
                if (done) {
                    return false;
                }
                if (nothingCanComeOut()) {
                    // the first input is empty, or an input an intersect needs: the rest need not be read
                    done = true;
                    return false;
                }
                RowSet.Taken t = RowSet.takeAny(sources, ctx.allocator());
                if (t != null) {
                    add(t, ctx);
                    return true;
                }
                walk(ctx);
                done = true;
                return false;
            } catch (java.io.IOException e) {
                throw SortFactory.spillFailed(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NodeException("Interrupted", e);
            }
        }

        private long limit() {
            return megabytes(settings.limitMb());
        }

        private boolean empty(int i) {
            return sources.get(i).atEnd() && (sorters[i] == null || sorters[i].rows() == 0)
                && waiting.stream().noneMatch(w -> w.index() == i);
        }

        /** Whether an input has ended with no rows where that means no row can come out. */
        private boolean nothingCanComeOut() {
            if (sources.isEmpty() || empty(0)) {
                return true;
            }
            if (!settings.except()) {
                for (int i = 1; i < sources.size(); i++) {
                    if (empty(i)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** Adds a batch to its input's sorter; one that comes before the first input's columns are known waits. */
        private void add(RowSet.Taken t, NodeContext ctx) throws NodeException, java.io.IOException, InterruptedException {
            int i = t.index();
            if (t.batch().getRowCount() == 0) {
                t.batch().close();
                return;
            }
            if (i > 0 && schemas[0] == null) {
                waiting.add(t);
                if (ctx.allocator().getAllocatedMemory() > limit()) {
                    throw fail("batches waiting for the first input's first batch take more than " + settings.limitMb()
                        + " MB; raise memory_limit_mb");
                }
                return;
            }
            sort(t, ctx);
            if (i == 0) {
                while (!waiting.isEmpty()) {
                    sort(waiting.removeFirst(), ctx);
                }
            }
        }

        private void sort(RowSet.Taken t, NodeContext ctx) throws NodeException, java.io.IOException, InterruptedException {
            int i = t.index();
            try {
                if (!seen[i]) {
                    seen[i] = true;
                    schemas[i] = t.batch().getSchema();
                    KeyCodec codec = i == 0 ? comparison(schemas[0], schemas[0], 0, settings)[0]
                        : comparison(schemas[0], schemas[i], i, settings)[1];
                    if (i == 0) {
                        // inputs whose batches came first were checked against what the pipeline check worked out
                        for (int j = 1; j < schemas.length; j++) {
                            if (seen[j]) {
                                comparison(schemas[0], schemas[j], j, settings);
                            }
                        }
                    }
                    sorters[i] = new ExternalSorter(i == 0 ? "the first input" : "input " + (i + 1), codec,
                        ctx.allocator(), limit() / sorters.length, ctx::spilled);
                }
            } catch (NodeException | RuntimeException e) {
                t.batch().close();
                throw e;
            }
            sorters[i].add(t.batch());
            long held = 0;
            ExternalSorter biggest = null;
            for (ExternalSorter s : sorters) {
                if (s != null) {
                    held += s.held();
                    if (biggest == null || s.held() > biggest.held()) {
                        biggest = s;
                    }
                }
            }
            if (held > limit() && biggest != null) {
                biggest.spill();
            }
        }

        /** The first input in the order of the compared columns, beside each other input in that order. */
        private void walk(NodeContext ctx) throws NodeException, java.io.IOException, InterruptedException {
            if (sorters[0] == null) {
                return;
            }
            boolean except = settings.except();
            long bytes = 0;
            int runs = 0;
            List<JoinFactory.Cursor> open = new ArrayList<>();
            try (JoinFactory.Output out = new JoinFactory.Output(sorters[0].schema(),
                sorters[0].schema().getFields().size(), ctx)) {
                JoinFactory.Cursor first = new JoinFactory.Cursor(sorters[0].finish(ctx.allocator(), true));
                open.add(first);
                List<JoinFactory.Cursor> others = new ArrayList<>();
                for (int i = 1; i < sorters.length; i++) {
                    JoinFactory.Cursor c = new JoinFactory.Cursor(sorters[i] == null ? null
                        : sorters[i].finish(ctx.allocator(), true));
                    open.add(c);
                    others.add(c);
                }
                for (ExternalSorter s : sorters) {
                    if (s != null && s.runsWritten() > 0) {
                        bytes += s.spilledBytes();
                        runs += s.runsWritten();
                    }
                }
                if (runs > 0) {
                    ctx.note("Sorted the inputs on disk: they took more than " + settings.limitMb() + " MB, so " + runs
                        + " sorted parts (" + SortFactory.megabytesText(bytes) + ") were written to a temporary "
                        + "folder and merged.");
                }
                long steps = 0;
                rows:
                while (first.has()) {
                    if ((++steps & 0xFFFF) == 0) {
                        ExternalSorter.checkInterrupt();
                    }
                    int found = 0;
                    for (JoinFactory.Cursor c : others) {
                        while (c.has() && c.compare(first) < 0) {
                            c.advance();
                        }
                        if (c.has() && c.compare(first) == 0) {
                            // it stays where it is: the next row of the first input may be the same
                            found++;
                        } else if (!except && !c.has()) {
                            break rows; // intersect: an input has no more rows, so nothing more is in all of them
                        }
                    }
                    if (except ? found == 0 : found == others.size()) {
                        out.left(first.rows(), first.pos);
                    }
                    first.advance();
                }
                out.flush();
            } finally {
                open.forEach(JoinFactory.Cursor::close);
            }
        }

        @Override
        public void close() {
            waiting.forEach(w -> w.batch().close());
            waiting.clear();
            if (sorters != null) {
                for (ExternalSorter s : sorters) {
                    if (s != null) {
                        s.close();
                    }
                }
            }
        }
    }
}

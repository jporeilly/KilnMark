package io.kilnmark.server.pipelines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.MetadataEmitter;
import io.kilnmark.kiln.Node;
import io.kilnmark.kiln.NodeContext;
import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.RowSet;
import io.kilnmark.kiln.nodes.WhichRow;
import io.kilnmark.server.auth.AccessRole;

/** An upsert write's rule for which row of a repeated key wins, in front of the source's writer. */
class WriteGuardTest {

    static final Schema ROWS = new Schema(List.of(Field.nullable("id", new ArrowType.Int(64, true)),
        Field.nullable("version", new ArrowType.Int(64, true)), Field.nullable("note", ArrowType.Utf8.INSTANCE)));

    private final FakeSources sources = new FakeSources().add("warehouse", Map.of("allow_writing", "true"), "pw");
    private final WriteNodeFactory write = new WriteNodeFactory(sources);

    private Node create(Map<String, Object> config) {
        RunContext ctx = new RunContext("p", 1, "jo", AccessRole.STEWARD);
        return ctx.run(() -> {
            try {
                return write.create(config);
            } catch (NodeException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    void lastIsWhatAnUpsertDoesSoTheWriterIsUsedAsItIs() {
        Map<String, Object> upsert = Map.of("source", "warehouse", "table", "a.b", "mode", "upsert", "key",
            List.of("id"));
        assertInstanceOf(MemoryConnector.Counter.class, create(upsert));
        Map<String, Object> last = new java.util.HashMap<>(upsert);
        last.put("duplicates", "last");
        assertInstanceOf(MemoryConnector.Counter.class, create(last));
        last.put("duplicates", "latest");
        last.put("duplicates_by", "version");
        assertInstanceOf(WriteGuard.class, create(last));
    }

    @Test
    void theRuleIsCheckedAndOnlyUpsertTakesIt() {
        assertEquals(List.of(), write.check(Map.of("source", "warehouse", "table", "a.b", "mode", "upsert", "key",
            List.of("id"), "duplicates", "earliest", "duplicates_by", "version")));
        assertEquals(List.of("duplicates_by: latest needs the column that decides, such as updated_at"),
            write.check(Map.of("source", "warehouse", "table", "a.b", "mode", "upsert", "key", List.of("id"),
                "duplicates", "latest")));
        assertEquals(List.of("duplicates: only upsert has a key whose rows can repeat; append writes every row"),
            write.check(Map.of("source", "warehouse", "table", "a.b", "mode", "append", "duplicates", "first")));
        assertEquals(List.of("duplicates: one of first, last, latest, earliest, error"),
            write.check(Map.of("source", "warehouse", "table", "a.b", "mode", "upsert", "key", List.of("id"),
                "duplicates", "newest", "duplicates_by", "version")), "one problem, not a second about the column");
        assertEquals(List.of("duplicates: only upsert has a key whose rows can repeat; append writes every row"),
            write.check(Map.of("source", "warehouse", "table", "a.b", "mode", "append", "duplicates", "latest")));
        // a write's share is judged at the end, so it cannot commit along the way
        assertEquals(List.of(), write.check(Map.of("source", "warehouse", "table", "a.b", "mode", "append",
            "max_error_share", 0.01, "max_errors", 5)));
        assertTrue(write.check(Map.of("source", "warehouse", "table", "a.b", "mode", "append", "max_error_share", 0.01,
            "commit_every", 1000L)).contains("max_error_share: the share is judged when the input ends, but "
            + "commit_every commits rows before that, and they would stay; leave out commit_every, or use max_errors"));
    }

    /** Runs the rule over two batches in front of a writer that keeps what it is sent. */
    private static Run run(String rule, String by) throws NodeException {
        Recorder writer = new Recorder();
        WriteGuard node = new WriteGuard(writer, new WhichRow.Settings(WhichRow.Rule.valueOf(rule), by),
            List.of("id"), 16);
        try (RootAllocator root = new RootAllocator(Long.MAX_VALUE)) {
            BufferAllocator mine = root.newChildAllocator("node", 0, Long.MAX_VALUE);
            RowSet in = new RowSet("in", 4);
            Context ctx = new Context(in, mine);
            try {
                in.put(batch(mine, new Object[][] {{1L, 1L, "a"}, {2L, 5L, "b"}, {1L, 3L, "c"}}));
                in.put(batch(mine, new Object[][] {{1L, 2L, "d"}, {2L, 5L, "e"}, {3L, 1L, "f"}}));
                in.finish();
                node.init(ctx);
                while (node.process(ctx)) {
                    // next batch
                }
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            } finally {
                node.close();
                in.cancel(); // what a failed run left unread
                assertEquals(0, mine.getAllocatedMemory(), "the rows are all given up");
                mine.close();
            }
            return new Run(writer.notes, ctx.notes, writer.ended);
        }
    }

    record Run(List<String> written, List<String> notes, boolean ended) {
    }

    @Test
    void eachRuleSendsTheWriterRowsThatLeaveTheWinnerInTheTable() throws Exception {
        Run first = run("FIRST", null);
        assertEquals(List.of("a", "b", "f"), first.written());
        assertTrue(first.ended(), "the writer saw the end and committed");
        assertEquals(List.of("Which row wins (first): 3 rows left out because an earlier row of the same key wins"),
            first.notes());
        // c (version 3) beats a and is written over it; d and e (a tie keeps b) are left out
        Run latest = run("LATEST", "version");
        assertEquals(List.of("a", "b", "c", "f"), latest.written());
        assertEquals(List.of("Which row wins (latest by version): 2 rows left out because an earlier row of the same "
            + "key wins; 1 row written again over an earlier row of the same key that it beats"), latest.notes());
        assertEquals(List.of("a", "b", "f"), run("EARLIEST", "version").written());
    }

    @Test
    void aBatchOfNoRowsStillReachesTheWriter() throws Exception {
        Recorder writer = new Recorder();
        WriteGuard node = new WriteGuard(writer, new WhichRow.Settings(WhichRow.Rule.FIRST, null), List.of("id"),
            16);
        try (RootAllocator root = new RootAllocator(Long.MAX_VALUE)) {
            RowSet in = new RowSet("in", 2);
            in.put(batch(root, new Object[0][]));
            in.finish();
            Context ctx = new Context(in, root);
            node.init(ctx);
            while (node.process(ctx)) {
                // next batch
            }
            node.close();
        }
        assertEquals(1, writer.batches, "the writer learns the columns, and checks the table against them");
        assertTrue(writer.ended);
    }

    /** A writer that sends every row noted "bad" to its errors port and keeps the rest. */
    static final class Refuser implements Node {
        final List<String> kept = new ArrayList<>();
        boolean ended;

        @Override
        public String type() {
            return "refuser";
        }

        @Override
        public void init(NodeContext context) {
            // nothing to prepare
        }

        @Override
        public boolean process(NodeContext context) throws NodeException {
            try (VectorSchemaRoot b = context.input().take(context.allocator())) {
                if (b == null) {
                    ended = true;
                    return false;
                }
                VarCharVector note = (VarCharVector) b.getVector("note");
                List<Integer> bad = new ArrayList<>();
                for (int i = 0; i < b.getRowCount(); i++) {
                    String n = new String(note.get(i), StandardCharsets.UTF_8);
                    if (n.startsWith("bad")) {
                        bad.add(i);
                    } else {
                        kept.add(n);
                    }
                }
                if (!bad.isEmpty()) {
                    int[] rows = bad.stream().mapToInt(Integer::intValue).toArray();
                    io.kilnmark.kiln.RowErrors.Failure[] why = new io.kilnmark.kiln.RowErrors.Failure[rows.length];
                    java.util.Arrays.fill(why, new io.kilnmark.kiln.RowErrors.Failure("note", "too_long", "too long"));
                    context.emit("errors", io.kilnmark.kiln.RowErrors.batch(b, rows, why, rows.length, "w",
                        context.allocator()));
                }
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NodeException("interrupted", e);
            }
        }
    }

    /** Runs a refusing writer behind a guard with this tolerance; the error rows are counted. */
    private static Refuser tolerate(Long maxErrors, Double maxShare, Object[][]... batches) throws NodeException {
        Refuser writer = new Refuser();
        WriteGuard node = new WriteGuard(writer, null, List.of("id"), 16,
            new io.kilnmark.kiln.RowErrors.Tally(maxErrors, maxShare));
        try (RootAllocator root = new RootAllocator(Long.MAX_VALUE)) {
            BufferAllocator mine = root.newChildAllocator("node", 0, Long.MAX_VALUE);
            RowSet in = new RowSet("in", batches.length + 1);
            RowSet errors = new RowSet("errors", 100);
            Context ctx = new Context(in, mine, errors);
            try {
                for (Object[][] b : batches) {
                    in.put(batch(mine, b));
                }
                in.finish();
                node.init(ctx);
                while (node.process(ctx)) {
                    // next batch
                }
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            } finally {
                node.close();
                in.cancel();
                errors.cancel();
                assertEquals(0, mine.getAllocatedMemory(), "the rows are all given up");
                mine.close();
            }
        }
        return writer;
    }

    @Test
    void aWritesToleranceIsJudgedBeforeTheWriterCommits() throws Exception {
        Object[][] one = {{1L, 1L, "a"}, {2L, 1L, "bad 1"}, {3L, 1L, "b"}};
        Object[][] two = {{4L, 1L, "bad 2"}, {5L, 1L, "c"}};
        Refuser fine = tolerate(2L, 0.5, one, two);
        assertTrue(fine.ended);
        assertEquals(List.of("a", "b", "c"), fine.kept);
        NodeException count = assertThrows(NodeException.class, () -> tolerate(1L, null, one, two));
        assertTrue(count.getMessage().startsWith("2 of the 5 rows so far failed, more than its max_errors of 1"),
            count.getMessage());
        // 2 of 5 is 40%: past 30%, judged when the input ends, before the writer hears of it and commits
        Refuser[] seen = new Refuser[1];
        NodeException share = assertThrows(NodeException.class, () -> seen[0] = tolerate(null, 0.3, one, two));
        assertTrue(share.getMessage().startsWith("2 of 5 rows (40.00%) failed, more than its max_error_share of 30.00%"),
            share.getMessage());
    }

    @Test
    void errorStopsTheRunNamingTheKeyColumnsOnly() {
        NodeException e = assertThrows(NodeException.class, () -> run("ERROR", null));
        assertEquals("the input has more than one row with the same key (id); set duplicates to first, last, "
            + "latest or earliest to choose which row is kept", e.getMessage(), "the runner adds the node's name");
    }

    private static VectorSchemaRoot batch(BufferAllocator a, Object[][] rows) {
        VectorSchemaRoot root = VectorSchemaRoot.create(ROWS, a);
        BigIntVector id = (BigIntVector) root.getVector(0);
        BigIntVector version = (BigIntVector) root.getVector(1);
        VarCharVector note = (VarCharVector) root.getVector(2);
        root.allocateNew();
        for (int i = 0; i < rows.length; i++) {
            id.setSafe(i, (Long) rows[i][0]);
            version.setSafe(i, (Long) rows[i][1]);
            note.setSafe(i, ((String) rows[i][2]).getBytes(StandardCharsets.UTF_8));
        }
        root.setRowCount(rows.length);
        return root;
    }

    /** A writer that keeps the notes of the rows it is sent, one batch a call, as the node contract says. */
    static final class Recorder implements Node {
        final List<String> notes = new ArrayList<>();
        int batches;
        boolean ended;

        @Override
        public String type() {
            return "recorder";
        }

        @Override
        public void init(NodeContext context) {
            // nothing to prepare
        }

        @Override
        public boolean process(NodeContext context) throws NodeException {
            try (VectorSchemaRoot b = context.input().take(context.allocator())) {
                if (b == null) {
                    ended = true;
                    return false;
                }
                batches++;
                VarCharVector note = (VarCharVector) b.getVector("note");
                for (int i = 0; i < b.getRowCount(); i++) {
                    notes.add(new String(note.get(i), StandardCharsets.UTF_8));
                }
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NodeException("interrupted", e);
            }
        }
    }

    /** The run's side of a write node: its input and its log. */
    static final class Context implements NodeContext {
        private final RowSet in;
        private final BufferAllocator allocator;
        private final RowSet errors;
        final List<String> notes = new ArrayList<>();

        Context(RowSet in, BufferAllocator allocator) {
            this(in, allocator, null);
        }

        Context(RowSet in, BufferAllocator allocator, RowSet errors) {
            this.in = in;
            this.allocator = allocator;
            this.errors = errors;
        }

        @Override
        public java.util.Set<String> outputPorts() {
            return errors == null ? java.util.Set.of() : java.util.Set.of("errors");
        }

        @Override
        public RowSet output(String port) throws NodeException {
            if ("errors".equals(port) && errors != null) {
                return errors;
            }
            return NodeContext.super.output(port);
        }

        @Override
        public String nodeName() {
            return "w";
        }

        @Override
        public Map<String, String> settings() {
            return Map.of();
        }

        @Override
        public MetadataEmitter metadata() {
            return e -> { };
        }

        @Override
        public BufferAllocator allocator() {
            return allocator;
        }

        @Override
        public RowSet input() {
            return in;
        }

        @Override
        public RowSet output() {
            return null;
        }

        @Override
        public boolean hasInput() {
            return true;
        }

        @Override
        public boolean hasOutput() {
            return false;
        }

        @Override
        public void note(String text) {
            notes.add(text);
        }
    }
}

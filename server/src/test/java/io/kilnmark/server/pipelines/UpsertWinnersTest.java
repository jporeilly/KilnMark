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
class UpsertWinnersTest {

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
        assertInstanceOf(UpsertWinners.class, create(last));
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
    }

    /** Runs the rule over two batches in front of a writer that keeps what it is sent. */
    private static Run run(String rule, String by) throws NodeException {
        Recorder writer = new Recorder();
        UpsertWinners node = new UpsertWinners(writer, new WhichRow.Settings(WhichRow.Rule.valueOf(rule), by),
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
        UpsertWinners node = new UpsertWinners(writer, new WhichRow.Settings(WhichRow.Rule.FIRST, null), List.of("id"),
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
        final List<String> notes = new ArrayList<>();

        Context(RowSet in, BufferAllocator allocator) {
            this.in = in;
            this.allocator = allocator;
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

package io.kilnmark.kiln.nodes;

import static io.kilnmark.kiln.nodes.Harness.INT64;
import static io.kilnmark.kiln.nodes.Harness.UTF8;
import static io.kilnmark.kiln.nodes.Harness.field;
import static io.kilnmark.kiln.nodes.Harness.row;
import static io.kilnmark.kiln.nodes.Harness.schema;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.nodes.expr.Type;

/** The sorter under sort and join: runs on disk, merged in several passes, encrypted, and deleted. */
class ExternalSorterTest {

    static final Schema ROWS = schema(field("k", INT64), field("seq", INT64), field("note", UTF8));
    static final String MARKER = "PLAINTEXT-MARKER-7f3a";

    private static KeyCodec byK() {
        return new KeyCodec(List.of(new KeyCodec.Part("k", Type.INT64, false, false)), false);
    }

    @Test
    void mergesManyRunsInSeveralPassesAndKeepsEqualRowsInOrder() throws Exception {
        AtomicLong spilled = new AtomicLong();
        try (Harness h = new Harness()) {
            List<Map<String, Object>> got = new ArrayList<>();
            Path folder;
            List<Path> seen = new ArrayList<>();
            try (ExternalSorter s = new ExternalSorter("rows", byK(), h.nodeMemory, 1 << 20, spilled::addAndGet, 3)) {
                long seq = 0;
                for (int run = 0; run < 20; run++) {
                    Object[][] rows = new Object[50][];
                    for (int i = 0; i < rows.length; i++) {
                        rows[i] = row((long) ((i * 7 + run) % 5), seq++, MARKER);
                    }
                    s.add(Harness.batch(h.nodeMemory, ROWS, rows));
                    s.spill(); // one run per batch: 20 runs, merged three at a time
                    folder = s.folder();
                    try (Stream<Path> files = Files.list(folder)) {
                        files.forEach(seen::add);
                    }
                    for (Path p : seen) {
                        if (Files.exists(p)) {
                            byte[] bytes = Files.readAllBytes(p);
                            assertFalse(new String(bytes, StandardCharsets.ISO_8859_1).contains(MARKER),
                                "rows on disk are encrypted");
                        }
                    }
                }
                assertEquals(20, s.runsWritten());
                ExternalSorter.Sorted sorted = s.finish(h.nodeMemory, true);
                assertTrue(s.runsWritten() > 20, "runs were merged into bigger ones first");
                for (ExternalSorter.Chunk next = sorted.next(); next != null; next = sorted.next()) {
                    try (ExternalSorter.Chunk c = next) {
                        got.addAll(Harness.rowsOf(c.rows()));
                        assertEquals(c.rows().getRowCount(), c.keys().rows());
                    }
                }
                folder = s.folder();
            }
            assertEquals(1000, got.size());
            for (int i = 1; i < got.size(); i++) {
                long k0 = (Long) got.get(i - 1).get("k");
                long k1 = (Long) got.get(i).get("k");
                assertTrue(k0 < k1 || (k0 == k1 && (Long) got.get(i - 1).get("seq") < (Long) got.get(i).get("seq")),
                    "row " + i + " is out of order: " + got.get(i - 1) + " then " + got.get(i));
            }
            assertTrue(spilled.get() > 0);
            assertFalse(Files.exists(folder), "the folder is deleted when the sorter closes");
            assertTrue(seen.stream().noneMatch(Files::exists));
        }
    }

    @Test
    void closingBeforeTheEndDeletesEverything() throws Exception {
        try (Harness h = new Harness()) {
            Path folder;
            try (ExternalSorter s = new ExternalSorter("rows", byK(), h.nodeMemory, 1 << 20, n -> { }, 2)) {
                for (int run = 0; run < 5; run++) {
                    s.add(Harness.batch(h.nodeMemory, ROWS, row(5L - run, (long) run, "x"), row(1L, 10L + run, "y")));
                    s.spill();
                }
                s.add(Harness.batch(h.nodeMemory, ROWS, row(0L, 99L, "held")));
                ExternalSorter.Sorted sorted = s.finish(h.nodeMemory, false);
                try (ExternalSorter.Chunk c = sorted.next()) {
                    assertTrue(c.rows().getRowCount() > 0);
                }
                folder = s.folder();
                assertTrue(Files.exists(folder));
            }
            assertFalse(Files.exists(folder));
            assertEquals(0, h.nodeMemory.getAllocatedMemory());
        }
    }
}

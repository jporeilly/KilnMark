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
                long first = s.spilledBytes();
                ExternalSorter.Sorted sorted = s.finish(h.nodeMemory, true);
                // neighbours merged a pass at a time: 20 runs make 7, then 3, so every row is written
                // once a pass (about three times in all), not again and again in a growing first run
                assertEquals(29, s.runsWritten());
                assertTrue(s.spilledBytes() < 3.3 * first, s.spilledBytes() + " bytes written for " + first);
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

    @Test
    void aRunCutShortOrChangedOnDiskFailsLoudly() throws Exception {
        for (String harm : List.of("flip", "truncate")) {
            try (Harness h = new Harness()) {
                Exception failure = null;
                try (ExternalSorter s = new ExternalSorter("rows", byK(), h.nodeMemory, 1 << 20, n -> { }, 4)) {
                    for (int run = 0; run < 3; run++) {
                        Object[][] rows = new Object[500][];
                        for (int i = 0; i < rows.length; i++) {
                            rows[i] = row((long) i, (long) i, "note-" + "x".repeat(40) + i);
                        }
                        s.add(Harness.batch(h.nodeMemory, ROWS, rows));
                        s.spill();
                    }
                    Path victim;
                    try (Stream<Path> files = Files.list(s.folder())) {
                        victim = files.sorted().findFirst().orElseThrow();
                    }
                    byte[] bytes = Files.readAllBytes(victim);
                    if (harm.equals("flip")) {
                        bytes[bytes.length / 2] ^= 0x10;
                        Files.write(victim, bytes);
                    } else {
                        Files.write(victim, java.util.Arrays.copyOf(bytes, bytes.length / 2));
                    }
                    try {
                        ExternalSorter.Sorted sorted = s.finish(h.nodeMemory, false);
                        for (ExternalSorter.Chunk next = sorted.next(); next != null; next = sorted.next()) {
                            next.close();
                        }
                    } catch (java.io.IOException | RuntimeException e) {
                        failure = e;
                    }
                }
                assertTrue(failure != null, harm + ": a damaged run must not pass quietly");
                if (harm.equals("flip")) {
                    assertTrue(failure instanceof java.io.IOException || failure instanceof RuntimeException,
                        String.valueOf(failure));
                } else {
                    assertEquals("A temporary file of sorted rows was cut short or changed while it was on disk",
                        failure.getMessage());
                }
            }
        }
    }

    @Test
    void wideRowsComeBackInBatchesBoundedByBytes() throws Exception {
        String wide = "w".repeat(20_000);
        for (boolean spill : List.of(false, true)) {
            try (Harness h = new Harness()) {
                try (ExternalSorter s = new ExternalSorter("rows", byK(), h.nodeMemory, 1 << 20, n -> { }, 4)) {
                    for (int b = 0; b < 4; b++) {
                        Object[][] rows = new Object[20][];
                        for (int i = 0; i < rows.length; i++) {
                            rows[i] = row((long) (b * 20 + i), 0L, wide);
                        }
                        s.add(Harness.batch(h.nodeMemory, ROWS, rows));
                        if (spill) {
                            s.spill();
                        }
                    }
                    ExternalSorter.Sorted sorted = s.finish(h.nodeMemory, false);
                    int total = 0;
                    for (ExternalSorter.Chunk next = sorted.next(); next != null; next = sorted.next()) {
                        try (ExternalSorter.Chunk c = next) {
                            int n = c.rows().getRowCount();
                            total += n;
                            // 1 MB / 8 over rows of 20 KB: at most 6 rows a batch
                            assertTrue(n <= 6, (spill ? "merged" : "in memory") + ": " + n + " rows of 20 KB in one batch");
                        }
                    }
                    assertEquals(80, total);
                }
            }
        }
    }

    @Test
    void aFolderWhoseLockCanBeTakenIsStaleAndOneHeldIsNot() throws Exception {
        Path base = Files.createTempDirectory("spill-clean-test");
        try {
            Path gone = Files.createDirectory(base.resolve(SpillFolder.PREFIX + "1-a"));
            Files.writeString(gone.resolve(SpillFolder.LOCK), "");
            Files.writeString(gone.resolve("left-behind.arrow"), "x");
            Path held = Files.createDirectory(base.resolve(SpillFolder.PREFIX + "1-b"));
            Path fresh = Files.createDirectory(base.resolve(SpillFolder.PREFIX + "1-c"));
            Path old = Files.createDirectory(base.resolve(SpillFolder.PREFIX + "1-d"));
            Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.from(
                java.time.Instant.now().minus(SpillFolder.UNLOCKED_GRACE).minusSeconds(60)));
            try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(held.resolve(SpillFolder.LOCK),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
                 java.nio.channels.FileLock lock = ch.lock()) {
                assertTrue(lock.isValid());
                // every one says process 1, as in a container: only the locks tell them apart
                SpillFolder.cleanStale(base);
                assertFalse(Files.exists(gone), "its lock could be taken: its process has gone");
                assertTrue(Files.exists(held), "its lock is held: its process is running");
                assertTrue(Files.exists(fresh), "no lock yet, but new: its process may be making it");
                assertFalse(Files.exists(old), "no lock, and old");
            }
        } finally {
            SpillFolder.deleteTree(base);
        }
    }

    @Test
    void aFolderLockedByAnotherProcessIsNotStale() throws Exception {
        Path base = Files.createTempDirectory("spill-clean-other");
        Process holder = null;
        try {
            Path other = Files.createDirectory(base.resolve(SpillFolder.PREFIX + "1-other"));
            Path lockFile = other.resolve(SpillFolder.LOCK);
            Path program = base.resolve("HoldLock.java");
            Files.writeString(program, """
                import java.nio.channels.FileChannel;
                import java.nio.file.Path;
                import java.nio.file.StandardOpenOption;
                public class HoldLock {
                    public static void main(String[] args) throws Exception {
                        try (FileChannel ch = FileChannel.open(Path.of(args[0]), StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE); var lock = ch.lock()) {
                            System.out.println("locked");
                            System.out.flush();
                            System.in.read();
                        }
                    }
                }
                """);
            String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            holder = new ProcessBuilder(javaBin, program.toString(), lockFile.toString()).redirectErrorStream(true).start();
            java.io.BufferedReader out = new java.io.BufferedReader(new java.io.InputStreamReader(holder.getInputStream(),
                StandardCharsets.UTF_8));
            assertEquals("locked", out.readLine());
            assertFalse(SpillFolder.stale(other), "another running process holds its lock");
            SpillFolder.cleanStale(base);
            assertTrue(Files.exists(other));
            holder.getOutputStream().close();
            assertTrue(holder.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(SpillFolder.stale(other), "once that process has gone, its lock can be taken");
        } finally {
            if (holder != null) {
                holder.destroyForcibly();
            }
            SpillFolder.deleteTree(base);
        }
    }

    @Test
    void aSpillFolderIsTheServersUsersOnly() throws Exception {
        Path base = Files.createTempDirectory("spill-private");
        try {
            Path dir = SpillFolder.privateFolder(base, "node-");
            if (Files.getFileStore(dir).supportsFileAttributeView("posix")) {
                assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(dir)));
            } else {
                var acl = Files.getFileAttributeView(dir, java.nio.file.attribute.AclFileAttributeView.class).getAcl();
                assertEquals(1, acl.size(), acl.toString());
                assertEquals(Files.getOwner(dir), acl.getFirst().principal());
            }
        } finally {
            SpillFolder.deleteTree(base);
        }
    }

    @Test
    void aStopWhileWritingIsAStopNotAFailure() {
        io.kilnmark.kiln.NodeException e = SortFactory.spillFailed(new java.nio.channels.ClosedByInterruptException());
        assertEquals("Interrupted", e.getMessage());
        assertTrue(Thread.interrupted(), "the interrupt is kept");
        assertEquals("could not use the temporary folder for rows that do not fit in memory: disk full",
            SortFactory.spillFailed(new java.io.IOException("disk full")).getMessage());
        assertEquals("could not use the temporary folder for rows that do not fit in memory: IOException",
            SortFactory.spillFailed(new java.io.IOException()).getMessage());
    }
}

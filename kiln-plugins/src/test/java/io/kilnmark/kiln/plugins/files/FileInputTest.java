package io.kilnmark.kiln.plugins.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import dev.hardwood.InputFile;
import io.kilnmark.kiln.Pipeline;
import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.Runner;
import io.kilnmark.kiln.NodeSpec;
import io.kilnmark.kiln.plugins.jdbc.JdbcColumn.Kind;

class FileInputTest {

    /** A store whose files fail part-way through, counting the streams it opened and those closed. */
    static final class FailingStore implements FileStore {

        final AtomicInteger opened = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();
        final AtomicInteger cancelled = new AtomicInteger();
        final byte[] start;

        FailingStore(String start) {
            this.start = start.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public List<Entry> list() {
            return List.of();
        }

        @SuppressWarnings("InputStreamSlowMultibyteRead") // a few bytes, then a failure: speed does not matter
        @Override
        public InputStream open(String path) {
            opened.incrementAndGet();
            return new InputStream() {
                private int pos;

                @Override
                public int read() throws IOException {
                    if (pos < start.length) {
                        return start[pos++];
                    }
                    throw new IOException("connection reset");
                }

                @Override
                public void close() {
                    closed.incrementAndGet();
                }
            };
        }

        @Override
        public byte[] head(String path, int bytes) {
            return start.clone();
        }

        @Override
        public InputFile parquet(String path, long size) throws IOException {
            throw new IOException("not here");
        }

        @Override
        public String describe(String path) {
            return "fake://" + path;
        }

        @Override
        public void cancel() {
            cancelled.incrementAndGet();
        }
    }

    private static RunResult read(FailingStore store, String path, String format, List<FileColumn> columns)
        throws InterruptedException {
        String urn = "urn:kilnmark:fake:" + path;
        return new Runner(16L << 20, 2).run(new Pipeline("read", List.of(new NodeSpec("read",
            new FileInput(() -> store, columns), Map.of("path", path, "format", format, "encoding", "UTF-8",
                "asset-urn", urn)))), e -> { });
    }

    @Test
    void aFailureWhileReadingTheHeaderClosesTheStream() throws Exception {
        FailingStore store = new FailingStore("a,b"); // the header line never ends
        RunResult r = read(store, "t.csv", "csv", List.of(FileColumn.inferred("a", Kind.TEXT, 0),
            FileColumn.inferred("b", Kind.TEXT, 0)));
        assertEquals(RunResult.Status.FAILED, r.status());
        assertTrue(r.error().contains("connection reset"), r.error());
        assertEquals(store.opened.get(), store.closed.get(), "every stream opened was closed");
        assertTrue(store.opened.get() > 0);
        assertEquals(1, store.cancelled.get(), "transfers are stopped when the node closes");
    }

    @Test
    void aFailureWhileReadingJsonClosesTheStream() throws Exception {
        FailingStore store = new FailingStore("{\"a\":1}\n{\"a\":");
        RunResult r = read(store, "t.ndjson", "jsonl", List.of(FileColumn.inferred("a", Kind.INT64, 0)));
        assertEquals(RunResult.Status.FAILED, r.status());
        assertEquals(store.opened.get(), store.closed.get());
    }

    @Test
    void aFileThatCannotBeOpenedFailsWithItsReason() throws Exception {
        FailingStore store = new FailingStore("");
        RunResult r = read(store, "t.parquet", "parquet", List.of());
        assertEquals(RunResult.Status.FAILED, r.status());
        assertTrue(r.error().contains("Cannot read fake://t.parquet: not here"), r.error());
    }
}

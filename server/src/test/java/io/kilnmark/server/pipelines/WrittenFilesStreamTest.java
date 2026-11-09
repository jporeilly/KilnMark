package io.kilnmark.server.pipelines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kilnmark.kiln.plugins.files.FilesConnector;

/**
 * Sending a written file (ADR 0055): a buffer at a time, so a file larger than the server's whole
 * memory goes out without being held; never once it is no longer the file that was checked; and
 * named and typed so a browser saves it and never runs it.
 */
class WrittenFilesStreamTest {

    private static FilesConnector.LocalFile checked(Path file) throws IOException {
        BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return new FilesConnector.LocalFile(file, a.size(), a.lastModifiedTime().toInstant(), a.fileKey());
    }

    /** Counts what is written, keeping none of it. */
    private static final class Counting extends OutputStream {
        long bytes;
        int largestWrite;

        @Override
        public void write(int b) {
            bytes++;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            bytes += len;
            largestWrite = Math.max(largestWrite, len);
        }
    }

    @Test
    void aFileLargerThanTheHeapIsStreamedNotHeld(@TempDir Path dir) throws IOException {
        // Sparse, so it takes no disk: larger than the most this JVM may hold, so loading it would fail.
        long size = Runtime.getRuntime().maxMemory() + (64L << 20);
        Path big = dir.resolve("big.parquet");
        try (SeekableByteChannel c = Files.newByteChannel(big, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
            StandardOpenOption.SPARSE)) {
            c.position(size - 1);
            c.write(ByteBuffer.wrap(new byte[] {7}));
        }
        Counting out = new Counting();
        WrittenFiles.copy(checked(big), out);
        assertEquals(size, out.bytes);
        assertTrue(out.largestWrite <= 1 << 16, "a buffer at a time: " + out.largestWrite);
    }

    @Test
    void aFileChangedSinceItWasCheckedIsNotSent(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("a.csv");
        Files.writeString(f, "id\n1\n");
        FilesConnector.LocalFile was = checked(f);
        Files.writeString(f, "id\n1\n2\n");
        Counting out = new Counting();
        assertThrows(IOException.class, () -> WrittenFiles.copy(was, out));
        assertEquals(0, out.bytes);
        Files.delete(f);
        assertThrows(IOException.class, () -> WrittenFiles.copy(was, out));
    }

    @Test
    void theWholeCheckedFileIsSent(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("a.csv");
        Files.writeString(f, "id\n1\n");
        FilesConnector.LocalFile was = checked(f);
        Counting out = new Counting();
        WrittenFiles.copy(was, out);
        assertEquals(5, out.bytes);
    }

    @Test
    void itIsNamedAndTypedToBeSaved() {
        assertEquals("orders_named.csv", WrittenFiles.filename("starter-pack/samples/combine/orders_named.csv"));
        assertEquals("a_b_c_.csv", WrittenFiles.filename("x/a\"b;cé.csv"));
        assertEquals("_env.csv", WrittenFiles.filename("x/.env.csv"));
        assertEquals("download", WrittenFiles.filename("x/"));
        String longName = WrittenFiles.filename("x/" + "a".repeat(300) + ".parquet");
        assertEquals(120, longName.length());
        assertTrue(longName.endsWith(".parquet"));
        assertEquals("text/csv; charset=utf-8", WrittenFiles.contentType("a/b.CSV"));
        assertEquals("application/vnd.apache.parquet", WrittenFiles.contentType("a/b.parquet"));
        assertEquals("application/json", WrittenFiles.contentType("a/b.json"));
        assertEquals("application/octet-stream", WrittenFiles.contentType("a/b.html"));
        assertEquals("application/octet-stream", WrittenFiles.contentType("a/b"));
    }
}

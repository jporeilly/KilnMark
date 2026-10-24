package io.kilnmark.kiln.plugins.files;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import io.kilnmark.kiln.plugins.documents.DocumentException;
import io.kilnmark.kiln.plugins.documents.DocumentRuntime;
import io.kilnmark.kiln.plugins.documents.DocumentTypes;

/**
 * A document as a file on this server's disk, which the document parsers need (PDF and Office
 * files are read by seeking, not front to back). A folder's files are used where they are; an
 * object from S3 is streamed into a private temporary folder, never into memory, and deleted when
 * the copy is closed. Copies stop at the size limit.
 */
final class LocalCopy implements AutoCloseable {

    private final Path path;
    private final boolean temporary;

    private LocalCopy(Path path, boolean temporary) {
        this.path = path;
        this.temporary = temporary;
    }

    Path path() {
        return path;
    }

    /**
     * @throws DocumentException when the file is larger than {@code maxBytes}
     */
    static LocalCopy of(FileStore store, String path, long maxBytes) throws IOException, DocumentException {
        Path local = store.localPath(path);
        if (local != null) {
            long size = Files.size(local);
            if (size > maxBytes) {
                throw tooLarge(size, maxBytes);
            }
            return new LocalCopy(local, false);
        }
        String ext = DocumentTypes.extension(path);
        Path tmp = Files.createTempFile(DocumentRuntime.tempDir(), "doc-", ext.isEmpty() ? ".bin" : "." + ext);
        boolean ok = false;
        try (InputStream in = store.open(path); OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            long n = 0;
            int r;
            while ((r = in.read(buf)) > 0) {
                n += r;
                if (n > maxBytes) {
                    throw tooLarge(-1, maxBytes);
                }
                out.write(buf, 0, r);
            }
            ok = true;
        } finally {
            if (!ok) {
                Files.deleteIfExists(tmp);
            }
        }
        return new LocalCopy(tmp, true);
    }

    /** @param size the file's size, or -1 when only known to be over the limit */
    static DocumentException tooLarge(long size, long maxBytes) {
        String setting = DocumentRuntime.limits().maxLegacyBytes() == maxBytes
            ? "kilnmark.documents.max-legacy-file-mb" : "kilnmark.documents.max-file-mb";
        return new DocumentException("it is " + (size < 0 ? "larger than " + mb(maxBytes) : mb(size))
            + ", over the " + mb(maxBytes) + " limit for reading this kind of file (an administrator can change "
            + setting + ")");
    }

    static String mb(long bytes) {
        if (bytes % (1 << 20) == 0) {
            return (bytes >> 20) + " MB";
        }
        return bytes >= (1 << 20) ? String.format("%.1f MB", bytes / 1048576.0) : String.format("%.0f KB",
            Math.ceil(bytes / 1024.0));
    }

    @Override
    public void close() throws IOException {
        if (temporary) {
            Files.deleteIfExists(path);
        }
    }
}

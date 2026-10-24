package io.kilnmark.kiln.plugins.files;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.kilnmark.kiln.plugins.documents.DocumentException;
import io.kilnmark.kiln.plugins.documents.DocumentRuntime;
import io.kilnmark.kiln.plugins.documents.DocumentTypes;

/**
 * A document as a file on this server's disk, which the document parsers need (PDF and Office
 * files are read by seeking, not front to back). A folder's files are used where they are; an
 * object from S3 is streamed into a private temporary folder, never into memory, stopping at the
 * size limit.
 *
 * <p>Copies of remote files are shared: within a scan a workbook is needed to list its sheets,
 * to read each sheet and to read its text, and is fetched once for all of them. A copy is found
 * again by where it came from and its version (size and time from the listing), and deleted when
 * nothing has used it for {@value #IDLE_MINUTES} minutes, or sooner when the copies kept add up to
 * more than {@value #MAX_KEPT_MB} MB.
 */
final class LocalCopy implements AutoCloseable {

    static final int IDLE_MINUTES = 10;
    static final int MAX_KEPT_MB = 2048;

    /** A copy kept for others to use. */
    private static final class Kept {
        final Path file;
        final long bytes;
        int users;
        long lastUsed = System.nanoTime();

        Kept(Path file, long bytes) {
            this.file = file;
            this.bytes = bytes;
        }
    }

    private static final Map<String, Kept> KEPT = new LinkedHashMap<>();

    private final Path path;
    private final boolean temporary;
    private final Kept kept;
    private boolean closed;

    private LocalCopy(Path path, boolean temporary, Kept kept) {
        this.path = path;
        this.temporary = temporary;
        this.kept = kept;
    }

    Path path() {
        return path;
    }

    /** The version of a listed file: its size and time, enough to tell a changed object apart. */
    static String version(FileStore.Entry e) {
        return e.size() + "|" + (e.modified() == null ? "" : e.modified().toString());
    }

    /** A copy used only by the caller. */
    static LocalCopy of(FileStore store, String path, long maxBytes) throws IOException, DocumentException {
        return of(store, path, maxBytes, null);
    }

    /**
     * @param version the file's version as {@link #version} gives it, to share the copy; null or
     *                without a time for a copy used only by the caller
     * @throws DocumentException when the file is larger than {@code maxBytes}
     */
    static LocalCopy of(FileStore store, String path, long maxBytes, String version)
        throws IOException, DocumentException {
        Path local = store.localPath(path);
        if (local != null) {
            long size = Files.size(local);
            if (size > maxBytes) {
                throw tooLarge(size, maxBytes);
            }
            return new LocalCopy(local, false, null);
        }
        boolean share = version != null && !version.endsWith("|");
        String key = store.copyKey(path) + "\n" + version;
        if (share) {
            synchronized (KEPT) {
                evict();
                Kept k = KEPT.get(key);
                if (k != null && Files.exists(k.file)) {
                    k.users++;
                    return new LocalCopy(k.file, false, k);
                }
            }
        }
        Path tmp = copy(store, path, maxBytes);
        if (!share) {
            return new LocalCopy(tmp, true, null);
        }
        synchronized (KEPT) {
            Kept k = KEPT.get(key);
            if (k != null && Files.exists(k.file)) { // another thread fetched it meanwhile
                Files.deleteIfExists(tmp);
            } else {
                k = new Kept(tmp, Files.size(tmp));
                KEPT.put(key, k);
            }
            k.users++;
            return new LocalCopy(k.file, false, k);
        }
    }

    private static Path copy(FileStore store, String path, long maxBytes) throws IOException, DocumentException {
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
        return tmp;
    }

    /** Deletes copies nobody uses that have been idle too long, or the oldest when too many are kept. */
    private static void evict() {
        long now = System.nanoTime();
        long idle = Duration.ofMinutes(IDLE_MINUTES).toNanos();
        long total = 0;
        for (Kept k : KEPT.values()) {
            total += k.bytes;
        }
        List<Path> gone = new ArrayList<>();
        for (Iterator<Kept> it = KEPT.values().iterator(); it.hasNext(); ) {
            Kept k = it.next();
            if (k.users == 0 && (now - k.lastUsed > idle || total > ((long) MAX_KEPT_MB << 20))) {
                total -= k.bytes;
                gone.add(k.file);
                it.remove();
            }
        }
        for (Path p : gone) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                // the folder is cleaned when the server starts again
            }
        }
    }

    /** Copies kept for sharing (tests). */
    static int kept() {
        synchronized (KEPT) {
            return KEPT.size();
        }
    }

    /** Drops every shared copy nobody is using (tests). */
    static void dropIdle() {
        synchronized (KEPT) {
            for (Kept k : KEPT.values()) {
                k.lastUsed = 0;
            }
            evict();
        }
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
        if (closed) {
            return;
        }
        closed = true;
        if (kept != null) {
            synchronized (KEPT) {
                kept.users--;
                kept.lastUsed = System.nanoTime();
            }
        } else if (temporary) {
            Files.deleteIfExists(path);
        }
    }
}

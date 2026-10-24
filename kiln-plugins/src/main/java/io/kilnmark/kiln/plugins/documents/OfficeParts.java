package io.kilnmark.kiln.plugins.documents;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Checks an Office Open XML file (a ZIP archive) before any parser opens it. Parsers keep a
 * workbook's shared strings, styles and workbook part whole in memory, and size them by what the
 * archive declares, which a hostile file can understate. So those parts are expanded here and their
 * real size counted, stopping at the limit; a file whose parts are larger is not read.
 */
public final class OfficeParts {

    /** The most entries looked at; real files have tens. */
    static final int MAX_ENTRIES = 10_000;

    private static final Set<String> OOXML = Set.of("docx", "docm", "dotx", "xlsx", "xlsm", "xltx", "pptx", "pptm",
        "potx");

    private OfficeParts() {
    }

    /** Whether a file with this name is Office Open XML. */
    public static boolean ooxml(String name) {
        return OOXML.contains(DocumentTypes.extension(name));
    }

    /**
     * @throws DocumentException when a shared strings, styles or workbook part expands past
     *                           {@code limit} bytes
     */
    public static void check(Path file, long limit) throws DocumentException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            for (int n = 0; entries.hasMoreElements() && n < MAX_ENTRIES; n++) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory() || !guarded(e.getName())) {
                    continue;
                }
                long real;
                try (InputStream in = zip.getInputStream(e)) {
                    real = count(in, limit);
                }
                if (real > limit) {
                    throw new DocumentException("its part " + e.getName() + " expands to more than "
                        + DocumentLimits.mb(limit) + ", more than Kilnmark reads (kilnmark.documents.max-part-mb); "
                        + "the file may be made to exhaust memory");
                }
            }
        } catch (ZipException e) {
            // not a valid archive: the parser says so in its own words
        } catch (IOException e) {
            throw DocumentRuntime.explain(e);
        }
    }

    /** Parts parsers hold whole: shared strings, styles, the workbook (in any folder, any case). */
    static boolean guarded(String entry) {
        String n = entry.toLowerCase(Locale.ROOT);
        String base = n.substring(n.lastIndexOf('/') + 1);
        return base.startsWith("sharedstrings") || base.startsWith("styles") || base.startsWith("workbook");
    }

    /** Bytes in the stream, counting at most {@code limit} + 1. */
    static long count(InputStream in, long limit) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long n = 0;
        int r;
        while (n <= limit && (r = in.read(buf)) > 0) {
            n += r;
        }
        return n;
    }

    /** A stream that fails once more than {@code limit} bytes have been read from it. */
    public static InputStream limited(InputStream in, long limit, String what) {
        return new FilterInputStream(in) {
            private long read;

            private void add(long n) throws IOException {
                read += n;
                if (read > limit) {
                    throw new IOException(what + " expands to more than " + DocumentLimits.mb(limit)
                        + ", more than Kilnmark reads (kilnmark.documents.max-part-mb)");
                }
            }

            @Override
            public int read() throws IOException {
                int b = super.read();
                if (b >= 0) {
                    add(1);
                }
                return b;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int n = super.read(b, off, len);
                if (n > 0) {
                    add(n);
                }
                return n;
            }

            @Override
            public long skip(long n) throws IOException {
                long s = super.skip(n);
                add(s);
                return s;
            }
        };
    }
}

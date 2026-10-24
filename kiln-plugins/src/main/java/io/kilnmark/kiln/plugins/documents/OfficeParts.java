package io.kilnmark.kiln.plugins.documents;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

/**
 * Checks a ZIP-based document before any parser opens it. Parsers keep some parts of an Office
 * file whole in memory (shared strings, styles, the workbook, relationships, ...) and size them by
 * what the archive declares, which a hostile file can understate. So every part of an Office Open
 * XML file (an archive with {@code [Content_Types].xml}, whatever its file name) is expanded here
 * and its real size counted, stopping at the limit; only the parts parsers stream (worksheets,
 * slides, a Word document's body) and media are left out. The archive is read with Commons
 * Compress, the parser POI itself uses, and anything it cannot read is refused rather than let
 * through. Every archive, Office or not, is refused above {@value #MAX_ENTRIES} entries.
 */
public final class OfficeParts {

    /** The most entries in an archive; real documents have tens to hundreds. */
    static final int MAX_ENTRIES = 10_000;

    private static final Set<String> OOXML = Set.of("docx", "docm", "dotx", "xlsx", "xlsm", "xltx", "pptx", "pptm",
        "potx");

    /** Parts the parsers read as a stream, never whole: their size is not limited here. */
    private static final Pattern STREAMED = Pattern.compile(
        "xl/worksheets/[^/]+\\.xml|ppt/slides/[^/]+\\.xml|word/document\\d*\\.xml|.*/media/.*|.*/embeddings/.*");

    private OfficeParts() {
    }

    /** Whether a file with this name is Office Open XML. */
    public static boolean ooxml(String name) {
        return OOXML.contains(DocumentTypes.extension(name));
    }

    /** Whether a file starts like a ZIP archive. */
    public static boolean zip(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] b = in.readNBytes(4);
            return b.length == 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * @throws DocumentException when the archive cannot be read safely, holds too many entries, or
     *                           is Office Open XML with a part (other than those parsers stream) that
     *                           expands past {@code limit} bytes
     */
    public static void check(Path file, long limit) throws DocumentException {
        try (ZipFile zip = ZipFile.builder().setPath(file).get()) {
            List<ZipArchiveEntry> entries = new ArrayList<>();
            boolean office = false;
            for (Enumeration<ZipArchiveEntry> e = zip.getEntries(); e.hasMoreElements(); ) {
                ZipArchiveEntry entry = e.nextElement();
                if (entries.size() >= MAX_ENTRIES) {
                    throw new DocumentException("it holds more than " + String.format("%,d", MAX_ENTRIES)
                        + " parts, more than Kilnmark reads; the file may be made to exhaust memory");
                }
                entries.add(entry);
                office |= entry.getName().equals("[Content_Types].xml");
            }
            if (!office) {
                return;
            }
            for (ZipArchiveEntry entry : entries) {
                if (entry.isDirectory() || STREAMED.matcher(entry.getName().toLowerCase(Locale.ROOT)).matches()) {
                    continue;
                }
                long real;
                try (InputStream in = zip.getInputStream(entry)) {
                    real = count(in, limit);
                }
                if (real > limit) {
                    throw new DocumentException("its part " + Names.clean(entry.getName(), "?") + " expands to more "
                        + "than " + DocumentLimits.mb(limit) + ", more than Kilnmark reads "
                        + "(kilnmark.documents.max-part-mb); the file may be made to exhaust memory");
                }
            }
        } catch (DocumentException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // what this reader cannot read safely, no parser may try
            throw new DocumentException("it is not an archive Kilnmark can read safely (" + DocumentRuntime.rootMessage(e)
                + "); it may be damaged", e);
        }
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

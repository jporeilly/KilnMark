package io.kilnmark.kiln.plugins.documents;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.poi.util.XMLHelper;

/**
 * Checks any ZIP archive before a parser opens it. Parsers keep many parts of an Office file whole
 * in memory (shared strings, styles, comments, relationships, ...) and size them by what the
 * archive declares, which a hostile file can understate; and they find parts by relationships and
 * content types, not by names. So:
 *
 * <ul>
 *   <li>whether a file is an archive is decided by opening it with Commons Compress, the reader POI
 *       uses, which finds the central directory from the end (so a preamble before the archive does
 *       not hide it), never by its name, first bytes or declared type;</li>
 *   <li>every entry is expanded and its real size counted against the limit, except entries whose
 *       content type in {@code [Content_Types].xml} is one parsers stream: worksheets, slides, a
 *       Word document's main part, and images;</li>
 *   <li>when the content types cannot be read, nothing is exempt;</li>
 *   <li>an archive of more than {@value #MAX_ENTRIES} entries is refused.</li>
 * </ul>
 *
 * Formats that are always archives (Office Open XML, OpenDocument, EPUB) must open and pass, or
 * they are not read.
 */
public final class OfficeParts {

    /** The most entries in an archive; real documents have tens to hundreds. */
    static final int MAX_ENTRIES = 10_000;

    /** The most {@code [Content_Types].xml} may hold. */
    static final int MAX_TYPES_BYTES = 1 << 20;

    private static final Set<String> OOXML = Set.of("docx", "docm", "dotx", "xlsx", "xlsm", "xltx", "pptx", "pptm",
        "potx");

    /** Content types of parts parsers read as a stream, never whole: their size is not limited here. */
    private static final Set<String> STREAMED = Set.of(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml",
        "application/vnd.openxmlformats-officedocument.presentationml.slide+xml",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.template.main+xml",
        "application/vnd.ms-word.document.macroenabled.main+xml",
        "application/vnd.ms-word.template.macroenabledtemplate.main+xml");

    private OfficeParts() {
    }

    /** Whether a file with this name is Office Open XML. */
    public static boolean ooxml(String name) {
        return OOXML.contains(DocumentTypes.extension(name));
    }

    /** Whether a media type is a format that is always a ZIP archive. */
    public static boolean archiveType(String mediaType) {
        if (mediaType == null) {
            return false;
        }
        String t = mediaType.toLowerCase(Locale.ROOT);
        return t.contains("openxmlformats") || t.contains("macroenabled") || t.contains("opendocument")
            || t.contains("epub") || t.endsWith("/zip");
    }

    /**
     * Checks a file that may be an archive.
     *
     * @param mustBeArchive true for formats that are always archives: one that does not open is
     *                      refused rather than handed to a parser that might read it another way
     * @throws DocumentException when the archive holds too many entries or a part that expands past
     *                           {@code limit} bytes, or must be an archive and cannot be read as one
     */
    public static void check(Path file, long limit, boolean mustBeArchive) throws DocumentException {
        ZipFile zip;
        try {
            zip = ZipFile.builder().setPath(file).get();
        } catch (IOException | RuntimeException e) {
            if (mustBeArchive) {
                throw new DocumentException("it is not a valid file of the kind its name says; it may be damaged "
                    + "or named wrongly (" + DocumentRuntime.rootMessage(e) + ")", e);
            }
            return; // not an archive: nothing here for a ZIP reader to find
        }
        try (zip) {
            List<ZipArchiveEntry> entries = new ArrayList<>();
            ZipArchiveEntry types = null;
            for (Enumeration<ZipArchiveEntry> e = zip.getEntries(); e.hasMoreElements(); ) {
                ZipArchiveEntry entry = e.nextElement();
                if (entries.size() >= MAX_ENTRIES) {
                    throw new DocumentException("it holds more than " + String.format("%,d", MAX_ENTRIES)
                        + " parts, more than Kilnmark reads; the file may be made to exhaust memory");
                }
                entries.add(entry);
                if (entry.getName().equalsIgnoreCase("[Content_Types].xml")) {
                    types = types == null ? entry : types;
                }
            }
            ContentTypes ct = types == null ? null : contentTypes(zip, types);
            for (ZipArchiveEntry entry : entries) {
                if (entry.isDirectory() || (ct != null && streamed(ct.of(entry.getName())))) {
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
            throw new DocumentException("it is not an archive Kilnmark can read safely ("
                + DocumentRuntime.rootMessage(e) + "); it may be damaged", e);
        }
    }

    private static boolean streamed(String contentType) {
        if (contentType == null) {
            return false;
        }
        String t = contentType.toLowerCase(Locale.ROOT).strip();
        return STREAMED.contains(t) || t.startsWith("image/");
    }

    /** Content types by part name and by extension, from {@code [Content_Types].xml}. */
    record ContentTypes(Map<String, String> overrides, Map<String, String> defaults) {
        /** The content type of an entry (OPC part names are case-insensitive), or null. */
        String of(String entry) {
            String name = "/" + entry.toLowerCase(Locale.ROOT);
            String o = overrides.get(name);
            if (o != null) {
                return o;
            }
            int dot = name.lastIndexOf('.');
            int slash = name.lastIndexOf('/');
            return dot > slash ? defaults.get(name.substring(dot + 1)) : null;
        }
    }

    /** The content types, or null when they cannot be read (then nothing is exempt). */
    static ContentTypes contentTypes(ZipFile zip, ZipArchiveEntry entry) {
        Map<String, String> overrides = new HashMap<>();
        Map<String, String> defaults = new HashMap<>();
        XMLInputFactory xml = XMLHelper.newXMLInputFactory();
        try (InputStream in = limited(zip.getInputStream(entry), MAX_TYPES_BYTES, "[Content_Types].xml")) {
            XMLStreamReader x = xml.createXMLStreamReader(in);
            try {
                while (x.hasNext()) {
                    if (x.next() != XMLStreamConstants.START_ELEMENT) {
                        continue;
                    }
                    String type = x.getAttributeValue(null, "ContentType");
                    if (type == null) {
                        continue;
                    }
                    if (x.getLocalName().equals("Override") && x.getAttributeValue(null, "PartName") != null) {
                        overrides.put(x.getAttributeValue(null, "PartName").toLowerCase(Locale.ROOT), type);
                    } else if (x.getLocalName().equals("Default") && x.getAttributeValue(null, "Extension") != null) {
                        defaults.put(x.getAttributeValue(null, "Extension").toLowerCase(Locale.ROOT), type);
                    }
                }
            } finally {
                x.close();
            }
            return new ContentTypes(overrides, defaults);
        } catch (IOException | XMLStreamException | RuntimeException e) {
            return null;
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

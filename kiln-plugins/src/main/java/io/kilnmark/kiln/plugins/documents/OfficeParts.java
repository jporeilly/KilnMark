package io.kilnmark.kiln.plugins.documents;

import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 *   <li>an archive is refused when two entries share a name (ignoring case), when an entry's local
 *       header does not match the central directory, when entries overlap, or when it has more
 *       than {@value #MAX_ENTRIES} entries;</li>
 *   <li>every entry is expanded and its real size counted against the limit. In Office Open XML
 *       only, an entry is exempt when parsers stream it, which needs both its declared content type
 *       and every relationship to it to agree: a worksheet or slide that the main part's
 *       relationships name as one, the main part of a Word document named by the package's
 *       relationships, or an image named only as an image;</li>
 *   <li>when the content types or relationships cannot be read exactly, nothing is exempt.</li>
 * </ul>
 *
 * Formats that are always archives (Office Open XML, OpenDocument, EPUB) must open and pass, or
 * they are not read; OpenDocument and EPUB have every entry counted.
 */
public final class OfficeParts {

    /** The most entries in an archive; real documents have tens to hundreds. */
    static final int MAX_ENTRIES = 10_000;

    /** The most {@code [Content_Types].xml} or one relationships part may hold. */
    static final int MAX_TYPES_BYTES = 1 << 20;

    /** The most all relationships parts together may hold before nothing is exempt. */
    static final long MAX_RELS_BYTES = 16L << 20;

    private static final Set<String> OOXML = Set.of("docx", "docm", "dotx", "xlsx", "xlsm", "xltx", "pptx", "pptm",
        "potx");

    private static final String WORKSHEET = "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml";
    private static final String SLIDE = "application/vnd.openxmlformats-officedocument.presentationml.slide+xml";
    private static final Set<String> WORD_MAIN = Set.of(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.template.main+xml",
        "application/vnd.ms-word.document.macroenabled.main+xml",
        "application/vnd.ms-word.template.macroenabledtemplate.main+xml");

    private static final List<String> REL_BASES = List.of(
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/",
        "http://purl.oclc.org/ooxml/officeDocument/relationships/");

    private static final Pattern RELS = Pattern.compile("(?:(.*)/)?_rels/([^/]*)\\.rels");

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
        return officeType(t) || t.contains("opendocument") || t.contains("epub") || t.endsWith("/zip");
    }

    /** Whether a media type is Office Open XML, read by the parsers that stream some parts. */
    public static boolean officeType(String mediaType) {
        if (mediaType == null) {
            return false;
        }
        String t = mediaType.toLowerCase(Locale.ROOT);
        return t.contains("openxmlformats") || t.contains("macroenabled");
    }

    /** {@link #check(Path, long, boolean, boolean)} for a file of this media type. */
    public static void check(Path file, long limit, String mediaType) throws DocumentException {
        check(file, limit, archiveType(mediaType), officeType(mediaType));
    }

    /**
     * Checks a file that may be an archive.
     *
     * @param mustBeArchive true for formats that are always archives: one that does not open is
     *                      refused rather than handed to a parser that might read it another way
     * @param office        true when an Office Open XML parser will read it: only then may parts
     *                      parsers stream go uncounted
     * @throws DocumentException when the archive is malformed in a way readers could disagree on,
     *                           holds too many entries or a part that expands past {@code limit}
     *                           bytes, or must be an archive and cannot be read as one
     */
    public static void check(Path file, long limit, boolean mustBeArchive, boolean office) throws DocumentException {
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
        try (zip; FileChannel raw = FileChannel.open(file, StandardOpenOption.READ)) {
            List<ZipArchiveEntry> entries = new ArrayList<>();
            Set<String> names = new HashSet<>();
            for (Enumeration<ZipArchiveEntry> e = zip.getEntries(); e.hasMoreElements(); ) {
                ZipArchiveEntry entry = e.nextElement();
                if (entries.size() >= MAX_ENTRIES) {
                    throw new DocumentException("it holds more than " + String.format("%,d", MAX_ENTRIES)
                        + " parts, more than Kilnmark reads; the file may be made to exhaust memory");
                }
                if (!names.add(key(entry.getName()))) {
                    throw malformed("it holds two parts named " + Names.clean(entry.getName(), "?"));
                }
                entries.add(entry);
            }
            layout(raw, entries);
            Set<String> exempt = office ? exempt(zip, entries) : Set.of();
            for (ZipArchiveEntry entry : entries) {
                if (exempt.contains(key(entry.getName()))) {
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

    private static DocumentException malformed(String what) {
        return new DocumentException(what + ", which readers may take differently; Kilnmark does not read it");
    }

    /** An entry's name as Office parts are compared: case does not count. */
    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * Each entry's local header must be where the central directory says, carry the same name, and
     * end before the next entry starts: otherwise readers that walk the headers, or entries that
     * share data, could see something the count did not.
     */
    private static void layout(FileChannel raw, List<ZipArchiveEntry> entries) throws IOException, DocumentException {
        List<ZipArchiveEntry> byOffset = new ArrayList<>(entries);
        byOffset.sort(Comparator.comparingLong(ZipArchiveEntry::getLocalHeaderOffset));
        long end = -1;
        String previous = null;
        for (ZipArchiveEntry entry : byOffset) {
            long at = entry.getLocalHeaderOffset();
            if (at < end) {
                throw malformed("its parts " + Names.clean(previous, "?") + " and "
                    + Names.clean(entry.getName(), "?") + " overlap");
            }
            ByteBuffer head = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
            read(raw, head, at);
            byte[] name = entry.getRawName();
            int nameLength = Short.toUnsignedInt(head.getShort(26));
            int extraLength = Short.toUnsignedInt(head.getShort(28));
            ByteBuffer local = ByteBuffer.allocate(nameLength);
            if (head.getInt(0) == 0x04034b50 && nameLength == name.length) {
                read(raw, local, at + 30);
            }
            if (head.getInt(0) != 0x04034b50 || !Arrays.equals(local.array(), name)) {
                throw malformed("its part " + Names.clean(entry.getName(), "?") + " is named differently in its "
                    + "own header");
            }
            end = at + 30 + nameLength + extraLength + entry.getCompressedSize();
            previous = entry.getName();
        }
    }

    private static void read(FileChannel raw, ByteBuffer into, long at) throws IOException {
        while (into.hasRemaining()) {
            if (raw.read(into, at + into.position()) < 0) {
                throw new EOFException("an entry's header is past the end of the file");
            }
        }
    }

    /**
     * The entries (by {@link #key}) parsers stream, so they need not be counted: those whose
     * declared content type and every relationship to them agree. Empty when the content types or
     * relationships cannot be read exactly.
     */
    static Set<String> exempt(ZipFile zip, List<ZipArchiveEntry> entries) {
        ZipArchiveEntry typesEntry = null;
        List<ZipArchiveEntry> relsEntries = new ArrayList<>();
        for (ZipArchiveEntry e : entries) {
            String name = e.getName();
            if (name.startsWith("/") || name.contains("%") || name.contains("\\")) {
                return Set.of(); // names readers may decode differently
            }
            if (name.equalsIgnoreCase("[Content_Types].xml")) {
                typesEntry = e;
            } else if (RELS.matcher(key(name)).matches()) {
                relsEntries.add(e);
            }
        }
        if (typesEntry == null) {
            return Set.of();
        }
        ContentTypes types = contentTypes(zip, typesEntry);
        if (types == null) {
            return Set.of();
        }
        // every relationship, by its target: source part ("" for the package) and type
        Map<String, List<String[]>> rels = new HashMap<>();
        long budget = MAX_RELS_BYTES;
        for (ZipArchiveEntry e : relsEntries) {
            Matcher m = RELS.matcher(key(e.getName()));
            m.matches();
            String source = m.group(2).isEmpty() ? "" : (m.group(1) == null ? "" : m.group(1) + "/") + m.group(2);
            if (m.group(2).isEmpty() && m.group(1) != null) {
                return Set.of(); // relationships of a folder: nothing POI reads, nothing to trust
            }
            long used = relationships(zip, e, source, Math.min(budget, MAX_TYPES_BYTES), rels);
            if (used < 0) {
                return Set.of();
            }
            budget -= used;
        }
        Set<String> main = new HashSet<>();
        rels.forEach((target, from) -> {
            for (String[] r : from) {
                if (r[0].isEmpty() && r[1].equals("officeDocument")) {
                    main.add(target);
                }
            }
        });
        Set<String> exempt = new HashSet<>();
        for (ZipArchiveEntry e : entries) {
            String name = key(e.getName());
            if (name.equals("[content_types].xml") || RELS.matcher(name).matches()) {
                continue;
            }
            String type = types.of(name);
            List<String[]> to = rels.get(name);
            if (type == null || to == null || to.isEmpty()) {
                continue;
            }
            type = type.toLowerCase(Locale.ROOT).strip();
            boolean ok = true;
            for (String[] r : to) {
                ok &= switch (r[1]) {
                    case "worksheet" -> type.equals(WORKSHEET) && main.contains(r[0]);
                    case "slide" -> type.equals(SLIDE) && main.contains(r[0]);
                    case "officeDocument" -> WORD_MAIN.contains(type) && r[0].isEmpty();
                    case "image" -> type.startsWith("image/");
                    default -> false;
                };
            }
            if (ok) {
                exempt.add(name);
            }
        }
        return exempt;
    }

    /**
     * Adds a relationships part's internal relationships to {@code rels}, by resolved target.
     *
     * @return the bytes read, or -1 when it cannot be read exactly
     */
    private static long relationships(ZipFile zip, ZipArchiveEntry entry, String source, long limit,
                                      Map<String, List<String[]>> rels) {
        XMLInputFactory xml = XMLHelper.newXMLInputFactory();
        long[] read = new long[1];
        try (InputStream in = counted(limited(zip.getInputStream(entry), limit, entry.getName()), read)) {
            XMLStreamReader x = xml.createXMLStreamReader(in);
            try {
                while (x.hasNext()) {
                    if (x.next() != XMLStreamConstants.START_ELEMENT || !x.getLocalName().equals("Relationship")) {
                        continue;
                    }
                    if ("External".equalsIgnoreCase(x.getAttributeValue(null, "TargetMode"))) {
                        continue;
                    }
                    String target = resolve(source, x.getAttributeValue(null, "Target"));
                    String type = x.getAttributeValue(null, "Type");
                    if (target == null || type == null) {
                        return -1;
                    }
                    String kind = "other";
                    for (String base : REL_BASES) {
                        if (type.startsWith(base)) {
                            kind = type.substring(base.length());
                        }
                    }
                    rels.computeIfAbsent(target, k -> new ArrayList<>()).add(new String[] {source, kind});
                }
            } finally {
                x.close();
            }
            return read[0];
        } catch (IOException | XMLStreamException | RuntimeException e) {
            return -1;
        }
    }

    /** A relationship's target as an entry {@link #key}, or null when it is not one plainly. */
    static String resolve(String source, String target) {
        if (target == null || target.isEmpty() || target.contains("#") || target.contains("?")
            || target.contains("\\")) {
            return null;
        }
        String decoded;
        try {
            decoded = URLDecoder.decode(target.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String path = decoded.startsWith("/") ? decoded.substring(1)
            : source.substring(0, source.lastIndexOf('/') + 1) + decoded;
        List<String> parts = new ArrayList<>();
        for (String s : path.split("/", -1)) {
            if (s.isEmpty()) {
                return null;
            } else if (s.equals("..")) {
                if (parts.isEmpty()) {
                    return null;
                }
                parts.remove(parts.size() - 1);
            } else if (!s.equals(".")) {
                parts.add(s);
            }
        }
        return parts.isEmpty() ? null : key(String.join("/", parts));
    }

    /** Content types by part name and by extension, from {@code [Content_Types].xml}. */
    record ContentTypes(Map<String, String> overrides, Map<String, String> defaults) {
        /** The content type of an entry {@link #key}, or null. */
        String of(String key) {
            String o = overrides.get(key);
            if (o != null) {
                return o;
            }
            int dot = key.lastIndexOf('.');
            int slash = key.lastIndexOf('/');
            return dot > slash ? defaults.get(key.substring(dot + 1)) : null;
        }
    }

    /**
     * The content types, or null when they cannot be read exactly (then nothing is exempt): also
     * when a part or extension is named twice, or a part name is encoded.
     */
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
                    String part = x.getAttributeValue(null, "PartName");
                    String ext = x.getAttributeValue(null, "Extension");
                    if (x.getLocalName().equals("Override")) {
                        if (type == null || part == null || !part.startsWith("/") || part.contains("%")
                            || overrides.put(key(part.substring(1)), type) != null) {
                            return null;
                        }
                    } else if (x.getLocalName().equals("Default")) {
                        if (type == null || ext == null || defaults.put(key(ext), type) != null) {
                            return null;
                        }
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

    private static InputStream counted(InputStream in, long[] read) {
        return new FilterInputStream(in) {
            @Override
            public int read() throws IOException {
                int b = super.read();
                if (b >= 0) {
                    read[0]++;
                }
                return b;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int n = super.read(b, off, len);
                if (n > 0) {
                    read[0] += n;
                }
                return n;
            }
        };
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

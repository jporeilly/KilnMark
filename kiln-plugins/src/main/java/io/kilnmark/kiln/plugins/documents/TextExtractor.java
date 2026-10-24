package io.kilnmark.kiln.plugins.documents;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.io.input.CloseShieldInputStream;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Message;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.Office;
import org.apache.tika.metadata.PagedText;
import org.apache.tika.metadata.Property;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MediaTypeRegistry;
import org.apache.tika.mime.MimeTypes;
import org.apache.tika.parser.CompositeParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.epub.EpubParser;
import org.apache.tika.parser.html.JSoupParser;
import org.apache.tika.parser.mail.RFC822Parser;
import org.apache.tika.parser.microsoft.OfficeParser;
import org.apache.tika.parser.microsoft.OfficeParserConfig;
import org.apache.tika.parser.microsoft.ooxml.OOXMLParser;
import org.apache.tika.parser.microsoft.rtf.RTFParser;
import org.apache.tika.parser.odf.OpenDocumentParser;
import org.apache.tika.parser.pdf.PDFParser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.parser.xml.XMLParser;
import org.apache.tika.sax.EmbeddedContentHandler;
import org.xml.sax.SAXException;

/**
 * Reads a document's text with Apache Tika, using only the parsers for the formats Kilnmark
 * reads (no automatic loading of every parser on the class path), set up for untrusted files:
 *
 * <ul>
 *   <li>no OCR, no images, no scripts, macros or PDF actions; nothing is fetched from a network,
 *       and XML is parsed by Tika's and POI's hardened readers (no external entities);</li>
 *   <li>text stops at the character budget, shared by a document and everything inside it;</li>
 *   <li>documents inside documents are read to {@link DocumentLimits#maxDepth()} levels and at most
 *       {@link DocumentLimits#maxEmbedded()} of them; a mail message's attachments become parts of
 *       their own, so each can be an asset;</li>
 *   <li>the read stops when {@code cancelled} turns true (its time is up).</li>
 * </ul>
 *
 * Call it through {@link DocumentRuntime#run}, which adds the time limit.
 */
public final class TextExtractor {

    private static final MimeTypes MIME = MimeTypes.getDefaultMimeTypes();
    private static final MediaTypeRegistry REGISTRY = MIME.getMediaTypeRegistry();

    private TextExtractor() {
    }

    /**
     * Reads the document in {@code file}.
     *
     * @param name the document's own file name, which decides its format
     * @param size its size in bytes, or -1
     */
    public static Extraction extract(Path file, String name, long size, DocumentLimits limits, AtomicBoolean cancelled)
        throws DocumentException {
        String type = DocumentTypes.mediaType(name);
        if (type == null) {
            throw new DocumentException("Kilnmark does not read the text of this kind of file");
        }
        TextCollector.Budget budget = new TextCollector.Budget(limits.maxCharacters());
        TextCollector main = new TextCollector(budget, cancelled);
        Parser parser = parser();
        Embedded embedded = new Embedded(parser, limits, budget, cancelled, main, DocumentTypes.mail(type));
        ParseContext context = context(parser, embedded);
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, name);
        metadata.set(Metadata.CONTENT_TYPE, type);
        try (TikaInputStream in = TikaInputStream.get(file)) {
            parser.parse(in, main, metadata, context);
        } catch (IOException | SAXException | TikaException | RuntimeException e) {
            if (cancelled.get()) {
                throw new DocumentException("the read was stopped", e);
            }
            if (!main.truncated() && !(e instanceof TextCollector.LimitReached)) {
                throw DocumentRuntime.explain(e);
            }
            // the character limit: keep what was read
        }
        if (cancelled.get()) {
            throw new DocumentException("the read was stopped");
        }
        List<String> warnings = warnings(metadata);
        for (String w : warnings) {
            if (w.toLowerCase(java.util.Locale.ROOT).contains("zip bomb")) {
                throw DocumentRuntime.explain(new IOException(w)); // Tika read around the refused part
            }
        }
        Extraction.Part document = part(name, type, size, main, metadata, DocumentTypes.mail(type), null);
        List<String> notes = new ArrayList<>(embedded.notes());
        if (!warnings.isEmpty()) {
            notes.add("Parts of it could not be read (" + DocumentRuntime.rootMessage(new IOException(
                warnings.get(0).lines().findFirst().orElse(""))) + ")");
        }
        if (main.truncated() || budget.left() <= 0) {
            notes.add(0, "Only the first " + String.format("%,d", limits.maxCharacters()) + " characters of text "
                + "were read: the limit for one document");
        }
        return new Extraction(document, embedded.attachments, notes);
    }

    /** Problems Tika's parsers met and read around (one part of a file that would not parse). */
    private static List<String> warnings(Metadata md) {
        List<String> out = new ArrayList<>();
        for (String n : md.names()) {
            if (n.startsWith("X-TIKA:EXCEPTION:") && !n.endsWith("write_limit_reached")) {
                for (String v : md.getValues(n)) {
                    if (v != null && !v.isBlank()) {
                        out.add(v);
                    }
                }
            }
        }
        return out;
    }

    /** The parsers for the formats Kilnmark reads, and nothing else. */
    static Parser parser() {
        DocumentRuntime.limits(); // its class sets the zip-bomb check, after Tika's OOXML parser turns it off
        return new CompositeParser(REGISTRY, List.of(new PDFParser(), new OfficeParser(), new OOXMLParser(),
            new RTFParser(), new JSoupParser(Encodings.detector()), new RFC822Parser(), new OpenDocumentParser(),
            new EpubParser(), new XMLParser(), new PlainTextParser()));
    }

    private static ParseContext context(Parser parser, EmbeddedDocumentExtractor embedded) {
        ParseContext c = new ParseContext();
        c.set(Parser.class, parser);
        c.set(EmbeddedDocumentExtractor.class, embedded);
        c.set(org.apache.tika.detect.Detector.class, MIME);
        c.set(org.apache.tika.detect.EncodingDetector.class, Encodings.detector());

        PDFParserConfig pdf = new PDFParserConfig();
        pdf.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
        pdf.setExtractInlineImages(false);
        pdf.setExtractActions(false);
        pdf.setExtractBookmarksText(false);
        pdf.setExtractAnnotationText(true);
        pdf.setExtractAcroFormContent(true);
        pdf.setExtractFontNames(false);
        pdf.setCatchIntermediateIOExceptions(true);
        pdf.setMaxMainMemoryBytes(64L << 20); // beyond this PDFBox works in a temporary file
        c.set(PDFParserConfig.class, pdf);

        OfficeParserConfig office = new OfficeParserConfig();
        office.setUseSAXDocxExtractor(true); // streaming: memory stays small for large files
        office.setUseSAXPptxExtractor(true);
        office.setExtractMacros(false);
        office.setIncludeDeletedContent(false);
        office.setIncludeMoveFromContent(false);
        office.setIncludeHeadersAndFooters(true);
        c.set(OfficeParserConfig.class, office);
        return c;
    }

    /** Whether one of the parsers reads this media type (or a type it is a kind of). */
    static boolean supported(Parser parser, MediaType type) {
        Map<MediaType, Parser> parsers = ((CompositeParser) parser).getParsers();
        for (MediaType t = type == null ? null : type.getBaseType(); t != null; t = REGISTRY.getSupertype(t)) {
            if (parsers.containsKey(t)) {
                return true;
            }
        }
        return false;
    }

    private static Extraction.Part part(String name, String type, long size, TextCollector c, Metadata md,
                                        boolean mail, String problem) {
        String text = c.text();
        List<Integer> starts = c.pageStarts();
        List<String> labels = c.pageLabels();
        List<Extraction.Page> pages = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            pages.add(new Extraction.Page(i + 1, starts.get(i), labels.get(i)));
        }
        Map<String, String> p = properties(md, mail);
        if (!p.containsKey(Extraction.PAGES) && !pages.isEmpty()) {
            p.put(Extraction.PAGES, Integer.toString(pages.size()));
        }
        String contentType = type;
        String detected = md.get(Metadata.CONTENT_TYPE);
        if (detected != null && MediaType.parse(detected) != null) {
            contentType = MediaType.parse(detected).getBaseType().toString();
        }
        return new Extraction.Part(name, contentType, size, text, pages, p, c.truncated(), problem);
    }

    /** The properties people look for, from Tika's metadata. */
    static Map<String, String> properties(Metadata md, boolean mail) {
        Map<String, String> p = new LinkedHashMap<>();
        put(p, Extraction.TITLE, md.get(TikaCoreProperties.TITLE));
        put(p, Extraction.AUTHOR, joined(md.getValues(TikaCoreProperties.CREATOR), "; "));
        put(p, Extraction.CREATED, date(md, TikaCoreProperties.CREATED));
        put(p, Extraction.MODIFIED, date(md, TikaCoreProperties.MODIFIED));
        for (Property count : List.of(PagedText.N_PAGES, Office.PAGE_COUNT, Office.SLIDE_COUNT)) {
            String v = md.get(count);
            if (v != null && !p.containsKey(Extraction.PAGES)) {
                try {
                    if (Integer.parseInt(v.strip()) > 0) {
                        p.put(Extraction.PAGES, v.strip());
                    }
                } catch (NumberFormatException e) {
                    // not a count
                }
            }
        }
        if (mail) {
            String from = md.get(Message.MESSAGE_FROM);
            if (from == null) {
                String n = md.get(Message.MESSAGE_FROM_NAME);
                String e = md.get(Message.MESSAGE_FROM_EMAIL);
                from = n != null && e != null && !n.equals(e) ? n + " <" + e + ">" : n != null ? n : e;
            }
            put(p, Extraction.FROM, from != null ? from : p.get(Extraction.AUTHOR));
            String to = joined(md.getValues(Message.MESSAGE_TO), ", ");
            put(p, Extraction.TO, to != null ? to : joined(md.getValues(Message.MESSAGE_TO_DISPLAY_NAME), ", "));
            String cc = joined(md.getValues(Message.MESSAGE_CC), ", ");
            put(p, Extraction.CC, cc != null ? cc : joined(md.getValues(Message.MESSAGE_CC_DISPLAY_NAME), ", "));
            String subject = md.get(TikaCoreProperties.SUBJECT);
            put(p, Extraction.SUBJECT, subject != null ? subject : p.get(Extraction.TITLE));
            put(p, Extraction.DATE, p.get(Extraction.CREATED));
        }
        return p;
    }

    private static void put(Map<String, String> p, String key, String value) {
        if (value != null && !value.isBlank()) {
            String v = value.replaceAll("[\\p{Cntrl}&&[^\\t]]", " ").strip();
            p.put(key, v.length() > 1000 ? v.substring(0, 1000) : v);
        }
    }

    private static String joined(String[] values, String separator) {
        if (values == null || values.length == 0) {
            return null;
        }
        List<String> kept = new ArrayList<>();
        for (String v : values) {
            if (v != null && !v.isBlank() && !kept.contains(v.strip())) {
                kept.add(v.strip());
            }
        }
        return kept.isEmpty() ? null : String.join(separator, kept);
    }

    private static String date(Metadata md, Property property) {
        Date d = md.getDate(property);
        return d == null ? null : DateTimeFormatter.ISO_INSTANT.format(d.toInstant());
    }

    /** Counts the bytes read through it. */
    private static final class Counting extends FilterInputStream {
        long count;

        Counting(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                count += n;
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long s = super.skip(n);
            count += s;
            return s;
        }

        @Override
        public boolean markSupported() {
            return false;
        }
    }

    /**
     * Reads documents inside a document: attachments, embedded files, the parts of a mail message.
     * Their text joins the text being collected, except a mail message's own attachments, which are
     * collected as parts of their own.
     */
    private static final class Embedded implements EmbeddedDocumentExtractor {

        private final Parser parser;
        private final DocumentLimits limits;
        private final TextCollector.Budget budget;
        private final AtomicBoolean cancelled;
        private final boolean mail;
        private final Deque<TextCollector> collectors = new ArrayDeque<>();
        final List<Extraction.Part> attachments = new ArrayList<>();
        private int depth;
        private int count;
        private int tooDeep;
        private int tooMany;
        private int failed;

        Embedded(Parser parser, DocumentLimits limits, TextCollector.Budget budget, AtomicBoolean cancelled,
                 TextCollector main, boolean mail) {
            this.parser = parser;
            this.limits = limits;
            this.budget = budget;
            this.cancelled = cancelled;
            this.mail = mail;
            collectors.push(main);
        }

        List<String> notes() {
            List<String> notes = new ArrayList<>();
            if (tooDeep > 0) {
                notes.add(tooDeep + (tooDeep == 1 ? " file" : " files") + " nested more than " + limits.maxDepth()
                    + " levels deep inside it " + (tooDeep == 1 ? "was" : "were") + " not read");
            }
            if (tooMany > 0) {
                notes.add("Only the first " + limits.maxEmbedded() + " files inside it were read; " + tooMany
                    + " more " + (tooMany == 1 ? "was" : "were") + " not");
            }
            if (failed > 0) {
                notes.add(failed + (failed == 1 ? " file" : " files") + " inside it could not be read");
            }
            return notes;
        }

        @Override
        public boolean shouldParseEmbedded(Metadata metadata) {
            return true;
        }

        @Override
        public void parseEmbedded(InputStream stream, org.xml.sax.ContentHandler handler, Metadata metadata,
                                  boolean outputHtml) throws SAXException, IOException {
            if (cancelled.get()) {
                throw new TextCollector.Cancelled();
            }
            boolean attachment = mail && depth == 0 && isAttachment(metadata);
            if (depth >= limits.maxDepth()) {
                tooDeep++;
                return;
            }
            if (count >= limits.maxEmbedded()) {
                tooMany++;
                return;
            }
            count++;
            String name = metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY);
            if (name == null || name.isBlank()) {
                name = attachment ? "attachment-" + (attachments.size() + 1) : null;
            } else {
                name = name.replace('\\', '/');
                name = name.substring(name.lastIndexOf('/') + 1).strip();
            }
            Counting counted = new Counting(CloseShieldInputStream.wrap(stream));
            try (TikaInputStream in = TikaInputStream.get(counted)) {
                MediaType type = type(in, metadata, name);
                boolean readable = type != null && supported(parser, type);
                if (readable) {
                    metadata.set(Metadata.CONTENT_TYPE, type.toString());
                }
                if (attachment) {
                    attachment(in, counted, metadata, name, type, readable);
                } else if (readable) {
                    inline(in, metadata);
                }
            }
        }

        private static boolean isAttachment(Metadata metadata) {
            // Tika marks a message's body parts as attachments too; real ones have a file name or
            // say so in Content-Disposition
            if ("INLINE".equalsIgnoreCase(metadata.get(TikaCoreProperties.EMBEDDED_RESOURCE_TYPE))) {
                return false;
            }
            String disposition = metadata.get("Content-Disposition");
            return metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY) != null
                || (disposition != null && disposition.strip().toLowerCase(java.util.Locale.ROOT).startsWith("attachment"));
        }

        private MediaType type(TikaInputStream in, Metadata metadata, String name) throws IOException {
            if (name != null && DocumentTypes.mediaType(name) != null) {
                return MediaType.parse(DocumentTypes.mediaType(name));
            }
            String declared = metadata.get(Metadata.CONTENT_TYPE);
            MediaType t = declared == null ? null : MediaType.parse(declared);
            if (t != null && supported(parser, t)) {
                return t;
            }
            return MIME.detect(in, metadata);
        }

        /** Text from inside the document joins the text being collected. */
        private void inline(InputStream in, Metadata metadata) throws SAXException, IOException {
            TextCollector into = collectors.peek();
            into.enterEmbedded();
            depth++;
            try {
                parser.parse(in, new EmbeddedContentHandler(into), metadata, context(parser, this));
            } catch (TikaException | SAXException | IOException | RuntimeException e) {
                stopIfOver(e);
                failed++;
            } finally {
                depth--;
                into.leaveEmbedded();
            }
        }

        /** A mail message's attachment: a part of its own. */
        private void attachment(InputStream in, Counting counted, Metadata metadata, String name, MediaType type,
                                boolean readable) throws SAXException, IOException {
            TextCollector into = new TextCollector(budget, cancelled);
            String problem = readable ? null : "Kilnmark does not read the text of this kind of file";
            if (readable) {
                collectors.push(into);
                depth++;
                try {
                    parser.parse(in, into, metadata, context(parser, this));
                } catch (TikaException | SAXException | IOException | RuntimeException e) {
                    if (cancelled.get()) {
                        throw new TextCollector.Cancelled();
                    }
                    if (!into.truncated() && !(e instanceof TextCollector.LimitReached)) {
                        problem = DocumentRuntime.explain(e).getMessage();
                    }
                } finally {
                    depth--;
                    collectors.pop();
                }
            }
            drain(counted, limits.maxBytes() - counted.count);
            long size = counted.count;
            String contentType = type == null ? "application/octet-stream" : type.getBaseType().toString();
            attachments.add(part(name, contentType, size, into, metadata, DocumentTypes.mail(contentType), problem));
        }

        private static long drain(InputStream in, long max) throws IOException {
            long n = 0;
            byte[] buf = new byte[8192];
            int r;
            while (n < max && (r = in.read(buf, 0, (int) Math.min(buf.length, max - n))) > 0) {
                n += r;
            }
            return n;
        }

        /** The character budget spent or the time up: the whole read stops; anything else is this file's. */
        private void stopIfOver(Exception e) throws SAXException {
            if (cancelled.get()) {
                throw new TextCollector.Cancelled();
            }
            if (budget.left() <= 0) {
                throw new TextCollector.LimitReached();
            }
        }
    }
}

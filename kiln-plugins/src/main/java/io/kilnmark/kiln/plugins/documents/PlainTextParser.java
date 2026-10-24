package io.kilnmark.kiln.plugins.documents;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.XHTMLContentHandler;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

/**
 * Plain text and Markdown, and the plain-text parts of mail: lines are kept, blank lines end
 * paragraphs. The encoding is the declared one, else found as {@link Encodings} describes. A
 * Markdown file whose first line is a {@code # heading} takes it as its title. Lines are read in
 * pieces of at most {@value #PIECE} characters, so a file with no line breaks cannot exhaust memory.
 */
final class PlainTextParser implements Parser {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    static final int PIECE = 64 * 1024;

    private static final Set<MediaType> TYPES = Set.of(MediaType.TEXT_PLAIN, MediaType.text("x-web-markdown"),
        MediaType.text("markdown"), MediaType.text("x-markdown"));

    @Override
    public Set<MediaType> getSupportedTypes(ParseContext context) {
        return TYPES;
    }

    @Override
    public void parse(InputStream stream, ContentHandler handler, Metadata metadata, ParseContext context)
        throws IOException, SAXException, TikaException {
        BufferedInputStream in = new BufferedInputStream(stream, Encodings.SAMPLE + 16);
        Charset charset = Encodings.bom(in, metadata);
        if (charset == null) {
            charset = Encodings.declared(metadata);
        }
        if (charset == null) {
            charset = Encodings.utf8OrWindows1252(in, metadata);
        } else if (Encodings.bom(in, metadata) != null) {
            in.skipNBytes(charset == StandardCharsets.UTF_8 ? 3 : 2);
        }
        boolean markdown = isMarkdown(metadata);
        XHTMLContentHandler x = new XHTMLContentHandler(handler, metadata);
        x.startDocument();
        Reader r = new InputStreamReader(in, charset);
        StringBuilder line = new StringBuilder();
        boolean open = false;
        boolean first = true;
        int inParagraph = 0;
        int c;
        while (true) {
            c = r.read();
            if (c == '\n' || c < 0 || line.length() >= PIECE) {
                String l = line.toString();
                line.setLength(0);
                if (l.endsWith("\r")) {
                    l = l.substring(0, l.length() - 1);
                }
                if (l.isBlank()) {
                    if (open) {
                        x.endElement("p");
                        open = false;
                        inParagraph = 0;
                    }
                } else {
                    if (first) {
                        first = false;
                        if (markdown && l.startsWith("# ") && metadata.get(TikaCoreProperties.TITLE) == null) {
                            metadata.set(TikaCoreProperties.TITLE, l.substring(2).strip());
                        }
                    }
                    if (!open || inParagraph > PIECE) {
                        if (open) {
                            x.endElement("p");
                        }
                        x.startElement("p");
                        open = true;
                        inParagraph = 0;
                    } else {
                        x.characters("\n");
                    }
                    x.characters(l);
                    inParagraph += l.length();
                }
                if (c < 0) {
                    break;
                }
                if (c != '\n') {
                    line.append((char) c); // a piece cut at PIECE characters: keep this one
                }
            } else {
                line.append((char) c);
            }
        }
        if (open) {
            x.endElement("p");
        }
        x.endDocument();
    }

    private static boolean isMarkdown(Metadata metadata) {
        String type = metadata.get(Metadata.CONTENT_TYPE);
        String name = metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY);
        return (type != null && type.toLowerCase(Locale.ROOT).contains("markdown"))
            || (name != null && (name.toLowerCase(Locale.ROOT).endsWith(".md")
            || name.toLowerCase(Locale.ROOT).endsWith(".markdown")));
    }
}

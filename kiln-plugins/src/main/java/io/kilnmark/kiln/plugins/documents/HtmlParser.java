package io.kilnmark.kiln.plugins.documents;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.html.JSoupParser;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

/**
 * HTML and XHTML through Tika's jsoup parser, after dropping a DOCTYPE that carries an internal
 * subset ({@code <!DOCTYPE html [ <!ENTITY ...> ]>}). jsoup never expands such entities, but it
 * reads the subset's end as text, so declarations would show up in the document's text. Only the
 * first {@value #LOOK} bytes are looked at, in an ASCII-compatible encoding.
 */
final class HtmlParser implements Parser {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    static final int LOOK = 64 * 1024;

    private final JSoupParser jsoup = new JSoupParser(Encodings.detector());

    @Override
    public Set<MediaType> getSupportedTypes(ParseContext context) {
        return jsoup.getSupportedTypes(context);
    }

    @Override
    public void parse(InputStream stream, ContentHandler handler, Metadata metadata, ParseContext context)
        throws IOException, SAXException, TikaException {
        BufferedInputStream in = new BufferedInputStream(stream, LOOK + 16);
        in.mark(LOOK + 1);
        byte[] head = in.readNBytes(LOOK);
        in.reset();
        long skip = subsetEnd(new String(head, StandardCharsets.ISO_8859_1));
        if (skip > 0) {
            in.skipNBytes(skip);
        }
        jsoup.parse(in, handler, metadata, context);
    }

    /** Where a leading DOCTYPE with an internal subset ends, or 0 when there is none. */
    static int subsetEnd(String head) {
        String lower = head.toLowerCase(Locale.ROOT);
        int at = lower.indexOf("<!doctype");
        if (at < 0 || !lower.substring(0, at).replaceAll("<\\?[^>]*\\?>|<!--.*?-->|[\\s\\u00ef\\u00bb\\u00bf]", "")
            .isEmpty()) {
            return 0;
        }
        int open = lower.indexOf('[', at);
        int close = lower.indexOf('>', at);
        if (open < 0 || (close >= 0 && close < open)) {
            return 0; // a plain DOCTYPE: jsoup handles it
        }
        int end = lower.indexOf("]>", open);
        if (end < 0) {
            end = lower.indexOf(']', open);
            int gt = end < 0 ? -1 : lower.indexOf('>', end);
            return gt < 0 ? head.length() : gt + 1;
        }
        return end + 2;
    }
}

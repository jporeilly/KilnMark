package io.kilnmark.kiln.plugins.documents;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.List;

import org.apache.tika.detect.CompositeEncodingDetector;
import org.apache.tika.detect.EncodingDetector;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.html.HtmlEncodingDetector;

/**
 * Text encodings, found the way the files connector finds them for CSV: a byte-order mark
 * decides, then a charset the document or its envelope declares (HTML {@code meta}, a mail part's
 * Content-Type), then UTF-8 if the first bytes are valid UTF-8, else windows-1252. Nothing here
 * guesses from statistics, so no extra library is needed.
 */
final class Encodings {

    /** Bytes looked at to decide. */
    static final int SAMPLE = 64 * 1024;

    static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    private Encodings() {
    }

    /** The detector given to Tika's parsers: byte-order mark, then HTML's declaration, then UTF-8 or windows-1252. */
    static EncodingDetector detector() {
        return new CompositeEncodingDetector(List.of(Encodings::bom, new HtmlEncodingDetector(),
            Encodings::utf8OrWindows1252));
    }

    /** The charset a byte-order mark names, or null. */
    static Charset bom(InputStream in, Metadata metadata) throws IOException {
        if (in == null) {
            return null;
        }
        in.mark(4);
        byte[] head = in.readNBytes(3);
        in.reset();
        return bom(head);
    }

    static Charset bom(byte[] head) {
        if (head.length >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        if (head.length >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xFE) {
            return StandardCharsets.UTF_16LE;
        }
        if (head.length >= 2 && (head[0] & 0xFF) == 0xFE && (head[1] & 0xFF) == 0xFF) {
            return StandardCharsets.UTF_16BE;
        }
        return null;
    }

    /** UTF-8 when the first {@value #SAMPLE} bytes are valid UTF-8, else windows-1252. */
    static Charset utf8OrWindows1252(InputStream in, Metadata metadata) throws IOException {
        if (in == null) {
            return null;
        }
        in.mark(SAMPLE + 1);
        byte[] head = in.readNBytes(SAMPLE);
        in.reset();
        return utf8OrWindows1252(head, head.length >= SAMPLE);
    }

    static Charset utf8OrWindows1252(byte[] head, boolean truncated) {
        CharsetDecoder d = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(head);
        CharBuffer out = CharBuffer.allocate(8192);
        while (true) {
            CoderResult r = d.decode(in, out, !truncated);
            if (r.isError()) {
                return WINDOWS_1252;
            }
            if (r.isOverflow()) {
                out.clear();
                continue;
            }
            return StandardCharsets.UTF_8;
        }
    }

    /** The charset a Content-Type such as {@code text/plain; charset=iso-8859-1} declares, or null. */
    static Charset declared(Metadata metadata) {
        String type = metadata.get(Metadata.CONTENT_TYPE);
        String name = null;
        if (type != null) {
            MediaType t = MediaType.parse(type);
            name = t == null ? null : t.getParameters().get("charset");
        }
        if (name == null) {
            name = metadata.get(Metadata.CONTENT_ENCODING);
        }
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Charset.forName(name.strip());
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            return null;
        }
    }
}

package io.kilnmark.kiln.plugins.files;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Decodes text files. A byte-order mark (UTF-8, UTF-16LE, UTF-16BE) decides the encoding and is
 * skipped. Otherwise a file is UTF-8 if its first bytes are valid UTF-8, and windows-1252 (the
 * usual encoding of files saved by Windows programs in Western Europe) if they are not; a source's
 * {@code encoding} setting overrides this. Bytes still not valid become U+FFFD rather than failing.
 */
final class TextInput {

    /** The fallback for text that is not valid UTF-8. */
    static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    private TextInput() {
    }

    /** The encoding a byte-order mark names, or null when there is none. */
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

    /**
     * The encoding of a file from its first bytes: the byte-order mark's, else UTF-8 when they are
     * valid UTF-8 (a character cut off by the end of a truncated sample does not count), else
     * windows-1252.
     */
    static Charset detect(byte[] head, boolean truncated) {
        Charset bom = bom(head);
        if (bom != null) {
            return bom;
        }
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
            return StandardCharsets.UTF_8; // underflow: everything decoded, or a cut-off last character
        }
    }

    /** A reader over {@code in} in {@code charset}, positioned after any byte-order mark. */
    static Reader reader(InputStream in, Charset charset) throws IOException {
        PushbackInputStream p = new PushbackInputStream(in, 3);
        byte[] head = new byte[3];
        int n = p.readNBytes(head, 0, 3);
        byte[] got = java.util.Arrays.copyOf(head, n);
        Charset bom = bom(got);
        int skip = bom == null ? 0 : bom == StandardCharsets.UTF_8 ? 3 : 2;
        if (n > skip) {
            p.unread(got, skip, n - skip);
        }
        return new InputStreamReader(p, bom != null ? bom : charset);
    }

    /** Decodes a sample of a file's first bytes. */
    static String sample(byte[] head, Charset charset) throws IOException {
        try (Reader r = reader(new java.io.ByteArrayInputStream(head), charset)) {
            StringBuilder b = new StringBuilder(head.length);
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) {
                b.append(buf, 0, n);
            }
            return b.toString();
        }
    }
}

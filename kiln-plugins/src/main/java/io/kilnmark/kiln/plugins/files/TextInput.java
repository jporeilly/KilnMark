package io.kilnmark.kiln.plugins.files;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Decodes text files. They are read as UTF-8 unless they start with a byte-order mark, which is
 * skipped: UTF-8, UTF-16LE or UTF-16BE. Bytes that are not valid in the encoding become U+FFFD
 * rather than failing the read.
 */
final class TextInput {

    private TextInput() {
    }

    /** A reader over {@code in}, positioned after any byte-order mark. */
    static Reader reader(InputStream in) throws IOException {
        PushbackInputStream p = new PushbackInputStream(in, 3);
        byte[] head = new byte[3];
        int n = p.readNBytes(head, 0, 3);
        Charset charset = StandardCharsets.UTF_8;
        int skip = 0;
        if (n >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF) {
            skip = 3;
        } else if (n >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xFE) {
            charset = StandardCharsets.UTF_16LE;
            skip = 2;
        } else if (n >= 2 && (head[0] & 0xFF) == 0xFE && (head[1] & 0xFF) == 0xFF) {
            charset = StandardCharsets.UTF_16BE;
            skip = 2;
        }
        if (n > skip) {
            p.unread(head, skip, n - skip);
        }
        return new InputStreamReader(p, charset);
    }

    /** Decodes a sample of a file's first bytes. */
    static String sample(byte[] head) throws IOException {
        try (Reader r = reader(new java.io.ByteArrayInputStream(head))) {
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

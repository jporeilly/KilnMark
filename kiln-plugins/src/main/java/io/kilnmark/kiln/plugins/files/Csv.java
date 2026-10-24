package io.kilnmark.kiln.plugins.files;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Delimited text: finding the delimiter and quote, and reading records. Reading is lenient, in the
 * way spreadsheets are: a quote only opens a quoted field at the start of a field, a doubled quote
 * inside one is a literal quote, quoted fields may span lines, short rows are padded with nulls and
 * extra fields are ignored. An empty unquoted field is null; a quoted empty field ({@code ""}) is
 * an empty string.
 */
final class Csv {

    /** Delimiters tried, most common first. */
    static final char[] DELIMITERS = {',', ';', '\t', '|'};

    /** A quoted field longer than this almost always means a quote was left open. */
    static final int MAX_FIELD = 16 << 20;

    private Csv() {
    }

    /**
     * How a file is delimited.
     *
     * @param sepLine whether the file starts with Excel's {@code sep=X} line, which names the
     *                delimiter and is not part of the data
     */
    record Dialect(char delimiter, char quote, boolean sepLine) {

        /** As shown in hints: {@code \t} for a tab. */
        String delimiterName() {
            return delimiter == '\t' ? "\\t" : String.valueOf(delimiter);
        }

        static char parse(String hint, char fallback) {
            if (hint == null || hint.isEmpty()) {
                return fallback;
            }
            return hint.equals("\\t") ? '\t' : hint.charAt(0);
        }
    }

    /**
     * Finds how a sample is delimited.
     *
     * <ul>
     *   <li>A first line {@code sep=X}, which Excel writes and reads, names the delimiter.</li>
     *   <li>Otherwise TSV files use a tab, and other files the delimiter that splits the header into
     *       the most fields while keeping the same number of fields in the rows below it.</li>
     *   <li>The quote is {@code "}. It is {@code '} only when some fields start with {@code '},
     *       none with {@code "}, every field that starts with {@code '} is closed by a {@code '}
     *       followed by the delimiter or the end of a line, and reading with {@code '} keeps the
     *       number of fields per row at least as steady. An apostrophe at the start of a value
     *       ({@code 'tis}, Excel's {@code '+44 ...}) is therefore text, not a quote.</li>
     * </ul>
     */
    static Dialect detect(String sample, FileFormat format) {
        Character sep = excelSeparator(sample);
        String body = sample;
        if (sep != null) {
            int nl = sample.indexOf('\n');
            body = nl < 0 ? "" : sample.substring(nl + 1);
        }
        char delimiter;
        if (sep != null) {
            delimiter = sep;
        } else if (format == FileFormat.TSV) {
            delimiter = '\t';
        } else {
            delimiter = ',';
            double bestScore = -1;
            for (char d : DELIMITERS) {
                double score = score(body, d, '"');
                if (score > bestScore) {
                    bestScore = score;
                    delimiter = d;
                }
            }
        }
        char quote = singleQuoted(body, delimiter) && score(body, delimiter, '\'') >= score(body, delimiter, '"')
            ? '\'' : '"';
        return new Dialect(delimiter, quote, sep != null);
    }

    /** The delimiter named by a first line {@code sep=X}, or null. */
    static Character excelSeparator(String sample) {
        if (sample.length() >= 5 && sample.regionMatches(true, 0, "sep=", 0, 4)) {
            char c = sample.charAt(4);
            int end = 5;
            if (end < sample.length() && sample.charAt(end) == '\r') {
                end++;
            }
            if (c != '\n' && c != '\r' && (end == sample.length() || sample.charAt(end) == '\n')) {
                return c;
            }
        }
        return null;
    }

    private static double score(String sample, char delimiter, char quote) {
        Parser p = new Parser(new StringReader(sample), delimiter, quote);
        try {
            List<String> header = p.next();
            if (header == null) {
                return 0;
            }
            int rows = 0;
            int same = 0;
            List<String> r;
            while (rows < 50 && (r = p.next()) != null) {
                rows++;
                if (r.size() == header.size()) {
                    same++;
                }
            }
            // one ragged row is forgiven: the sample may end part-way through the last one
            double consistency = rows == 0 ? 1 : (double) Math.min(rows, same + 1) / rows;
            return header.size() < 2 ? consistency : consistency * 1000 + header.size();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Whether some fields start with {@code '}, none with {@code "}, and every field starting
     * with {@code '} is properly closed.
     */
    static boolean singleQuoted(String sample, char delimiter) {
        int quoted = 0;
        boolean fieldStart = true;
        int i = 0;
        int n = sample.length();
        while (i < n) {
            char c = sample.charAt(i);
            if (fieldStart && c == '"') {
                return false;
            }
            if (fieldStart && c == '\'') {
                // find the closing quote, skipping doubled ones
                int j = i + 1;
                while (true) {
                    int q = sample.indexOf('\'', j);
                    if (q < 0) {
                        return false; // never closed
                    }
                    if (q + 1 < n && sample.charAt(q + 1) == '\'') {
                        j = q + 2;
                        continue;
                    }
                    if (q + 1 < n) {
                        char after = sample.charAt(q + 1);
                        if (after != delimiter && after != '\n' && after != '\r') {
                            return false; // closed in the middle of a value
                        }
                    }
                    i = q + 1;
                    break;
                }
                quoted++;
                fieldStart = false;
                continue;
            }
            fieldStart = c == delimiter || c == '\n' || c == '\r';
            i++;
        }
        return quoted > 0;
    }

    /** Column names from a header row: blanks become {@code column_N}, repeats get {@code _2}, {@code _3}. */
    static List<String> names(List<String> header) {
        List<String> out = new ArrayList<>(header.size());
        Set<String> used = new HashSet<>();
        for (int i = 0; i < header.size(); i++) {
            String h = header.get(i);
            String name = h == null || h.isBlank() ? "column_" + (i + 1)
                : h.strip().replace('\n', ' ').replace('\r', ' ');
            String unique = name;
            for (int n = 2; !used.add(unique); n++) {
                unique = name + "_" + n;
            }
            out.add(unique);
        }
        return out;
    }

    /** Reads records one at a time from a character stream. */
    static final class Parser {

        private final Reader in;
        private final char delimiter;
        private final char quote;
        private final char[] buf = new char[1 << 16];
        private int pos;
        private int len;
        private int pushback = -2;
        private long line = 1;
        private final StringBuilder field = new StringBuilder();

        Parser(Reader in, char delimiter, char quote) {
            this.in = in;
            this.delimiter = delimiter;
            this.quote = quote;
        }

        /** The line the parser has reached, for messages. */
        long line() {
            return line;
        }

        private int read() throws IOException {
            if (pushback != -2) {
                int c = pushback;
                pushback = -2;
                return c;
            }
            if (pos == len) {
                len = in.read(buf, 0, buf.length);
                pos = 0;
                if (len <= 0) {
                    len = 0;
                    return -1;
                }
            }
            return buf[pos++];
        }

        /** The next record's fields, or null at the end. Blank lines are skipped. */
        List<String> next() throws IOException {
            int c = read();
            while (c == '\n' || c == '\r') {
                line++;
                c = read();
            }
            if (c == -1) {
                return null;
            }
            List<String> row = new ArrayList<>();
            field.setLength(0);
            boolean quoted = false;
            boolean inQuotes = false;
            while (true) {
                if (c == -1) {
                    row.add(value(quoted));
                    return row;
                }
                if (inQuotes) {
                    if (c == quote) {
                        int n = read();
                        if (n == quote) {
                            field.append(quote);
                            c = read();
                        } else {
                            inQuotes = false;
                            c = n;
                        }
                        continue;
                    }
                    if (c == '\n') {
                        line++;
                    }
                    append(c);
                    c = read();
                    continue;
                }
                if (c == delimiter) {
                    row.add(value(quoted));
                    field.setLength(0);
                    quoted = false;
                    c = read();
                } else if (c == '\n' || c == '\r') {
                    if (c == '\r') {
                        int n = read();
                        if (n != '\n') {
                            pushback = n;
                        }
                    }
                    line++;
                    row.add(value(quoted));
                    return row;
                } else if (c == quote && field.isEmpty() && !quoted) {
                    inQuotes = true;
                    quoted = true;
                    c = read();
                } else {
                    append(c);
                    c = read();
                }
            }
        }

        private void append(int c) throws IOException {
            if (field.length() >= MAX_FIELD) {
                throw new IOException("A field near line " + line + " is longer than " + (MAX_FIELD >> 20)
                    + " MiB, so a quote is probably left open: check the file for an unbalanced quote");
            }
            field.append((char) c);
        }

        private String value(boolean quoted) {
            return field.isEmpty() && !quoted ? null : field.toString();
        }
    }
}

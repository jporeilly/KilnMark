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

    /** How a file is delimited. */
    record Dialect(char delimiter, char quote) {

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
     * Picks the delimiter that splits the sample's header into the most fields and keeps the same
     * number of fields in the rows after it; a tab for TSV files. The quote is {@code "} unless
     * fields start with {@code '} and never with {@code "}.
     */
    static Dialect detect(String sample, FileFormat format) {
        char quote = detectQuote(sample);
        if (format == FileFormat.TSV) {
            return new Dialect('\t', quote);
        }
        char best = ',';
        double bestScore = -1;
        for (char d : DELIMITERS) {
            double score = score(sample, d, quote);
            if (score > bestScore) {
                bestScore = score;
                best = d;
            }
        }
        return new Dialect(best, quote);
    }

    private static double score(String sample, char delimiter, char quote) {
        Parser p = new Parser(new StringReader(sample), delimiter, quote);
        try {
            List<String> header = p.next();
            if (header == null || header.size() < 2) {
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
            return consistency * 1000 + header.size();
        } catch (IOException e) {
            return 0;
        }
    }

    private static char detectQuote(String sample) {
        int dq = 0;
        int sq = 0;
        boolean fieldStart = true;
        for (int i = 0; i < sample.length(); i++) {
            char c = sample.charAt(i);
            if (fieldStart) {
                if (c == '"') dq++;
                else if (c == '\'') sq++;
            }
            fieldStart = c == '\n' || c == '\r' || c == ',' || c == ';' || c == '\t' || c == '|';
        }
        return sq > 0 && dq == 0 ? '\'' : '"';
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

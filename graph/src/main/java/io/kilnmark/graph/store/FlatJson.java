package io.kilnmark.graph.store;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** A JSON object of string values, enough for source settings; keeps the graph module free of libraries. */
final class FlatJson {

    private FlatJson() {
    }

    static String write(Map<String, String> map) {
        StringBuilder b = new StringBuilder("{");
        boolean first = true;
        for (var e : new TreeMap<>(map).entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            b.append(first ? "" : ",");
            first = false;
            string(b, e.getKey());
            b.append(':');
            string(b, e.getValue());
        }
        return b.append('}').toString();
    }

    private static void string(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
                }
            }
        }
        b.append('"');
    }

    /** Reads a flat object; values that are not strings are kept as their JSON text. */
    static Map<String, String> read(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        if (json == null) {
            return out;
        }
        Parser p = new Parser(json);
        p.skip();
        p.expect('{');
        p.skip();
        if (p.peek() == '}') {
            return out;
        }
        while (true) {
            p.skip();
            String key = p.string();
            p.skip();
            p.expect(':');
            p.skip();
            String value = p.peek() == '"' ? p.string() : p.scalar();
            out.put(key, value);
            p.skip();
            char c = p.next();
            if (c == '}') {
                return out;
            }
            if (c != ',') {
                throw new IllegalArgumentException("Bad settings JSON at " + p.pos);
            }
        }
    }

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) {
            this.s = s;
        }

        char peek() {
            return s.charAt(pos);
        }

        char next() {
            return s.charAt(pos++);
        }

        void skip() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        void expect(char c) {
            if (next() != c) throw new IllegalArgumentException("Bad settings JSON: expected " + c + " at " + (pos - 1));
        }

        String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') return b.toString();
                if (c != '\\') {
                    b.append(c);
                    continue;
                }
                char e = next();
                switch (e) {
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    case 't' -> b.append('\t');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'u' -> {
                        b.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> b.append(e);
                }
            }
        }

        String scalar() {
            int start = pos;
            while (pos < s.length() && ",}".indexOf(s.charAt(pos)) < 0) pos++;
            return s.substring(start, pos).trim();
        }
    }
}

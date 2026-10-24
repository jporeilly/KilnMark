package io.kilnmark.kiln.plugins.files;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

/**
 * Reads JSON records: one object per line ({@link FileFormat#JSON_LINES}), or for
 * {@link FileFormat#JSON} a top-level array of objects or objects one after another. Each object's
 * own fields become columns; nested objects and arrays are kept as JSON text. A value that is not
 * an object becomes a record with one field, {@code value}.
 *
 * <p>In JSON lines, a line that is not valid JSON, or longer than {@value #MAX_LINE} characters, is
 * skipped and counted; an over-long line is read past without being kept. In a JSON document a
 * syntax error ends the read with a message naming the line.
 */
final class JsonRecords implements Closeable {

    /** One field value: its text, and what kind of JSON value it was. */
    record Value(String text, TypeGuess.Origin origin) {
    }

    /** The longest JSON line read, in characters: as much as a catalogue samples. */
    static final int MAX_LINE = FileInspector.SAMPLE_BYTES;

    private static final JsonFactory FACTORY = JsonFactory.builder().build();

    private final boolean lines;
    private final Reader lineReader;
    private final StringBuilder line = new StringBuilder();
    private final char[] buf = new char[1 << 16];
    private int pos;
    private int len;
    private final JsonParser parser;
    private boolean inArray;
    private boolean started;
    private long skipped;

    JsonRecords(Reader reader, FileFormat format) throws IOException {
        this.lines = format == FileFormat.JSON_LINES;
        if (lines) {
            lineReader = reader;
            parser = null;
        } else {
            lineReader = null;
            parser = FACTORY.createParser(reader);
        }
    }

    /** Malformed lines skipped so far (JSON lines only). */
    long skipped() {
        return skipped;
    }

    /** The next record, its fields in the order they appear, or null at the end. */
    Map<String, Value> next() throws IOException {
        return lines ? nextLine() : nextValue();
    }

    /** Whether the line just read was longer than {@link #MAX_LINE}. */
    private boolean overLong;

    /**
     * Reads the next line into {@link #line}: false at the end of the input. A line longer than
     * {@link #MAX_LINE} is read to its end but not kept, and leaves {@link #line} empty with
     * {@link #overLong} set.
     */
    private boolean readLine() throws IOException {
        line.setLength(0);
        overLong = false;
        boolean any = false;
        while (true) {
            if (pos == len) {
                len = lineReader.read(buf, 0, buf.length);
                pos = 0;
                if (len <= 0) {
                    len = 0;
                    return any;
                }
            }
            any = true;
            int start = pos;
            while (pos < len && buf[pos] != '\n') {
                pos++;
            }
            if (!overLong) {
                if (line.length() + (pos - start) > MAX_LINE) {
                    overLong = true;
                    line.setLength(0);
                } else {
                    line.append(buf, start, pos - start);
                }
            }
            if (pos < len) {
                pos++; // the newline
                return true;
            }
        }
    }

    private Map<String, Value> nextLine() throws IOException {
        while (readLine()) {
            if (overLong) {
                skipped++;
                continue;
            }
            if (line.toString().isBlank()) {
                continue;
            }
            try (JsonParser p = FACTORY.createParser(line.toString())) {
                JsonToken t = p.nextToken();
                Map<String, Value> record = record(p, t);
                if (p.nextToken() != null) {
                    throw new com.fasterxml.jackson.core.JsonParseException(p, "more than one value on the line");
                }
                return record;
            } catch (com.fasterxml.jackson.core.JacksonException e) {
                skipped++;
            }
        }
        return null;
    }

    private Map<String, Value> nextValue() throws IOException {
        try {
            JsonToken t = parser.nextToken();
            if (!started) {
                started = true;
                if (t == JsonToken.START_ARRAY) {
                    inArray = true;
                    t = parser.nextToken();
                }
            }
            if (t == null || (inArray && t == JsonToken.END_ARRAY)) {
                return null;
            }
            return record(parser, t);
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            throw new IOException("Not valid JSON near line " + parser.currentLocation().getLineNr() + ": "
                + e.getOriginalMessage(), e);
        }
    }

    /** Reads the value starting at {@code t} as a record. */
    private static Map<String, Value> record(JsonParser p, JsonToken t) throws IOException {
        Map<String, Value> out = new LinkedHashMap<>();
        if (t != JsonToken.START_OBJECT) {
            out.put("value", value(p, t));
            return out;
        }
        while ((t = p.nextToken()) == JsonToken.FIELD_NAME) {
            String name = p.currentName();
            out.put(name, value(p, p.nextToken()));
        }
        return out;
    }

    private static Value value(JsonParser p, JsonToken t) throws IOException {
        if (t == null) {
            throw new com.fasterxml.jackson.core.JsonParseException(p, "unexpected end of input");
        }
        return switch (t) {
            case VALUE_NULL -> null;
            case VALUE_STRING -> new Value(p.getText(), TypeGuess.Origin.JSON_STRING);
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> new Value(p.getText(), TypeGuess.Origin.JSON_NUMBER);
            case VALUE_TRUE, VALUE_FALSE -> new Value(p.getText(), TypeGuess.Origin.JSON_BOOLEAN);
            case START_OBJECT, START_ARRAY -> {
                StringWriter w = new StringWriter();
                try (JsonGenerator g = FACTORY.createGenerator(w)) {
                    g.copyCurrentStructure(p);
                }
                yield new Value(w.toString(), TypeGuess.Origin.JSON_NESTED);
            }
            default -> throw new com.fasterxml.jackson.core.JsonParseException(p, "unexpected " + t);
        };
    }

    @Override
    public void close() throws IOException {
        if (parser != null) {
            parser.close();
        }
        if (lineReader != null) {
            lineReader.close();
        }
    }
}

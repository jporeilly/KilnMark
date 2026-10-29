package io.kilnmark.kiln.plugins.documents;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;

import com.fasterxml.jackson.core.io.JsonStringEncoder;

/**
 * What a document reader reports: one {@code DOCUMENT} event per document (and per attachment of
 * a mail message), and the text's chunks as Arrow rows. The server stores both; a node placed
 * between the reader and the store (embeddings, later) sees every chunk as a row with a
 * {@value #TEXT} column.
 */
public final class DocumentEvents {

    // DOCUMENT event attributes, besides the Extraction properties (title, author, created, ...)
    public static final String NAME = "name";
    /** For an attachment: the URN of the document it is attached to. */
    public static final String PARENT = "parent";
    public static final String FORMAT = "format";
    public static final String CONTENT_TYPE = "content_type";
    public static final String SIZE = "size";
    public static final String WORDS = "words";
    public static final String CHARACTERS = "characters";
    public static final String LANGUAGE = "language";
    public static final String OWNER = "owner";
    /** A JSON object of tag names to values. */
    public static final String TAGS = "tags";
    public static final String READABLE = "readable";
    public static final String PROBLEM = "problem";
    /**
     * "true" when the read failed for a passing reason (busy, out of time, stopped, source not
     * reachable): what an earlier scan stored about the document should be kept.
     */
    public static final String PASSING = "passing";
    public static final String TRUNCATED = "truncated";
    public static final String TEXT_KEPT = "text_kept";
    public static final String CHUNKS = "chunks";
    /** A JSON array of strings. */
    public static final String NOTES = "notes";
    /** A JSON array of {"number": 1, "start": 0, "label": "Sheet1"}. */
    public static final String PAGE_LIST = "page_list";

    // Chunk rows
    public static final String ASSET_URN = "asset_urn";
    public static final String SEQUENCE = "sequence";
    public static final String TEXT = "text";
    public static final String PAGE = "page";
    public static final String START = "start_offset";
    public static final String END = "end_offset";

    /** The Arrow schema of chunk rows. {@code page} is null when not known. */
    public static final Schema CHUNK_SCHEMA = new Schema(List.of(
        new Field(ASSET_URN, FieldType.notNullable(ArrowType.Utf8.INSTANCE), null),
        new Field(SEQUENCE, FieldType.notNullable(new ArrowType.Int(32, true)), null),
        new Field(TEXT, FieldType.notNullable(ArrowType.Utf8.INSTANCE), null),
        new Field(PAGE, FieldType.nullable(new ArrowType.Int(32, true)), null),
        new Field(START, FieldType.notNullable(new ArrowType.Int(32, true)), null),
        new Field(END, FieldType.notNullable(new ArrowType.Int(32, true)), null)));

    private DocumentEvents() {
    }

    /**
     * The attributes of a part's {@code DOCUMENT} event.
     *
     * @param chunks chunks kept for it; 0 when text is not kept
     */
    public static Map<String, String> attributes(Extraction.Part part, String format, boolean textKept, int chunks,
                                                 List<String> notes) {
        Map<String, String> a = new HashMap<>(part.properties());
        put(a, NAME, part.name());
        put(a, FORMAT, format);
        put(a, CONTENT_TYPE, part.contentType());
        if (part.size() >= 0) {
            a.put(SIZE, Long.toString(part.size()));
        }
        a.put(READABLE, Boolean.toString(part.problem() == null));
        put(a, PROBLEM, part.problem());
        if (part.problem() == null) {
            a.put(WORDS, Long.toString(TextStats.words(part.text())));
            a.put(CHARACTERS, Integer.toString(part.text().length()));
            put(a, LANGUAGE, TextStats.language(part.text()));
            a.put(TRUNCATED, Boolean.toString(part.truncated()));
        }
        a.put(TEXT_KEPT, Boolean.toString(textKept));
        a.put(CHUNKS, Integer.toString(chunks));
        if (notes != null && !notes.isEmpty()) {
            a.put(NOTES, jsonArray(notes));
        }
        if (!part.pages().isEmpty()) {
            StringBuilder b = new StringBuilder("[");
            for (Extraction.Page p : part.pages()) {
                if (b.length() > 1) {
                    b.append(',');
                }
                b.append("{\"number\":").append(p.number()).append(",\"start\":").append(p.start());
                if (p.label() != null) {
                    b.append(",\"label\":").append(json(p.label()));
                }
                b.append('}');
            }
            a.put(PAGE_LIST, b.append(']').toString());
        }
        return a;
    }

    /** A failed document's attributes: the reason, nothing else read. */
    public static Map<String, String> unreadable(String name, String format, String contentType, long size,
                                                 String problem) {
        return attributes(new Extraction.Part(name, contentType, size, "", List.of(), Map.of(), false, problem),
            format, false, 0, List.of());
    }

    private static void put(Map<String, String> a, String key, String value) {
        if (value != null) {
            a.put(key, value);
        }
    }

    /** A JSON string literal. */
    public static String json(String s) {
        return "\"" + new String(JsonStringEncoder.getInstance().quoteAsString(s)) + "\"";
    }

    public static String jsonArray(List<String> values) {
        List<String> quoted = new ArrayList<>();
        for (String v : values) {
            quoted.add(json(v));
        }
        return "[" + String.join(",", quoted) + "]";
    }

    /** A JSON object of strings. */
    public static String jsonObject(Map<String, String> values) {
        List<String> pairs = new ArrayList<>();
        values.forEach((k, v) -> pairs.add(json(k) + ":" + json(v)));
        return "{" + String.join(",", pairs) + "}";
    }
}

package io.kilnmark.graph.store;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Documents as the store reads and writes them (migration V7). */
public final class DocumentRecords {

    private DocumentRecords() {
    }

    /** One page, slide or sheet of a document's text. */
    public record Page(int number, int start, String label) {
    }

    /**
     * What reading a document found.
     *
     * @param mail      from, to, cc, subject and date of a mail message; empty for other documents
     * @param tags      S3 object tags; empty when none or not readable
     */
    public record Document(String urn, boolean readable, String problem, String format, String contentType,
                           Long sizeBytes, Integer pages, Long words, Long characters, String language,
                           String title, String author, Instant created, Instant modified, String owner,
                           Map<String, String> tags, Map<String, String> mail, List<Page> pageList,
                           List<String> notes, boolean truncated, boolean textKept, int chunkCount,
                           Instant observedAt) {
        public Document {
            tags = Map.copyOf(tags == null ? Map.of() : tags);
            mail = Map.copyOf(mail == null ? Map.of() : mail);
            pageList = List.copyOf(pageList == null ? List.of() : pageList);
            notes = List.copyOf(notes == null ? List.of() : notes);
        }
    }

    /** One chunk of a document's text. */
    public record Chunk(int sequence, String text, Integer page, int start, int end) {
    }

    /** An asset inside another: a sheet of a workbook, an attachment of a message. */
    public record Part(String urn, String kind, String name, boolean readable, String description,
                       Instant removedAt) {
    }

    /**
     * A short passage of a document's text, with the matched words marked by their positions in
     * {@code text} (so a page shows them highlighted without treating the text as markup).
     */
    public record Snippet(String text, List<Highlight> highlights) {
        public Snippet {
            highlights = List.copyOf(highlights);
        }
    }

    /** {@code length} characters from {@code start} in a snippet's text. */
    public record Highlight(int start, int length) {
    }

    /** Characters ts_headline puts around matches; never kept in documents' text. */
    public static final char MARK_START = '\uE000';
    public static final char MARK_END = '\uE001';

    /** A snippet from text with matches between {@link #MARK_START} and {@link #MARK_END}. */
    public static Snippet snippet(String marked) {
        StringBuilder text = new StringBuilder();
        List<Highlight> highlights = new java.util.ArrayList<>();
        int start = -1;
        String s = marked.replaceAll("\\s+", " ").strip();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == MARK_START) {
                start = text.length();
            } else if (c == MARK_END) {
                if (start >= 0 && text.length() > start) {
                    highlights.add(new Highlight(start, text.length() - start));
                }
                start = -1;
            } else {
                text.append(c);
            }
        }
        return new Snippet(text.toString(), highlights);
    }
}

package io.kilnmark.kiln.plugins.documents;

import java.util.List;
import java.util.Map;

/**
 * What reading one document found: its own text and properties, and for mail, each attachment as
 * a part of its own.
 *
 * @param document    the document itself (for mail, the message and its body)
 * @param attachments the attachments of a mail message, in order; empty for other documents
 * @param notes       things a person should know, such as nested files that were skipped
 */
public record Extraction(Part document, List<Part> attachments, List<String> notes) {

    public Extraction {
        attachments = List.copyOf(attachments);
        notes = List.copyOf(notes);
    }

    /** Property names in {@link Part#properties()}. */
    public static final String TITLE = "title";
    public static final String AUTHOR = "author";
    public static final String CREATED = "created";
    public static final String MODIFIED = "modified";
    public static final String PAGES = "pages";
    public static final String FROM = "from";
    public static final String TO = "to";
    public static final String CC = "cc";
    public static final String SUBJECT = "subject";
    public static final String DATE = "date";

    /**
     * Where a page (PDF), slide (presentations) or sheet (workbooks) starts in a part's text.
     *
     * @param number 1-based
     * @param start  character offset in the text
     * @param label  the page's own heading (a sheet's name), or null
     */
    public record Page(int number, int start, String label) {
    }

    /**
     * One document's text and properties.
     *
     * @param name        the file name (an attachment's own name)
     * @param contentType the media type, without parameters
     * @param size        bytes, or -1 when not known
     * @param text        the text, paragraphs separated by blank lines; empty when none was read
     * @param pages       page, slide or sheet boundaries; empty when the format has none
     * @param properties  title, author, created, modified (ISO 8601), pages; for mail from, to, cc,
     *                    subject, date. Only those found
     * @param truncated   whether the character limit cut the text short
     * @param problem     why the text could not be read, or null
     */
    public record Part(String name, String contentType, long size, String text, List<Page> pages,
                       Map<String, String> properties, boolean truncated, String problem) {

        public Part {
            pages = List.copyOf(pages);
            properties = Map.copyOf(properties);
        }
    }
}

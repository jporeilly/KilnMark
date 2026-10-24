package io.kilnmark.kiln.plugins.documents;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The kinds of document Kilnmark reads text from, by file extension. Anything else (images,
 * archives, media) is recorded with its size and time but not read.
 */
public final class DocumentTypes {

    private static final Map<String, String> TYPES = Map.ofEntries(
        Map.entry("pdf", "application/pdf"),
        Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        Map.entry("docm", "application/vnd.ms-word.document.macroenabled.12"),
        Map.entry("doc", "application/msword"),
        Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
        Map.entry("pptm", "application/vnd.ms-powerpoint.presentation.macroenabled.12"),
        Map.entry("ppt", "application/vnd.ms-powerpoint"),
        Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        Map.entry("xlsm", "application/vnd.ms-excel.sheet.macroenabled.12"),
        Map.entry("xls", "application/vnd.ms-excel"),
        Map.entry("rtf", "application/rtf"),
        Map.entry("html", "text/html"),
        Map.entry("htm", "text/html"),
        Map.entry("xhtml", "application/xhtml+xml"),
        Map.entry("eml", "message/rfc822"),
        Map.entry("msg", "application/vnd.ms-outlook"),
        Map.entry("txt", "text/plain"),
        Map.entry("text", "text/plain"),
        Map.entry("md", "text/x-web-markdown"),
        Map.entry("markdown", "text/x-web-markdown"),
        Map.entry("odt", "application/vnd.oasis.opendocument.text"),
        Map.entry("ods", "application/vnd.oasis.opendocument.spreadsheet"),
        Map.entry("odp", "application/vnd.oasis.opendocument.presentation"),
        Map.entry("xml", "application/xml"),
        Map.entry("epub", "application/epub+zip"));

    /** Formats read whole into memory by their parsers, so they have a lower size limit. */
    private static final Set<String> LEGACY = Set.of("doc", "xls", "ppt", "msg");

    /** Workbooks whose sheets may be tables. */
    private static final Set<String> WORKBOOKS = Set.of("xlsx", "xlsm", "xls");

    private DocumentTypes() {
    }

    /** The lower-case extension of a file name or path, or empty. */
    public static String extension(String name) {
        String n = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        return dot <= 0 ? "" : n.substring(dot + 1);
    }

    /** Whether Kilnmark reads the text of a file with this name. */
    public static boolean readable(String name) {
        return TYPES.containsKey(extension(name));
    }

    /** The media type of a readable document, or null. */
    public static String mediaType(String name) {
        return TYPES.get(extension(name));
    }

    /** Whether the file is a legacy Office file, read whole into memory. */
    public static boolean legacy(String name) {
        return LEGACY.contains(extension(name));
    }

    /** Whether the media type is a mail message, whose attachments become assets of their own. */
    public static boolean mail(String mediaType) {
        return "message/rfc822".equals(mediaType) || "application/vnd.ms-outlook".equals(mediaType);
    }

    /** Whether the file is an Excel workbook, whose sheets may be read as tables. */
    public static boolean workbook(String name) {
        return WORKBOOKS.contains(extension(name));
    }
}

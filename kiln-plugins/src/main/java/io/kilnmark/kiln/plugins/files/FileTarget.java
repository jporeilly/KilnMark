package io.kilnmark.kiln.plugins.files;

import java.util.Locale;

import io.kilnmark.kiln.connect.ConnectorException;

/**
 * Where a pipeline's {@code write} node puts a file: a path relative to the source's folder or
 * bucket prefix, checked before anything is opened. Refused: an empty path, an absolute one
 * ({@code /…}, {@code C:…}), one holding {@code ..}, {@code //}, a backslash or a control
 * character, a folder or file name starting with a dot (scans skip hidden files, so the file would
 * never be found again), a path ending in {@code /}, and a file name whose extension does not match
 * the format. The stores check again, after normalisation and (for folders) with links resolved,
 * that the file stays inside the source.
 *
 * @param path   the checked path, as given
 * @param format {@code csv} or {@code parquet}
 */
public record FileTarget(String path, String format) {

    /** The formats a file can be written in. */
    public static final java.util.List<String> FORMATS = java.util.List.of("csv", "parquet");

    /** The longest path accepted, in characters. */
    static final int MAX_LENGTH = 1024;

    /**
     * Checks a target and its format.
     *
     * @param format {@code csv}, {@code parquet}, or null to take it from the file's extension
     * @throws ConnectorException saying what is wrong and what to write instead
     */
    public static FileTarget of(String target, String format) throws ConnectorException {
        if (target == null || target.isBlank()) {
            throw new ConnectorException("path: give the file to write, relative to the source, for example "
                + "exports/customers.csv");
        }
        if (target.length() > MAX_LENGTH) {
            throw new ConnectorException("path: at most " + MAX_LENGTH + " characters");
        }
        for (int i = 0; i < target.length(); i++) {
            char c = target.charAt(i);
            if (c < 0x20 || c == 0x7f || (c >= 0x80 && c < 0xa0) || c == ' ' || c == ' ') {
                throw new ConnectorException("path: '" + printable(target) + "' holds a control character; "
                    + "use letters, digits, spaces, dashes, underscores, dots and / between folders");
            }
        }
        if (target.indexOf('\\') >= 0) {
            throw new ConnectorException("path: '" + target + "' holds a backslash; separate folders with /");
        }
        if (target.startsWith("/") || target.matches("^[A-Za-z]:.*") || target.startsWith("~")) {
            throw new ConnectorException("path: '" + target + "' is absolute; give a path inside the source, "
                + "for example exports/customers.csv");
        }
        if (target.contains("..")) {
            throw new ConnectorException("path: '" + target + "' holds '..'; a write stays inside its source, so "
                + "give a path below the source's folder");
        }
        if (target.contains("//")) {
            throw new ConnectorException("path: '" + target + "' holds '//'; separate folders with a single /");
        }
        if (target.endsWith("/")) {
            throw new ConnectorException("path: '" + target + "' is a folder; give a file name too, for example "
                + target + "data.csv");
        }
        for (String part : target.split("/")) {
            if (part.startsWith(".")) {
                throw new ConnectorException("path: '" + part + "' starts with a dot; scans skip hidden files and "
                    + "folders, so the file would never be catalogued");
            }
            if (part.isBlank() || !part.equals(part.strip())) {
                throw new ConnectorException("path: folder and file names must not start or end with a space");
            }
        }
        String name = target.substring(target.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String f = format == null || format.isBlank() ? null : format.strip().toLowerCase(Locale.ROOT);
        if (f == null) {
            if (!FORMATS.contains(extension)) {
                throw new ConnectorException("format: csv or parquet; the file name '" + name
                    + "' does not say which");
            }
            f = extension;
        }
        if (!FORMATS.contains(f)) {
            throw new ConnectorException("format: csv or parquet, not '" + format + "'");
        }
        if (!extension.equals(f)) {
            throw new ConnectorException("path: the file name '" + name + "' should end in ." + f
                + " for format " + f + ", so readers recognise it");
        }
        return new FileTarget(target, f);
    }

    private static String printable(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(c < 0x20 || c == 0x7f ? String.format("\\u%04x", (int) c) : String.valueOf(c));
        }
        return b.toString();
    }
}

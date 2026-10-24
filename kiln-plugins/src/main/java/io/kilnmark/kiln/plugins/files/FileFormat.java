package io.kilnmark.kiln.plugins.files;

import java.util.Locale;

/**
 * What a file holds, decided by its extension. Files Kilnmark cannot read as rows are documents:
 * they are recorded with their size and time, not read.
 */
public enum FileFormat {

    /** Comma-, semicolon-, tab- or pipe-separated values with a header row. */
    CSV("csv"),
    /** Tab-separated values with a header row. */
    TSV("tsv"),
    /** A JSON array of objects, or objects one after another. */
    JSON("json"),
    /** One JSON object per line (JSON lines, newline-delimited JSON). */
    JSON_LINES("jsonl"),
    /** Apache Parquet. */
    PARQUET("parquet"),
    /** Anything else: recorded, not read. */
    DOCUMENT("document");

    private final String id;

    FileFormat(String id) {
        this.id = id;
    }

    /** The name stored in an asset's {@code format} hint. */
    public String id() {
        return id;
    }

    /** Whether the file is read as rows. */
    public boolean readable() {
        return this != DOCUMENT;
    }

    /** The format of a file from its name, for example {@code orders.ndjson}. */
    public static FileFormat of(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        int slash = name.lastIndexOf('/');
        name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return DOCUMENT;
        }
        return switch (name.substring(dot + 1)) {
            case "csv" -> CSV;
            case "tsv", "tab" -> TSV;
            case "json" -> JSON;
            case "jsonl", "ndjson" -> JSON_LINES;
            case "parquet", "parq" -> PARQUET;
            default -> DOCUMENT;
        };
    }

    /** The format named by a {@code format} hint, for example {@code jsonl}. */
    public static FileFormat byId(String id) {
        for (FileFormat f : values()) {
            if (f.id.equals(id)) {
                return f;
            }
        }
        throw new IllegalArgumentException("Unknown file format '" + id + "'");
    }
}

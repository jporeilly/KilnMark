package io.kilnmark.server.api;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import io.kilnmark.graph.store.BrowseStore;

/** What the browse, diagram and relationship endpoints return. */
public final class BrowseViews {

    private BrowseViews() {
    }

    public record Source(String name, String label, String kind, int containers, int assets) {
        static Source of(BrowseStore.SourceNode s) {
            return new Source(s.name(), s.label(), s.kind(), s.containers(), s.assets());
        }
    }

    public record Container(String name, int assets, Map<String, Integer> kinds) {
        static Container of(BrowseStore.ContainerNode c) {
            return new Container(c.name(), c.assets(), c.kinds());
        }
    }

    /** {@code format}: what to show it as (table, view, csv, parquet, pdf, word, excel, email, ...). */
    public record Asset(String urn, String name, String kind, String format, String container, Long rowCount,
                        int fields, String description) {
        static Asset of(BrowseStore.AssetNode a) {
            return new Asset(a.urn(), a.name(), a.kind(), BrowseViews.format(a.kind(), a.name()), a.container(), a.rowCount(),
                a.fields(), a.description());
        }
    }

    public record Page<T>(List<T> items, int total, int offset, int limit) {
    }

    public record End(String urn, String source, String container, String name, String kind, List<String> fields) {
    }

    /**
     * A relationship. {@code kind}: declared or inferred; {@code status}: proposed, accepted or
     * rejected; {@code confidence} and {@code evidence} for inferred ones.
     */
    public record Relationship(long id, End from, End to, String kind, String status, String name, Double confidence,
                               JsonNode evidence, String decidedBy, Instant decidedAt, Instant foundAt,
                               Instant refreshedAt) {
    }

    public record Column(String name, String sourceType, String kind, boolean nullable, boolean primaryKey,
                         boolean foreignKey) {
    }

    /** A box in a diagram. {@code external}: outside the container asked for, shown because it is related. */
    public record Table(String urn, String source, String container, String name, String kind, String format,
                        Long rowCount, List<String> primaryKey, boolean external, List<Column> columns) {
    }

    /**
     * A diagram: tables with their columns and the relationships between them (rejected ones
     * included, for the list; diagrams hide them). {@code total} is how many tables the request
     * matched; {@code truncated} when more matched than {@code limit} allows.
     */
    public record Diagram(List<Table> tables, List<Relationship> relationships, int total, int limit,
                          boolean truncated) {
    }

    /** What to show an asset as, from its kind and, for files, the end of its name. */
    static String format(String kind, String name) {
        String k = kind == null ? "" : kind.toLowerCase(Locale.ROOT);
        if (k.contains("view")) {
            return "view";
        }
        if (k.contains("table")) {
            return "table";
        }
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("\\.(gz|zst|bz2)$", "");
        int dot = n.lastIndexOf('.');
        String ext = dot < 0 ? "" : n.substring(dot + 1);
        return switch (ext) {
            case "csv" -> "csv";
            case "tsv", "tab" -> "tsv";
            case "json", "jsonl", "ndjson" -> "json";
            case "parquet" -> "parquet";
            case "pdf" -> "pdf";
            case "doc", "docx", "odt", "rtf" -> "word";
            case "xls", "xlsx", "xlsm", "ods" -> "excel";
            case "eml", "msg", "mbox" -> "email";
            case "txt", "md", "log" -> "text";
            default -> k.equals("file") ? "file" : "document";
        };
    }
}

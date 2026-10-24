package io.kilnmark.server.quality;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One profile version of an asset, as analysis reads it: when it was observed, its row count, and
 * each field's kind and statistics. Statistics are the profile's JSON, in which numbers may be
 * written as text; the accessors here read either.
 */
public record ProfileFacts(long versionId, String assetUrn, Instant observedAt, Long rowCount,
                           Boolean rowCountComplete, boolean estimate, List<String> primaryKey,
                           List<FieldFacts> fields) {

    public ProfileFacts {
        primaryKey = List.copyOf(primaryKey == null ? List.of() : primaryKey);
        fields = List.copyOf(fields == null ? List.of() : fields);
    }

    /** One field with its statistics (null when it was not profiled) and its accepted value shapes. */
    public record FieldFacts(String name, int position, String kind, String sourceType, boolean nullable,
                             JsonNode stats, Set<String> acceptedShapes) {
        public FieldFacts {
            acceptedShapes = Set.copyOf(acceptedShapes == null ? Set.of() : acceptedShapes);
        }

        public boolean numeric() {
            return Set.of("int16", "int32", "int64", "float32", "float64", "decimal").contains(kind);
        }

        public boolean temporal() {
            return Set.of("date", "timestamp", "timestamp_tz").contains(kind);
        }

        public boolean text() {
            return "text".equals(kind);
        }

        public long rows() {
            return count(stats, "rows");
        }

        public long nulls() {
            return count(stats, "nulls");
        }

        public long values() {
            return Math.max(0, rows() - nulls());
        }

        public long get(String key) {
            return count(stats, key);
        }

        public double number(String key) {
            return ProfileFacts.number(stats, key);
        }

        public String text(String key) {
            return stats == null || !stats.hasNonNull(key) ? null : stats.get(key).asText();
        }

        public boolean has(String key) {
            return stats != null && stats.hasNonNull(key);
        }

        /** The value shapes, most frequent first. */
        public List<Counted> patterns() {
            return counted(stats, "patterns", "shape");
        }

        /** Detected formats with their counts. */
        public Map<String, Long> formats() {
            Map<String, Long> out = new LinkedHashMap<>();
            counted(stats, "formats", "format").forEach(c -> out.put(c.item(), c.count()));
            return out;
        }

        /** The most common values (absent when the source keeps no values). */
        public List<Counted> top() {
            return counted(stats, "top", "value");
        }

        /** Quantiles every 5% (21 numbers), or null. */
        public double[] quantiles() {
            if (stats == null || !stats.path("quantiles").isArray()) {
                return null;
            }
            JsonNode q = stats.get("quantiles");
            double[] out = new double[q.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = q.get(i).asDouble();
            }
            return out;
        }

        /** The latest value of a date or timestamp field, or null. */
        public Instant latest() {
            return instant(text("max"));
        }
    }

    /** An item with its count: a value shape, a format or a value. */
    public record Counted(String item, long count) {
    }

    public FieldFacts field(String name) {
        for (FieldFacts f : fields) {
            if (f.name().equals(name)) {
                return f;
            }
        }
        return null;
    }

    static long count(JsonNode stats, String key) {
        if (stats == null || !stats.hasNonNull(key)) {
            return -1;
        }
        JsonNode v = stats.get(key);
        if (v.isNumber()) {
            return v.asLong();
        }
        try {
            return Long.parseLong(v.asText().trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static double number(JsonNode stats, String key) {
        if (stats == null || !stats.hasNonNull(key)) {
            return Double.NaN;
        }
        JsonNode v = stats.get(key);
        if (v.isNumber()) {
            return v.asDouble();
        }
        try {
            return Double.parseDouble(v.asText().trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    static List<Counted> counted(JsonNode stats, String key, String item) {
        List<Counted> out = new ArrayList<>();
        if (stats == null || !stats.path(key).isArray()) {
            return out;
        }
        for (JsonNode n : stats.get(key)) {
            out.add(new Counted(n.path(item).asText(), n.path("count").asLong()));
        }
        return out;
    }

    /** A date ({@code 2024-03-01}, taken as its start in UTC) or an instant, or null. */
    static Instant instant(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return text.length() == 10 ? LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant()
                : Instant.parse(text);
        } catch (DateTimeParseException e) {
            try {
                return java.time.LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }
}

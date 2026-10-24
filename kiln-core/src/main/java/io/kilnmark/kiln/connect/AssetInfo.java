package io.kilnmark.kiln.connect;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One asset a connector found: a table, view or file, with its fields.
 *
 * @param path        unique within the source, for example {@code crm.customers} or
 *                    {@code raw/2025/orders.csv}; becomes the URN path
 * @param kind        what it is: table, view, file, ...
 * @param container   where it sits, shown in breadcrumbs: a schema, a folder or a bucket
 * @param name        its own name
 * @param description from the source (a table comment), or null
 * @param fields      its fields in order; may be empty when only reading reveals them
 * @param primaryKey  declared key fields, if any
 * @param bounded     true for things that may be expensive to read in full (views): the scan
 *                    then reads a sample
 * @param readable    false for assets recorded but not read, such as images or files that could
 *                    not be inspected; the scan stores them without a reader or profile. A
 *                    readable asset of kind {@code document} is read as text: its reader puts the
 *                    text's chunks on its output and reports a {@code DOCUMENT} event
 * @param hints       connector-specific details the reader needs (format, size, ...)
 */
public record AssetInfo(String path, String kind, String container, String name, String description,
                        List<FieldInfo> fields, List<String> primaryKey, boolean bounded, boolean readable,
                        Map<String, String> hints) {

    public AssetInfo {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(kind, "kind");
        fields = List.copyOf(fields == null ? List.of() : fields);
        primaryKey = List.copyOf(primaryKey == null ? List.of() : primaryKey);
        hints = Map.copyOf(hints == null ? Map.of() : hints);
    }

    /**
     * One field of an asset.
     *
     * @param kind the engine's view of its type: boolean, int16, int32, int64, float32, float64,
     *             decimal, text, binary, date, time, timestamp, timestamp_tz
     */
    public record FieldInfo(String name, int position, String sourceType, String kind, boolean nullable,
                            String description) {
    }
}

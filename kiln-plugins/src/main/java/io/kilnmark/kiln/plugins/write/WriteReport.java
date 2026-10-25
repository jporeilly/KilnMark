package io.kilnmark.kiln.plugins.write;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.arrow.vector.VectorSchemaRoot;

import io.kilnmark.kiln.MetadataEmitter;
import io.kilnmark.kiln.MetadataEvent;
import io.kilnmark.kiln.StepException;
import io.kilnmark.kiln.plugins.profile.ProfileStep;

/**
 * What a writer reports about the asset it wrote, so the server can register it with its profile
 * (docs/design/pipelines.md, section 5): the batches are profiled in the same pass as they are
 * written, by {@link ProfileStep}'s own logic, and nothing is reported until the write has
 * succeeded (committed, or the file is in place).
 *
 * <p>Events, all for {@code WriteOptions.assetUrn} (docs/design/pipelines.md, section 5, "What a
 * write reports"):
 *
 * <ul>
 *   <li>When the write leaves the asset holding exactly what was written (a table created or
 *       replaced, a file): {@code SCHEMA} (attributes {@code columns}, a JSON array of
 *       {@code {"name", "kind", "type"}}, plus the writer's details such as {@code target},
 *       {@code mode} or {@code format}), {@code ROW_COUNT} ({@code rows}, {@code complete=true},
 *       {@code written=true}), then the profile's {@code PROFILE} events and, with values kept, its
 *       {@code SAMPLE} event, as a scan reports them.</li>
 *   <li>When rows were added to or merged into an existing table (append, upsert): only
 *       {@code ROW_COUNT} with {@code rows} (the rows written), {@code complete=false},
 *       {@code written=true}, {@code appended=true} and the {@code mode}. The rows written are not
 *       the table, so no schema or profile is reported for it, and no profile is computed.</li>
 * </ul>
 */
public final class WriteReport {

    private final String assetUrn;
    private final boolean wholeAsset;
    private final ProfileStep profile;
    private long rows;

    /**
     * @param assetUrn the written asset's URN
     * @param settings   the write step's settings: {@value WriteSettings#PROFILE} and
     *                   {@value WriteSettings#KEEP_VALUES} are read
     * @param wholeAsset whether the asset will hold exactly what is written (false for append and upsert)
     */
    public WriteReport(String stepName, String assetUrn, WriteSettings settings, boolean wholeAsset)
        throws StepException, io.kilnmark.kiln.connect.ConnectorException {
        this.assetUrn = assetUrn;
        this.wholeAsset = wholeAsset;
        if (assetUrn == null || !assetUrn.startsWith("urn:kilnmark:")) {
            throw new StepException("Step " + stepName + " needs the URN of the asset it writes (urn:kilnmark:...)");
        }
        this.profile = wholeAsset && settings.bool(WriteSettings.PROFILE, true)
            ? ProfileStep.tap(stepName, Map.of("asset-urn", assetUrn,
                "keep-values", Boolean.toString(settings.bool(WriteSettings.KEEP_VALUES, true))))
            : null;
    }

    /** Counts and profiles a batch being written; the caller keeps the batch. */
    public void accept(VectorSchemaRoot batch) {
        rows += batch.getRowCount();
        if (profile != null) {
            profile.accept(batch);
        }
    }

    /** Rows written so far. */
    public long rows() {
        return rows;
    }

    /**
     * Reports the written asset; call once the write has succeeded.
     *
     * @param columns the written columns with the type each was written as (a database type, or a
     *                Parquet or CSV type), in order
     * @param details further attributes of the {@code SCHEMA} event
     */
    public void emit(MetadataEmitter metadata, List<WriteColumn> columns, List<String> types,
                     Map<String, String> details) {
        Instant now = Instant.now();
        if (!wholeAsset) {
            Map<String, String> count = new LinkedHashMap<>();
            count.put("rows", Long.toString(rows));
            count.put("complete", "false");
            count.put("written", "true");
            count.put("appended", "true");
            if (details.containsKey("mode")) {
                count.put("mode", details.get("mode"));
            }
            metadata.emit(new MetadataEvent(assetUrn, MetadataEvent.Kind.ROW_COUNT, count, now));
            return;
        }
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < columns.size(); i++) {
            WriteColumn c = columns.get(i);
            json.append(i == 0 ? "" : ",").append("{\"name\":").append(jsonString(c.name()))
                .append(",\"kind\":").append(jsonString(c.kindName()))
                .append(",\"type\":").append(jsonString(i < types.size() ? types.get(i) : c.kindName())).append('}');
        }
        Map<String, String> schema = new LinkedHashMap<>(details);
        schema.put("columns", json.append(']').toString());
        metadata.emit(new MetadataEvent(assetUrn, MetadataEvent.Kind.SCHEMA, schema, now));
        metadata.emit(new MetadataEvent(assetUrn, MetadataEvent.Kind.ROW_COUNT,
            Map.of("rows", Long.toString(rows), "complete", "true", "written", "true"), now));
        if (profile != null) {
            try {
                profile.report(metadata);
            } catch (RuntimeException e) {
                // the data is written; a profile that cannot be computed is left out rather than failing the run
            }
        }
    }

    static String jsonString(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }
}

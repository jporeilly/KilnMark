package io.kilnmark.kiln.plugins.write;

import java.time.Instant;
import java.util.HashMap;
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
 * (docs/design/pipelines.md, section 5, "What a write reports"). The writer owns the profile of
 * what it writes: batches are profiled in the same pass by {@link ProfileStep}'s own logic, with
 * the source's profiling settings ({@code WriteOptions.profiling()}), and nothing is reported until
 * the write has succeeded (committed, or the file is in place).
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
 *
 * <p>Profiling never fails a write. If it fails (bad profiling settings, or an error profiling a
 * batch or computing the profile), profiling stops, the write goes on, and the {@code ROW_COUNT}
 * event says {@code profiled=false} with {@code profile_error}, the reason, which the server can
 * show as a note; no {@code PROFILE} or {@code SAMPLE} events follow.
 */
public final class WriteReport {

    /** {@code ROW_COUNT} attribute: {@code false} when what was written could not be profiled. */
    public static final String PROFILED = "profiled";

    /** {@code ROW_COUNT} attribute, with {@link #PROFILED} {@code false}: why, in words a person can read. */
    public static final String PROFILE_ERROR = "profile_error";

    private final String assetUrn;
    private final boolean wholeAsset;
    private ProfileStep profile;
    private String profileError;
    private long rows;

    /**
     * @param assetUrn   the written asset's URN
     * @param settings   the write step's settings: {@value WriteSettings#PROFILE} and
     *                   {@value WriteSettings#KEEP_VALUES} are read
     * @param profiling  the source's profiling settings ({@code WriteOptions.profiling()}): the
     *                   profile step's settings, as a scan of the source would give them
     * @param wholeAsset whether the asset will hold exactly what is written (false for append and upsert)
     * @throws StepException without a valid URN
     */
    public WriteReport(String stepName, String assetUrn, WriteSettings settings, Map<String, String> profiling,
                       boolean wholeAsset) throws StepException, io.kilnmark.kiln.connect.ConnectorException {
        this.assetUrn = assetUrn;
        this.wholeAsset = wholeAsset;
        if (assetUrn == null || !assetUrn.startsWith("urn:kilnmark:")) {
            throw new StepException("Step " + stepName + " needs the URN of the asset it writes (urn:kilnmark:...)");
        }
        boolean wanted = settings.bool(WriteSettings.PROFILE, true);
        boolean keepValues = settings.bool(WriteSettings.KEEP_VALUES, true);
        if (wholeAsset && wanted) {
            Map<String, String> p = new HashMap<>(profiling == null ? Map.of() : profiling);
            p.put("asset-urn", assetUrn);
            // the source's rule wins: the step can keep fewer values than the source allows, never more
            boolean sourceKeeps = !"false".equalsIgnoreCase(p.getOrDefault("keep-values", "true"));
            p.put("keep-values", Boolean.toString(sourceKeeps && keepValues));
            try {
                profile = ProfileStep.tap(stepName, p);
            } catch (StepException | RuntimeException e) {
                stopProfiling(e);
            }
        }
    }

    private void stopProfiling(Throwable e) {
        profile = null;
        String m = e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
        profileError = "Profiling what was written stopped: " + m;
    }

    /** Counts and profiles a batch being written; the caller keeps the batch. Never fails. */
    public void accept(VectorSchemaRoot batch) {
        rows += batch.getRowCount();
        if (profile != null) {
            try {
                profile.accept(batch);
            } catch (RuntimeException | StackOverflowError e) {
                stopProfiling(e);
            }
        }
    }

    /** Rows written so far. */
    public long rows() {
        return rows;
    }

    /** Why profiling stopped, or null while it works. */
    public String profileError() {
        return profileError;
    }

    /**
     * Reports the written asset; call once the write has succeeded. Never fails because of profiling.
     *
     * @param columns the written columns with the type each was written as (a database type, or a
     *                Parquet or CSV type), in order
     * @param details further attributes of the {@code SCHEMA} event
     */
    public void emit(MetadataEmitter metadata, List<WriteColumn> columns, List<String> types,
                     Map<String, String> details) {
        emit(metadata, columns, List.of(), types, details);
    }

    /**
     * As {@link #emit(MetadataEmitter, List, List, Map)}, with the kind each column is recorded as
     * when a scan of the written asset would see another than the input's (a CSV file, whose kinds
     * are inferred again from its text). Empty: the input's kinds.
     */
    public void emit(MetadataEmitter metadata, List<WriteColumn> columns, List<String> kinds, List<String> types,
                     Map<String, String> details) {
        Instant now = Instant.now();
        Map<String, String> count = new LinkedHashMap<>();
        count.put("rows", Long.toString(rows));
        count.put("written", "true");
        if (!wholeAsset) {
            count.put("complete", "false");
            count.put("appended", "true");
            if (details.containsKey("mode")) {
                count.put("mode", details.get("mode"));
            }
            metadata.emit(new MetadataEvent(assetUrn, MetadataEvent.Kind.ROW_COUNT, count, now));
            return;
        }
        count.put("complete", "true");
        List<MetadataEvent> profileEvents = List.of();
        if (profile != null) {
            try {
                profileEvents = profile.events();
            } catch (RuntimeException e) {
                stopProfiling(e);
            }
        }
        if (profileError != null) {
            count.put(PROFILED, "false");
            count.put(PROFILE_ERROR, profileError);
        }
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < columns.size(); i++) {
            WriteColumn c = columns.get(i);
            json.append(i == 0 ? "" : ",").append("{\"name\":").append(jsonString(c.name()))
                .append(",\"kind\":").append(jsonString(i < kinds.size() ? kinds.get(i) : c.kindName()))
                .append(",\"type\":").append(jsonString(i < types.size() ? types.get(i) : c.kindName())).append('}');
        }
        Map<String, String> schema = new LinkedHashMap<>(details);
        schema.put("columns", json.append(']').toString());
        metadata.emit(new MetadataEvent(assetUrn, MetadataEvent.Kind.SCHEMA, schema, now));
        metadata.emit(new MetadataEvent(assetUrn, MetadataEvent.Kind.ROW_COUNT, count, now));
        profileEvents.forEach(metadata::emit);
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

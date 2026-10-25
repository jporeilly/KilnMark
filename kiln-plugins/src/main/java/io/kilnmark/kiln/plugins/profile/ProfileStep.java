package io.kilnmark.kiln.plugins.profile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.Decimal256Vector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.LargeVarCharVector;
import org.apache.arrow.vector.SmallIntVector;
import org.apache.arrow.vector.TimeMicroVector;
import org.apache.arrow.vector.TimeStampVector;
import org.apache.arrow.vector.TinyIntVector;
import org.apache.arrow.vector.UInt1Vector;
import org.apache.arrow.vector.UInt2Vector;
import org.apache.arrow.vector.UInt4Vector;
import org.apache.arrow.vector.UInt8Vector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;

import io.kilnmark.kiln.MetadataEmitter;
import io.kilnmark.kiln.MetadataEvent;
import io.kilnmark.kiln.Step;
import io.kilnmark.kiln.StepContext;
import io.kilnmark.kiln.StepException;

/**
 * Profiles every column of the batches passing through, and reports one {@code PROFILE} event per
 * column when the input ends. Batches go on to the next step if there is one.
 *
 * <p>Settings: {@code asset-urn}, the asset the profile is about. For data quality, optionally:
 * {@code keep-values} ({@code true} by default; {@code false} keeps no values at all, only shapes
 * and statistics), {@code examples} (example values per value shape, default 5),
 * {@code sample-size} (rows to sample, default 0: none), {@code sample-mode} ({@code key},
 * {@code row} or {@code reservoir}; see {@link RowSampler}), {@code sample-key} (key fields,
 * comma-separated), {@code sample-seed} (for {@code reservoir}) and {@code sample-max-bytes} (default 8 MiB). With values kept, it also
 * reports one {@code SAMPLE} event: attributes {@code method}, {@code key}, {@code seen},
 * {@code columns} and {@code rows} (JSON), and {@code examples} (JSON: per field, per shape).
 */
public final class ProfileStep implements Step {

    public static final String TYPE = "profile";

    /**
     * Attribute of a {@code PROFILE} event holding the column's distinct-value sketch: base64 of a
     * compact HyperLogLog (lgK 14, 4 bits per bucket, at most about 8 KiB). Kept apart from the
     * profile, it lets relationship discovery estimate how far one column's values lie inside
     * another's ({@code |A ∪ B|} against {@code |B|}) without reading either again. Only integer,
     * number and text columns with values have one, the types keys have.
     */
    public static final String SKETCH = "hll";

    /**
     * Setting naming the fields whose sketch to report: each name URL-encoded (UTF-8), joined with
     * commas. Without it no sketches are reported. See {@link #sketchFields(java.util.Collection)}.
     */
    public static final String SKETCH_FIELDS = "sketch-fields";

    /** {@code fields} as the value of {@link #SKETCH_FIELDS}. */
    public static String sketchFields(java.util.Collection<String> fields) {
        return String.join(",", fields.stream()
            .map(f -> java.net.URLEncoder.encode(f, java.nio.charset.StandardCharsets.UTF_8)).toList());
    }

    private String assetUrn;
    private java.util.Set<String> sketched = java.util.Set.of();
    private List<ColumnStats> stats;
    private boolean keepValues = true;
    private int examples = PatternProfile.DEFAULT_EXAMPLES;
    private RowSampler sampler;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void init(StepContext context) throws StepException {
        configure(context.stepName(), context.settings());
    }

    /**
     * A profiler for a step that handles its batches itself, such as a writer: it profiles what it
     * is given with {@link #accept} in the same pass and reports with {@link #report(MetadataEmitter)},
     * exactly as this step would. {@code settings} are this step's settings ({@code asset-urn},
     * {@code keep-values} and so on).
     *
     * @throws StepException if a setting cannot be read
     */
    public static ProfileStep tap(String stepName, Map<String, String> settings) throws StepException {
        ProfileStep p = new ProfileStep();
        p.configure(stepName, settings);
        return p;
    }

    private void configure(String stepName, Map<String, String> settings) throws StepException {
        assetUrn = settings.get("asset-urn");
        if (assetUrn == null || assetUrn.isBlank()) {
            throw new StepException("Step " + stepName + " needs the setting 'asset-urn'");
        }
        String fields = setting(settings, SKETCH_FIELDS, "");
        if (!fields.isEmpty()) {
            sketched = java.util.Arrays.stream(fields.split(","))
                .map(f -> java.net.URLDecoder.decode(f, java.nio.charset.StandardCharsets.UTF_8))
                .collect(java.util.stream.Collectors.toSet());
        }
        keepValues = Boolean.parseBoolean(setting(settings, "keep-values", "true"));
        try {
            examples = Math.clamp(Integer.parseInt(setting(settings, "examples", "5")), 0, 50);
            int size = Math.clamp(Integer.parseInt(setting(settings, "sample-size", "0")), 0, 100_000);
            if (keepValues && size > 0) {
                List<String> key = java.util.Arrays.stream(setting(settings, "sample-key", "").split(","))
                    .map(String::trim).filter(k -> !k.isEmpty()).toList();
                sampler = new RowSampler(RowSampler.Mode.parse(setting(settings, "sample-mode", "key")), key, size,
                    Long.parseLong(setting(settings, "sample-seed", "0")),
                    Long.parseLong(setting(settings, "sample-max-bytes", Long.toString(RowSampler.DEFAULT_MAX_BYTES))));
            }
        } catch (IllegalArgumentException e) {
            throw new StepException("Step " + stepName + " has a sample setting it cannot read: "
                + e.getMessage(), e);
        }
    }

    /** An optional setting, as {@link StepContext#setting(String, String)} reads it. */
    private static String setting(Map<String, String> settings, String key, String fallback) {
        String value = settings.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Override
    public boolean process(StepContext context) throws StepException {
        VectorSchemaRoot batch;
        try {
            batch = context.input().take(context.allocator());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StepException("Interrupted", e);
        }
        if (batch == null) {
            report(context);
            return false;
        }
        try {
            accept(batch);
        } catch (RuntimeException e) {
            batch.close();
            throw new StepException("Profiling failed: " + e.getMessage(), e);
        }
        if (context.hasOutput()) {
            try {
                context.output().put(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StepException("Interrupted", e);
            }
        } else {
            batch.close();
        }
        return true;
    }

    /** Profiles one batch; the caller keeps the batch and closes it. */
    public void accept(VectorSchemaRoot batch) {
        if (stats == null) {
            stats = new ArrayList<>();
            for (Field f : batch.getSchema().getFields()) {
                ColumnStats cs = new ColumnStats(f.getName(), shape(f.getType()), keepValues, examples);
                cs.keepSketch = sketched.contains(f.getName());
                stats.add(cs);
            }
        }
        if (sampler != null) {
            sampler.accept(batch);
        }
        int n = batch.getRowCount();
        for (int c = 0; c < stats.size(); c++) {
            ColumnStats s = stats.get(c);
            FieldVector v = batch.getVector(c);
            for (int i = 0; i < n; i++) {
                if (v.isNull(i)) {
                    s.addNull();
                    continue;
                }
                switch (v) {
                    case BigIntVector x -> s.addLong(x.get(i));
                    case IntVector x -> s.addLong(x.get(i));
                    case SmallIntVector x -> s.addLong(x.get(i));
                    case TinyIntVector x -> s.addLong(x.get(i));
                    case UInt1Vector x -> s.addLong(Byte.toUnsignedInt(x.get(i)));
                    case UInt2Vector x -> s.addLong(x.get(i));
                    case UInt4Vector x -> s.addLong(Integer.toUnsignedLong(x.get(i)));
                    case UInt8Vector x -> s.addDouble(Double.parseDouble(Long.toUnsignedString(x.get(i))));
                    case Float8Vector x -> s.addDouble(x.get(i));
                    case Float4Vector x -> s.addDouble(x.get(i));
                    case DecimalVector x -> s.addDouble(x.getObject(i).doubleValue());
                    case Decimal256Vector x -> s.addDouble(x.getObject(i).doubleValue());
                    case LargeVarCharVector x -> s.addText(x.get(i));
                    case BitVector x -> s.addBoolean(x.get(i) == 1);
                    case VarCharVector x -> s.addText(x.get(i));
                    case VarBinaryVector x -> s.addBinary(x.get(i));
                    case DateDayVector x -> s.addLong(x.get(i));
                    case TimeMicroVector x -> s.addLong(x.get(i));
                    case TimeStampVector x -> s.addLong(x.get(i));
                    default -> s.addBinary(new byte[0]);
                }
            }
        }
    }

    private void report(StepContext context) {
        report(context.metadata());
    }

    /**
     * Reports one {@code PROFILE} event per column and, with values kept, the {@code SAMPLE} event,
     * for what {@link #accept} was given. Nothing when it was given no batch.
     */
    public void report(MetadataEmitter metadata) {
        if (stats == null) {
            return;
        }
        Instant now = Instant.now();
        for (ColumnStats s : stats) {
            metadata.emit(new MetadataEvent(assetUrn, MetadataEvent.Kind.PROFILE, s.attributes(), now));
        }
        if (keepValues) {
            metadata.emit(new MetadataEvent(assetUrn, MetadataEvent.Kind.SAMPLE, sample(), now));
        }
    }

    /** The {@code SAMPLE} event's attributes: the sampled rows, and example values per shape. */
    Map<String, String> sample() {
        Map<String, String> a = new java.util.LinkedHashMap<>();
        StringBuilder ex = new StringBuilder("{");
        int n = 0;
        for (ColumnStats s : stats) {
            String e = s.patterns == null ? null : s.patterns.examplesJson();
            if (e != null && !e.equals("{}")) {
                ex.append(n++ == 0 ? "" : ",").append(Json.string(s.field)).append(':').append(e);
            }
        }
        a.put("examples", ex.append('}').toString());
        if (sampler != null) {
            a.put("method", sampler.mode() == RowSampler.Mode.KEY && sampler.keyFields().isEmpty() ? "row"
                : sampler.mode().id());
            a.put("key", String.join(",", sampler.keyFields()));
            a.put("seen", Long.toString(sampler.seen()));
            StringBuilder c = new StringBuilder("[");
            int i = 0;
            for (String col : sampler.columns()) {
                c.append(i++ == 0 ? "" : ",").append(Json.string(col));
            }
            a.put("columns", c.append(']').toString());
            a.put("rows", sampler.rowsJson());
        }
        return a;
    }

    static ColumnStats.Shape shape(ArrowType t) {
        return switch (t.getTypeID()) {
            case Int -> ColumnStats.Shape.INTEGER;
            case FloatingPoint, Decimal -> ColumnStats.Shape.NUMBER;
            case Bool -> ColumnStats.Shape.BOOLEAN;
            case Utf8, LargeUtf8 -> ColumnStats.Shape.TEXT;
            case Date -> ColumnStats.Shape.DATE;
            case Time -> ColumnStats.Shape.TIME;
            case Timestamp -> ColumnStats.Shape.TIMESTAMP;
            default -> ColumnStats.Shape.BINARY;
        };
    }
}

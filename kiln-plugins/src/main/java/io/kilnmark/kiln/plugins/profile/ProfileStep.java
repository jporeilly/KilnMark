package io.kilnmark.kiln.plugins.profile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.SmallIntVector;
import org.apache.arrow.vector.TimeMicroVector;
import org.apache.arrow.vector.TimeStampVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;

import io.kilnmark.kiln.MetadataEvent;
import io.kilnmark.kiln.Step;
import io.kilnmark.kiln.StepContext;
import io.kilnmark.kiln.StepException;

/**
 * Profiles every column of the batches passing through, and reports one {@code PROFILE} event per
 * column when the input ends. Batches go on to the next step if there is one.
 *
 * <p>Settings: {@code asset-urn}, the asset the profile is about.
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

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void init(StepContext context) throws StepException {
        assetUrn = context.setting("asset-urn");
        String fields = context.setting(SKETCH_FIELDS, "");
        if (fields != null && !fields.isEmpty()) {
            sketched = java.util.Arrays.stream(fields.split(","))
                .map(f -> java.net.URLDecoder.decode(f, java.nio.charset.StandardCharsets.UTF_8))
                .collect(java.util.stream.Collectors.toSet());
        }
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

    void accept(VectorSchemaRoot batch) {
        if (stats == null) {
            stats = new ArrayList<>();
            for (Field f : batch.getSchema().getFields()) {
                ColumnStats cs = new ColumnStats(f.getName(), shape(f.getType()));
                cs.keepSketch = sketched.contains(f.getName());
                stats.add(cs);
            }
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
                    case Float8Vector x -> s.addDouble(x.get(i));
                    case Float4Vector x -> s.addDouble(x.get(i));
                    case DecimalVector x -> s.addDouble(x.getObject(i).doubleValue());
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
        if (stats == null) {
            return;
        }
        Instant now = Instant.now();
        for (ColumnStats s : stats) {
            context.metadata().emit(new MetadataEvent(assetUrn, MetadataEvent.Kind.PROFILE, s.attributes(), now));
        }
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

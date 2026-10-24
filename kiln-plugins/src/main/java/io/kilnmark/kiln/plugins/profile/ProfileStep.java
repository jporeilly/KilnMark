package io.kilnmark.kiln.plugins.profile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 * <p>Settings: {@code asset-urn}, the asset the profile is about. For data quality, optionally:
 * {@code keep-values} ({@code true} by default; {@code false} keeps no values at all, only shapes
 * and statistics), {@code examples} (example values per value shape, default 5),
 * {@code sample-size} (rows to sample, default 0: none), {@code sample-mode} ({@code key},
 * {@code row} or {@code reservoir}; see {@link RowSampler}), {@code sample-key} (key fields,
 * comma-separated) and {@code sample-seed} (for {@code reservoir}). With values kept, it also
 * reports one {@code SAMPLE} event: attributes {@code method}, {@code key}, {@code seen},
 * {@code columns} and {@code rows} (JSON), and {@code examples} (JSON: per field, per shape).
 */
public final class ProfileStep implements Step {

    public static final String TYPE = "profile";

    private String assetUrn;
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
        assetUrn = context.setting("asset-urn");
        keepValues = Boolean.parseBoolean(context.setting("keep-values", "true"));
        try {
            examples = Math.clamp(Integer.parseInt(context.setting("examples", "5")), 0, 50);
            int size = Math.clamp(Integer.parseInt(context.setting("sample-size", "0")), 0, 100_000);
            if (keepValues && size > 0) {
                List<String> key = java.util.Arrays.stream(context.setting("sample-key", "").split(","))
                    .map(String::trim).filter(k -> !k.isEmpty()).toList();
                sampler = new RowSampler(RowSampler.Mode.parse(context.setting("sample-mode", "key")), key, size,
                    Long.parseLong(context.setting("sample-seed", "0")));
            }
        } catch (IllegalArgumentException e) {
            throw new StepException("Step " + context.stepName() + " has a sample setting it cannot read: "
                + e.getMessage(), e);
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
                stats.add(new ColumnStats(f.getName(), shape(f.getType()), keepValues, examples));
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
        if (keepValues) {
            context.metadata().emit(new MetadataEvent(assetUrn, MetadataEvent.Kind.SAMPLE, sample(), now));
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

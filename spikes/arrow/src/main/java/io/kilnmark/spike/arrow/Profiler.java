package io.kilnmark.spike.arrow;

import java.math.BigDecimal;
import java.util.List;

import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.TimeStampVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;

/** The profiling step: one {@link ColumnProfile} per column, fed from Arrow batches or row objects. */
final class Profiler {

    private ColumnProfile[] columns;

    Profiler() {
    }

    Profiler(List<String> names, List<ColumnProfile.Kind> kinds) {
        columns = new ColumnProfile[names.size()];
        for (int i = 0; i < columns.length; i++) {
            columns[i] = new ColumnProfile(names.get(i), kinds.get(i));
        }
    }

    static Profiler forSchema(Schema schema) {
        Profiler p = new Profiler();
        p.init(schema);
        return p;
    }

    private void init(Schema schema) {
        List<Field> fields = schema.getFields();
        columns = new ColumnProfile[fields.size()];
        for (int i = 0; i < columns.length; i++) {
            columns[i] = new ColumnProfile(fields.get(i).getName(), kind(fields.get(i).getType()));
        }
    }

    static ColumnProfile.Kind kind(ArrowType t) {
        return switch (t.getTypeID()) {
            case Int, FloatingPoint, Decimal -> ColumnProfile.Kind.NUMBER;
            case Utf8, LargeUtf8 -> ColumnProfile.Kind.TEXT;
            case Timestamp, Date, Time -> ColumnProfile.Kind.TIME;
            default -> ColumnProfile.Kind.OTHER;
        };
    }

    void accept(VectorSchemaRoot batch) {
        if (columns == null) {
            init(batch.getSchema());
        }
        int n = batch.getRowCount();
        for (int c = 0; c < columns.length; c++) {
            FieldVector v = batch.getVector(c);
            ColumnProfile p = columns[c];
            switch (v) {
                case BigIntVector x -> {
                    for (int i = 0; i < n; i++) {
                        if (x.isNull(i)) p.addNull(); else p.addLong(x.get(i));
                    }
                }
                case IntVector x -> {
                    for (int i = 0; i < n; i++) {
                        if (x.isNull(i)) p.addNull(); else p.addLong(x.get(i));
                    }
                }
                case Float8Vector x -> {
                    for (int i = 0; i < n; i++) {
                        if (x.isNull(i)) p.addNull(); else p.addDouble(x.get(i));
                    }
                }
                case DecimalVector x -> {
                    for (int i = 0; i < n; i++) {
                        if (x.isNull(i)) p.addNull(); else p.addDouble(x.getObject(i).doubleValue());
                    }
                }
                case VarCharVector x -> {
                    for (int i = 0; i < n; i++) {
                        if (x.isNull(i)) p.addNull(); else p.addText(x.get(i));
                    }
                }
                case TimeStampVector x -> {
                    for (int i = 0; i < n; i++) {
                        if (x.isNull(i)) p.addNull(); else p.addLong(x.get(i));
                    }
                }
                default -> {
                    for (int i = 0; i < n; i++) {
                        if (v.isNull(i)) p.addNull(); else p.count++;
                    }
                }
            }
        }
    }

    void accept(Object[][] rows) {
        for (Object[] row : rows) {
            for (int c = 0; c < columns.length; c++) {
                ColumnProfile p = columns[c];
                switch (row[c]) {
                    case null -> p.addNull();
                    case Long x -> p.addLong(x);
                    case Integer x -> p.addLong(x);
                    case Double x -> p.addDouble(x);
                    case BigDecimal x -> p.addDouble(x.doubleValue());
                    case String x -> p.addText(x);
                    default -> p.count++;
                }
            }
        }
    }

    void merge(Profiler other) {
        if (other.columns == null) {
            return;
        }
        if (columns == null) {
            columns = other.columns;
            return;
        }
        for (int c = 0; c < columns.length; c++) {
            columns[c].merge(other.columns[c]);
        }
    }

    ColumnProfile column(String name) {
        for (ColumnProfile p : columns) {
            if (p.name.equals(name)) {
                return p;
            }
        }
        throw new IllegalArgumentException(name);
    }

    ColumnProfile[] columns() {
        return columns == null ? new ColumnProfile[0] : columns;
    }
}

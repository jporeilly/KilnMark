package io.kilnmark.kiln.steps.expr;

import java.util.HashMap;
import java.util.Map;

import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;

/**
 * The rows an expression is worked out over: an Arrow batch, read column by column as needed
 * (each column once), plus columns computed earlier ({@link #put}). Holds no Arrow memory of its
 * own; the batch stays owned by the caller.
 */
public final class Batch {

    private final int rows;
    private final VectorSchemaRoot root;
    private final Map<String, Col> columns = new HashMap<>();
    private final long nowMicros;

    private Batch(int rows, VectorSchemaRoot root, long nowMicros) {
        this.rows = rows;
        this.root = root;
        this.nowMicros = nowMicros;
    }

    /**
     * The rows of an Arrow batch.
     *
     * @param nowMicros what {@code now()} gives, in microseconds since 1970 (UTC): fixed when the step
     *                  starts, so every batch of a run sees the same time
     */
    public static Batch of(VectorSchemaRoot root, long nowMicros) {
        return new Batch(root.getRowCount(), root, nowMicros);
    }

    /** Rows given as columns. */
    public static Batch of(int rows, Map<String, Col> columns, long nowMicros) {
        Batch b = new Batch(rows, null, nowMicros);
        b.columns.putAll(columns);
        return b;
    }

    public int rows() {
        return rows;
    }

    long now() {
        return nowMicros;
    }

    /** Makes {@code name} read as {@code col} from now on, replacing a column of that name. */
    public void put(String name, Col col) {
        if (col.rows != rows) {
            throw new IllegalArgumentException("Column " + name + " has " + col.rows + " rows, not " + rows);
        }
        columns.put(name, col);
    }

    Col column(String name, int pos) throws ExprException {
        Col c = columns.get(name);
        if (c != null) {
            return c;
        }
        FieldVector v = root == null ? null : root.getVector(name);
        if (v == null) {
            throw new ExprException("the column '" + name + "' is missing from these rows", pos);
        }
        try {
            c = ArrowIO.read(v);
        } catch (IllegalArgumentException e) {
            throw new ExprException("the column '" + name + "' has a type expressions cannot use ("
                + v.getField().getType() + ")", pos);
        } catch (ArithmeticException e) {
            throw new ExprException("the column '" + name + "' has a timestamp too far from today to read", pos);
        }
        columns.put(name, c);
        return c;
    }
}

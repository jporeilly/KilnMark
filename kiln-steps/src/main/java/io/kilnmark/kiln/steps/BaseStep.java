package io.kilnmark.kiln.steps;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.arrow.vector.util.TransferPair;

import io.kilnmark.kiln.RowSet;
import io.kilnmark.kiln.Step;
import io.kilnmark.kiln.StepContext;
import io.kilnmark.kiln.StepException;
import io.kilnmark.kiln.steps.expr.ExprException;

/**
 * What the steps here share: taking and putting batches on named ports, splitting a batch's rows
 * between ports, and wording errors as "column full_name: ..." (the runner adds the step's name).
 *
 * <p>Memory: a step closes every batch it takes, unless it hands the batch on with {@link #put},
 * which gives it up.
 */
abstract class BaseStep implements Step {

    private final String type;
    /** What {@code now()} gives: the time the step started, in microseconds since 1970 (UTC). */
    protected long now;
    protected String name = "?";

    BaseStep(String type) {
        this.type = type;
    }

    @Override
    public final String type() {
        return type;
    }

    @Override
    public void init(StepContext context) throws StepException {
        name = context.stepName();
        Instant t = Instant.now();
        now = t.getEpochSecond() * 1_000_000L + t.getNano() / 1_000L;
    }

    /**
     * "column full_name: unknown column ...". The runner puts the step's name in front ("Step tidy
     * (compute) failed: ..."), as the pipeline compiler does for problems found before a run.
     */
    protected StepException fail(String where, ExprException e) {
        return new StepException(where + ": " + e.getMessage(), e);
    }

    protected StepException fail(String message) {
        return new StepException(message);
    }

    /** The next batch on an input port, or null at its end. */
    static VectorSchemaRoot take(StepContext ctx, String port) throws StepException {
        try {
            return ctx.input(port).take(ctx.allocator());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StepException("Interrupted", e);
        }
    }

    /** The row set behind an output port, or null when nothing is connected to it (its rows are dropped). */
    static RowSet out(StepContext ctx, String port) throws StepException {
        return ctx.outputPorts().contains(port) ? ctx.output(port) : null;
    }

    /**
     * Hands a batch to an output port, which then owns it. An empty batch, or one for a port with
     * nothing connected, is closed instead.
     */
    static void put(StepContext ctx, String port, VectorSchemaRoot batch) throws StepException {
        RowSet rs;
        try {
            rs = out(ctx, port);
        } catch (StepException | RuntimeException e) {
            batch.close();
            throw e;
        }
        if (rs == null || batch.getRowCount() == 0) {
            batch.close();
            return;
        }
        try {
            ctx.emit(port, batch);
        } catch (InterruptedException e) {
            batch.close(); // interrupted while waiting for room: the batch was not handed over
            Thread.currentThread().interrupt();
            throw new StepException("Interrupted", e);
        }
    }

    /**
     * Sends each row of {@code batch} to the port {@code target[i]} names in {@code ports} (or
     * nowhere, for -1), then gives the batch up: it is handed on whole when every row goes to one
     * connected port, and closed otherwise.
     */
    static void distribute(StepContext ctx, VectorSchemaRoot batch, int[] target, List<String> ports)
        throws StepException {
        boolean handedOver = false;
        try {
            int n = batch.getRowCount();
            int[] counts = new int[ports.size()];
            for (int i = 0; i < n; i++) {
                if (target[i] >= 0) {
                    counts[target[i]]++;
                }
            }
            for (int p = 0; p < ports.size(); p++) {
                if (counts[p] == 0 || out(ctx, ports.get(p)) == null) {
                    continue;
                }
                if (counts[p] == n) {
                    handedOver = true;
                    put(ctx, ports.get(p), batch);
                    return;
                }
                int[] rows = new int[counts[p]];
                int k = 0;
                for (int i = 0; i < n; i++) {
                    if (target[i] == p) {
                        rows[k++] = i;
                    }
                }
                put(ctx, ports.get(p), copy(batch, rows, rows.length, ctx.allocator()));
            }
        } finally {
            if (!handedOver) {
                batch.close();
            }
        }
    }

    /** A new batch holding rows {@code rows[0..count)} of {@code in}, allocated from {@code allocator}. */
    static VectorSchemaRoot copy(VectorSchemaRoot in, int[] rows, int count, BufferAllocator allocator) {
        List<FieldVector> out = new ArrayList<>();
        try {
            for (FieldVector v : in.getFieldVectors()) {
                FieldVector o = v.getField().createVector(allocator);
                out.add(o);
                o.setInitialCapacity(count);
                o.allocateNew();
                for (int j = 0; j < count; j++) {
                    o.copyFromSafe(rows[j], j, v);
                }
                o.setValueCount(count);
            }
            return new VectorSchemaRoot(in.getSchema(), out, count);
        } catch (RuntimeException e) {
            out.forEach(FieldVector::close);
            throw e;
        }
    }

    /** Moves a vector's buffers into a new vector described by {@code field} (same type, perhaps renamed). */
    static FieldVector move(FieldVector v, Field field, BufferAllocator allocator) {
        TransferPair tp = v.getTransferPair(field, allocator);
        tp.transfer();
        return (FieldVector) tp.getTo();
    }

    /** A vector of {@code rows} nulls. */
    static FieldVector nulls(Field field, int rows, BufferAllocator allocator) {
        FieldVector v = field.createVector(allocator);
        try {
            v.setInitialCapacity(rows);
            v.allocateNew();
            for (int i = 0; i < rows; i++) {
                v.setNull(i);
            }
            v.setValueCount(rows);
            return v;
        } catch (RuntimeException e) {
            v.close();
            throw e;
        }
    }

    /** The field called {@code name}, or null. */
    static Field field(Schema s, String name) {
        for (Field f : s.getFields()) {
            if (f.getName().equals(name)) {
                return f;
            }
        }
        return null;
    }

    static List<String> names(Schema s) {
        return s.getFields().stream().map(Field::getName).toList();
    }

    /** The same field, nullable. */
    static Field nullable(Field f) {
        if (f.isNullable()) {
            return f;
        }
        return new Field(f.getName(), new org.apache.arrow.vector.types.pojo.FieldType(true, f.getType(),
            f.getDictionary(), f.getMetadata()), f.getChildren());
    }

    /** Megabytes as bytes. */
    static long megabytes(long mb) {
        return mb * 1024L * 1024L;
    }

    static String count(long n) {
        return String.format(java.util.Locale.UK, "%,d", n);
    }
}

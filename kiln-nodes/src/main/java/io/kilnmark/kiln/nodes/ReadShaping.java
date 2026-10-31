package io.kilnmark.kiln.nodes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;

import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.nodes.expr.Batch;
import io.kilnmark.kiln.nodes.expr.ExprException;
import io.kilnmark.kiln.nodes.expr.Expression;

/**
 * What a read node with {@code columns} or {@code where} does to the rows its source sends
 * (docs/design/pipelines.md, "The read node's columns and where"): keeps the rows meeting the whole
 * condition, then only the chosen columns, then at most the row limit. A source may already have
 * narrowed its read; this makes the rows the same either way.
 */
public final class ReadShaping {

    private final Expression where;
    private final List<String> keep;
    private final long rowLimit;
    private final long now;
    private Schema boundTo;
    private Expression.Compiled compiled;
    private long passed;

    /**
     * @param where    the condition, or null for every row
     * @param keep     the columns to pass on, in this order, or empty for every column
     * @param rowLimit the most rows to pass on, or 0 for every row
     * @throws NodeException when the condition does not read
     */
    public ReadShaping(String where, List<String> keep, long rowLimit) throws NodeException {
        try {
            this.where = where == null || where.isBlank() ? null : Expression.parse(where);
        } catch (ExprException e) {
            throw new NodeException("where: " + e.getMessage(), e);
        }
        this.keep = keep == null ? List.of() : List.copyOf(keep);
        this.rowLimit = Math.max(0, rowLimit);
        Instant t = Instant.now();
        this.now = t.getEpochSecond() * 1_000_000L + t.getNano() / 1_000L;
    }

    /**
     * The columns a read needs from its source: the chosen ones and those the condition reads, in the
     * asset's order; empty when every column is wanted.
     */
    public static List<String> needed(Schema asset, List<String> keep, String where) throws ExprException {
        if (keep == null || keep.isEmpty()) {
            return List.of();
        }
        Set<String> want = new LinkedHashSet<>(keep);
        if (where != null && !where.isBlank()) {
            want.addAll(Expression.parse(where).columns());
        }
        List<String> out = new ArrayList<>();
        for (Field f : asset.getFields()) {
            if (want.contains(f.getName())) {
                out.add(f.getName());
            }
        }
        return out;
    }

    /**
     * The columns leaving the read: the chosen ones in the order chosen, or all of them. The condition
     * must read and check against the asset's columns, and give true or false.
     *
     * @throws ExprException naming what is wrong, with {@code columns:} or {@code where:} in front
     */
    public static Schema outputSchema(Schema asset, List<String> keep, String where) throws ExprException {
        if (where != null && !where.isBlank()) {
            Expression.Compiled c = Expression.parse(where).compile(asset);
            if (c.type().kind() != io.kilnmark.kiln.nodes.expr.Type.Kind.BOOLEAN && !c.type().isNull()) {
                throw new ExprException("the condition must be true or false, but it gives " + c.type().family(), 0,
                    "compare the value, for example amount > 0");
            }
        }
        if (keep == null || keep.isEmpty()) {
            return asset;
        }
        List<Field> fields = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String name : keep) {
            Field f = BaseNode.field(asset, name);
            if (f == null) {
                throw new ExprException("columns: '" + name + "' is not a column of this table or file", 0,
                    "its columns are " + String.join(", ", BaseNode.names(asset)));
            }
            if (!seen.add(name)) {
                throw new ExprException("columns: '" + name + "' is listed twice", 0, "list each column once");
            }
            fields.add(f);
        }
        return new Schema(fields);
    }

    /** Whether the row limit has been reached: the source need send nothing more. */
    public boolean full() {
        return rowLimit > 0 && passed >= rowLimit;
    }

    /**
     * The batch as the read passes it on, or null when no row of it is kept (with {@code keepEmpty}, an
     * empty batch with the read's columns instead, so what comes after still learns them: a write of no
     * rows still makes its file with a header). Closes {@code in} unless it is passed on as it is.
     */
    public VectorSchemaRoot apply(VectorSchemaRoot in, BufferAllocator allocator, boolean keepEmpty)
        throws NodeException {
        boolean handedOver = false;
        try {
            int n = in.getRowCount();
            int[] rows = new int[n];
            int count = 0;
            boolean[] meets = null;
            if (where != null) {
                if (!in.getSchema().equals(boundTo)) {
                    compiled = where.compile(in.getSchema());
                    boundTo = in.getSchema();
                }
                meets = compiled.test(Batch.of(in, now), null);
            }
            long room = rowLimit > 0 ? rowLimit - passed : Long.MAX_VALUE;
            for (int i = 0; i < n && count < room; i++) {
                if (meets == null || meets[i]) {
                    rows[count++] = i;
                }
            }
            passed += count;
            if (count == 0 && !keepEmpty) {
                return null;
            }
            VectorSchemaRoot kept = count == n ? in : BaseNode.copy(in, rows, count, allocator);
            if (kept != in) {
                in.close(); // its kept rows are copied
            }
            handedOver = true;
            return keep.isEmpty() ? kept : columns(kept, allocator);
        } catch (ExprException e) {
            throw new NodeException("where: " + e.getMessage(), e);
        } finally {
            if (!handedOver) {
                in.close();
            }
        }
    }

    /** Only the chosen columns, in the order chosen; the others are closed. */
    private VectorSchemaRoot columns(VectorSchemaRoot in, BufferAllocator allocator) {
        List<FieldVector> out = new ArrayList<>();
        List<Field> fields = new ArrayList<>();
        for (String name : keep) {
            FieldVector v = in.getVector(name);
            fields.add(v.getField());
            out.add(BaseNode.move(v, v.getField(), allocator));
        }
        int rows = in.getRowCount();
        in.close();
        return new VectorSchemaRoot(fields, out, rows);
    }
}

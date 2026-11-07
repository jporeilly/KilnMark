package io.kilnmark.kiln.nodes;

import java.io.Serial;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;

import io.kilnmark.kiln.NodeContext;
import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.RowErrors;
import io.kilnmark.kiln.nodes.expr.ExprException;

/**
 * Rows that fail at compute, select, filter and route (ADR 0054): finding them in a batch that
 * failed, by halving it until single rows remain, and sending them to the {@code errors} port.
 *
 * <p>Expressions work on a whole batch at once, so a failure says only that some row failed. A
 * batch that works costs nothing extra; one that fails is split in halves, and each failing half
 * again: about {@code k × log₂(n)} tries for {@code k} failing rows of {@code n}. A batch whose
 * single rows all work (it fails only as a whole) stops the run as before.
 */
final class Failing {

    private Failing() {
    }

    /** One row (or more) failed while a node worked out a batch. */
    static final class RowFailure extends Exception {
        @Serial
        private static final long serialVersionUID = 1L;

        /** Where, for the run's error: "column total", "cast.amount", "condition", "route big". */
        final String where;
        /** What goes in {@code error_column}: the column, or the setting of a condition. */
        final String column;
        final transient ExprException cause;

        RowFailure(String where, String column, ExprException cause) {
            super(where + ": " + cause.getMessage(), cause);
            this.where = where;
            this.column = column;
            this.cause = cause;
        }

        /** The failure as the run's error, worded as before rows could be set aside. */
        NodeException fatal() {
            return new NodeException(where + ": " + cause.getMessage(), cause);
        }
    }

    /** Works out some rows; throws when one of them fails. Closes what it makes. */
    interface Attempt {
        void run(VectorSchemaRoot rows) throws RowFailure, NodeException;
    }

    /** Whether something is connected to the node's errors port. */
    static boolean connected(NodeContext ctx) throws NodeException {
        return BaseNode.out(ctx, RowErrors.PORT) != null;
    }

    /** The settings every node with an errors port takes, after its own. */
    static List<io.kilnmark.kiln.SettingSpec> withTolerance(List<io.kilnmark.kiln.SettingSpec> own) {
        List<io.kilnmark.kiln.SettingSpec> out = new java.util.ArrayList<>(own);
        out.addAll(RowErrors.settings());
        return List.copyOf(out);
    }

    /** The node's own problems and the tolerance's. */
    static List<String> withTolerance(List<String> problems, Map<String, Object> config) {
        problems.addAll(RowErrors.check(config));
        return problems;
    }

    /** The columns leaving the errors port, given the input's. */
    static Schema errorsSchema(Schema in) {
        return in == null ? null : RowErrors.schema(in);
    }

    /** The failure of one row, worded from a fixed list: never from the value. */
    static RowErrors.Failure failure(String column, ExprException e) {
        if (e.code() == null) {
            return new RowErrors.Failure(column, "expression_failed", RowErrors.GENERIC);
        }
        String hint = e.hint() == null || e.hint().isEmpty() ? "" : "; " + e.hint();
        return new RowErrors.Failure(column, e.code(), e.problem() + hint);
    }

    /**
     * Each row's failure in a batch that failed ({@code null} for a row that works), or null when
     * no single row fails, so the batch fails only as a whole.
     */
    static RowErrors.Failure[] find(VectorSchemaRoot batch, Attempt attempt, BufferAllocator allocator)
        throws NodeException {
        int n = batch.getRowCount();
        RowErrors.Failure[] out = new RowErrors.Failure[n];
        int[] all = new int[n];
        Arrays.setAll(all, i -> i);
        boolean any = n == 1 ? single(batch, all, attempt, allocator, out) : halves(batch, all, attempt, allocator, out);
        return any ? out : null;
    }

    /** Splits {@code rows} (which failed together) in halves and tries each; true when a row failed. */
    private static boolean halves(VectorSchemaRoot batch, int[] rows, Attempt attempt, BufferAllocator allocator,
                                  RowErrors.Failure[] out) throws NodeException {
        int mid = rows.length / 2;
        boolean a = tryRows(batch, Arrays.copyOfRange(rows, 0, mid), attempt, allocator, out);
        boolean b = tryRows(batch, Arrays.copyOfRange(rows, mid, rows.length), attempt, allocator, out);
        return a || b;
    }

    private static boolean tryRows(VectorSchemaRoot batch, int[] rows, Attempt attempt, BufferAllocator allocator,
                                   RowErrors.Failure[] out) throws NodeException {
        if (rows.length == 1) {
            return single(batch, rows, attempt, allocator, out);
        }
        try (VectorSchemaRoot part = BaseNode.copy(batch, rows, rows.length, allocator)) {
            attempt.run(part);
            return false;
        } catch (RowFailure f) {
            return halves(batch, rows, attempt, allocator, out);
        }
    }

    private static boolean single(VectorSchemaRoot batch, int[] rows, Attempt attempt, BufferAllocator allocator,
                                  RowErrors.Failure[] out) throws NodeException {
        try (VectorSchemaRoot part = BaseNode.copy(batch, rows, 1, allocator)) {
            attempt.run(part);
            return false;
        } catch (RowFailure f) {
            out[rows[0]] = failure(f.column, f.cause);
            return true;
        }
    }

    /**
     * Sends the failing rows of {@code batch} to the errors port and counts them, and returns the
     * rows that work as a new batch (null when none do). {@code batch} is unchanged.
     *
     * @throws NodeException past the tolerance
     */
    static VectorSchemaRoot setAside(VectorSchemaRoot batch, RowErrors.Failure[] failures, RowErrors.Tally tally,
                                     NodeContext ctx, String node) throws NodeException {
        int n = batch.getRowCount();
        int[] bad = new int[n];
        int[] good = new int[n];
        RowErrors.Failure[] why = new RowErrors.Failure[n];
        int b = 0;
        int g = 0;
        for (int i = 0; i < n; i++) {
            if (failures[i] != null) {
                why[b] = failures[i];
                bad[b++] = i;
            } else {
                good[g++] = i;
            }
        }
        BaseNode.put(ctx, RowErrors.PORT, RowErrors.batch(batch, bad, why, b, node, ctx.allocator()));
        tally.failed(b);
        return g == 0 ? null : BaseNode.copy(batch, good, g, ctx.allocator());
    }

    /** A node's work on some rows, giving its result: a new batch, or where each row goes. */
    interface Work<T> {
        T apply(VectorSchemaRoot rows) throws RowFailure, NodeException;
    }

    /**
     * The result of {@link #work}: {@code rows} is the batch it was worked out on, the batch given or,
     * when rows were set aside ({@code fresh}), a new batch of the rest, which the caller then owns.
     */
    record Done<T>(VectorSchemaRoot rows, T result, boolean fresh) {
    }

    /**
     * Works out {@code batch}. When a row fails and something is connected to the errors port, the
     * failing rows go there and the rest are worked out; null when every row failed. Without the
     * port, or when no single row fails, the first failure stops the run, worded as before. The
     * caller still owns {@code batch}.
     */
    static <T> Done<T> work(VectorSchemaRoot batch, Work<T> work, RowErrors.Tally tally, NodeContext ctx,
                            String node) throws NodeException {
        try {
            return new Done<>(batch, work.apply(batch), false);
        } catch (RowFailure f) {
            if (!connected(ctx)) {
                throw f.fatal();
            }
            RowErrors.Failure[] failures = find(batch, rows -> release(work.apply(rows)), ctx.allocator());
            if (failures == null) {
                throw f.fatal();
            }
            VectorSchemaRoot good = setAside(batch, failures, tally, ctx, node);
            if (good == null) {
                return null;
            }
            try {
                return new Done<>(good, work.apply(good), true);
            } catch (RowFailure again) {
                good.close();
                throw again.fatal();
            } catch (NodeException | RuntimeException e) {
                good.close();
                throw e;
            }
        }
    }

    /** Frees what a try made, when it is a batch. */
    private static void release(Object result) {
        if (result instanceof VectorSchemaRoot b) {
            b.close();
        }
    }

    /** At the input's end: the share is judged, and the counts go in the run's log. */
    static void finish(RowErrors.Tally tally, NodeContext ctx) throws NodeException {
        String note = tally.note();
        if (note != null) {
            ctx.note(note);
        }
        tally.finish();
    }
}

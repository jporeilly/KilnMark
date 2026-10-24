package io.kilnmark.spike.arrow;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.SplittableRandom;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.TimeStampMicroTZVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * Deterministic transactions, the same shape as the lab's billing.transactions, as Arrow batches or as
 * row objects. Known cardinalities: account_id 7,500, merchant 10, reference min(rows, 2,000,000).
 */
final class SyntheticSource {

    static final int ACCOUNTS = 7_500;
    static final int REFERENCES = 2_000_000;
    static final String[] MERCHANTS = {"Tesco", "Sainsbury's", "Boots", "Greggs", "TfL", "Amazon UK",
        "Octopus Energy", "Pret", "Argos", "Waterstones"};
    private static final byte[][] MERCHANT_BYTES = new byte[MERCHANTS.length][];
    private static final long BASE_MICROS = 1_704_067_200_000_000L; // 2024-01-01T00:00Z
    private static final long SPAN_MICROS = 660L * 86_400_000_000L;

    static {
        for (int i = 0; i < MERCHANTS.length; i++) {
            MERCHANT_BYTES[i] = MERCHANTS[i].getBytes(StandardCharsets.UTF_8);
        }
    }

    static final Schema SCHEMA = new Schema(List.of(
        Field.nullable("txn_id", new ArrowType.Int(64, true)),
        Field.nullable("account_id", new ArrowType.Int(32, true)),
        Field.nullable("amount", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
        Field.nullable("merchant", ArrowType.Utf8.INSTANCE),
        Field.nullable("reference", ArrowType.Utf8.INSTANCE),
        Field.nullable("txn_at", new ArrowType.Timestamp(TimeUnit.MICROSECOND, "UTC"))));

    private final long total;
    private final int batchSize;
    private final SplittableRandom random = new SplittableRandom(42);
    private long next;

    SyntheticSource(long total, int batchSize) {
        this.total = total;
        this.batchSize = batchSize;
    }

    boolean hasNext() {
        return next < total;
    }

    static long expectedDistinctReferences(long rows) {
        return Math.min(rows, REFERENCES);
    }

    /** The next batch, allocated from {@code allocator}; the caller owns and closes it. */
    VectorSchemaRoot nextBatch(BufferAllocator allocator) {
        int n = (int) Math.min(batchSize, total - next);
        VectorSchemaRoot root = VectorSchemaRoot.create(SCHEMA, allocator);
        try {
            for (FieldVector v : root.getFieldVectors()) {
                v.setInitialCapacity(n);
            }
            root.allocateNew();
            BigIntVector id = (BigIntVector) root.getVector(0);
            IntVector account = (IntVector) root.getVector(1);
            Float8Vector amount = (Float8Vector) root.getVector(2);
            VarCharVector merchant = (VarCharVector) root.getVector(3);
            VarCharVector reference = (VarCharVector) root.getVector(4);
            TimeStampMicroTZVector at = (TimeStampMicroTZVector) root.getVector(5);
            for (int i = 0; i < n; i++) {
                long row = next + i;
                id.set(i, row);
                account.set(i, account(row));
                double a = random.nextDouble();
                if (a < 0.01) {
                    amount.setNull(i);
                } else {
                    amount.set(i, amount(a));
                }
                merchant.setSafe(i, MERCHANT_BYTES[(int) (row % MERCHANTS.length)]);
                reference.setSafe(i, reference(row).getBytes(StandardCharsets.UTF_8));
                at.set(i, BASE_MICROS + (long) (random.nextDouble() * SPAN_MICROS));
            }
            root.setRowCount(n);
            next += n;
            return root;
        } catch (RuntimeException e) {
            root.close();
            throw e;
        }
    }

    /** The next batch as boxed row objects, for the baseline. */
    Object[][] nextRows() {
        int n = (int) Math.min(batchSize, total - next);
        Object[][] rows = new Object[n][];
        for (int i = 0; i < n; i++) {
            long row = next + i;
            double a = random.nextDouble();
            rows[i] = new Object[] {
                row,
                account(row),
                a < 0.01 ? null : amount(a),
                MERCHANTS[(int) (row % MERCHANTS.length)],
                reference(row),
                BASE_MICROS + (long) (random.nextDouble() * SPAN_MICROS)
            };
        }
        next += n;
        return rows;
    }

    private static int account(long row) {
        return (int) (1 + (row * 40_507L) % ACCOUNTS);
    }

    private static double amount(double a) {
        return Math.round((a * 900 + 1) * 100) / 100.0;
    }

    private static String reference(long row) {
        return "REF" + (row % REFERENCES);
    }
}

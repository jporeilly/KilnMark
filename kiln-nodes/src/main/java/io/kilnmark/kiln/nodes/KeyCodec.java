package io.kilnmark.kiln.nodes;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.vector.BaseVariableWidthVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VariableWidthFieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;

import io.kilnmark.kiln.nodes.expr.ArrowIO;
import io.kilnmark.kiln.nodes.expr.Col;
import io.kilnmark.kiln.nodes.expr.Type;

/**
 * Turns the key of each row (the columns a sort orders by, or a join matches on) into bytes whose
 * order, compared byte by byte as unsigned numbers, is the order of the values: the order the
 * expression language uses for {@code <} (docs/design/pipelines.md, "Ordering"). Sorting and merging
 * then compare bytes only, whatever the columns' types, and two sides of a join encoded with the same
 * types give equal bytes exactly for equal values.
 *
 * <ul>
 *   <li>Whole numbers, dates (days) and timestamps (microseconds, UTC): eight bytes, sign bit flipped.</li>
 *   <li>Floating point: eight bytes ordered as {@link Double#compare}, with -0.0 equal to 0.0 and every
 *       NaN equal and after +infinity.</li>
 *   <li>Decimals: by value, at the part's scale: a sign byte, the length of the digits' bytes and the
 *       bytes (inverted for negative numbers).</li>
 *   <li>Text: its UTF-8 bytes, which order as Unicode code points, with a zero byte escaped and two
 *       zero bytes at the end, so no key is the start of another. No collation: "B" sorts before "a".</li>
 *   <li>Booleans: false before true.</li>
 * </ul>
 *
 * <p>Each part starts with a byte saying whether the value is null (0 for nulls first, 2 for nulls
 * last, 1 for a value); descending parts invert their value's bytes, never that byte. For a join
 * ({@code joinKeys}), the whole key starts with 1, or with 2 and nothing more when any part is null
 * (or cannot be matched), so such rows sort last, keep their order, and never equal another key.
 */
final class KeyCodec {

    /** One part of a key: a column, read as type {@code as}, in a direction, with its nulls first or last. */
    record Part(String column, Type as, boolean descending, boolean nullsFirst) {
    }

    /** The first byte of a join key with a null (or unmatchable) part. */
    static final byte NULL_KEY = 2;
    private static final byte VALUE = 1;
    private static final byte NULL_FIRST = 0;
    private static final byte NULL_LAST = 2;

    private final List<Part> parts;
    private final boolean joinKeys;

    KeyCodec(List<Part> parts, boolean joinKeys) {
        this.parts = List.copyOf(parts);
        this.joinKeys = joinKeys;
    }

    List<Part> parts() {
        return parts;
    }

    /** The keys of every row of {@code batch}. */
    KeyBlock encode(VectorSchemaRoot batch) {
        int n = batch.getRowCount();
        Reader[] readers = new Reader[parts.size()];
        for (int p = 0; p < readers.length; p++) {
            readers[p] = reader(batch.getVector(parts.get(p).column()), parts.get(p).as());
        }
        Sink out = new Sink(Math.max(16, n * 10));
        int[] offsets = new int[n + 1];
        for (int i = 0; i < n; i++) {
            int start = out.length;
            if (joinKeys) {
                out.put(VALUE);
            }
            for (int p = 0; p < readers.length; p++) {
                Part part = parts.get(p);
                if (!readers[p].write(i, part, out)) {
                    if (joinKeys) {
                        out.length = start;
                        out.put(NULL_KEY);
                        break;
                    }
                    out.put(part.nullsFirst() ? NULL_FIRST : NULL_LAST);
                }
            }
            offsets[i + 1] = out.length;
        }
        return new KeyBlock(out.trimmed(), offsets);
    }

    /** Writes one part of a row's key; false when the value is null (or, for a join, cannot match). */
    private interface Reader {
        boolean write(int row, Part part, Sink out);
    }

    private static Reader reader(FieldVector v, Type as) {
        Type own = Type.of(v.getField().getType());
        if (own == null) {
            throw new IllegalArgumentException("A key column cannot hold " + v.getField().getType() + " values");
        }
        if (as.kind() == Type.Kind.UTF8 && v instanceof BaseVariableWidthVector b
            && !(v.getField().getType() instanceof ArrowType.Binary)) {
            ArrowBuf data = b.getDataBuffer();
            return (row, part, out) -> {
                if (b.isNull(row)) {
                    return false;
                }
                int start = b.getStartOffset(row);
                int len = b.getValueLength(row);
                out.put(VALUE);
                int at = out.length;
                for (int k = 0; k < len; k++) {
                    byte x = data.getByte(start + (long) k);
                    out.put(x);
                    if (x == 0) {
                        out.put((byte) 0xFF);
                    }
                }
                out.put((byte) 0);
                out.put((byte) 0);
                if (part.descending()) {
                    out.invert(at);
                }
                return true;
            };
        }
        if (as.kind() == Type.Kind.UTF8 && v instanceof VariableWidthFieldVector w) {
            return (row, part, out) -> {
                if (w.isNull(row)) {
                    return false;
                }
                out.put(VALUE);
                int at = out.length;
                text(w.get(row), out);
                if (part.descending()) {
                    out.invert(at);
                }
                return true;
            };
        }
        Col c = ArrowIO.read(v);
        return (row, part, out) -> {
            Object value = c.key(row, as.kind() == Type.Kind.NULL ? own : as);
            if (value == null || value instanceof BigInteger) {
                return false; // a date too far away for a timestamp: no timestamp can equal it
            }
            out.put(VALUE);
            int at = out.length;
            switch (as.kind()) {
                case INT64, DATE, TIMESTAMP -> out.putLong(((Number) value).longValue() ^ Long.MIN_VALUE);
                case FLOAT64 -> {
                    double d = ((Number) value).doubleValue();
                    long bits = Double.doubleToLongBits(d == 0.0 ? 0.0 : d);
                    out.putLong(bits < 0 ? ~bits : bits ^ Long.MIN_VALUE);
                }
                case DECIMAL -> decimal((BigDecimal) value, as.scale(), out);
                case BOOLEAN -> out.put((Boolean) value ? (byte) 1 : (byte) 0);
                case UTF8 -> text(((String) value).getBytes(StandardCharsets.UTF_8), out);
                case NULL -> {
                    return false;
                }
            }
            if (part.descending()) {
                out.invert(at);
            }
            return true;
        };
    }

    private static void text(byte[] bytes, Sink out) {
        for (byte x : bytes) {
            out.put(x);
            if (x == 0) {
                out.put((byte) 0xFF);
            }
        }
        out.put((byte) 0);
        out.put((byte) 0);
    }

    /** A decimal by value at {@code scale}: sign, then the length and bytes of its digits. */
    static void decimal(BigDecimal d, int scale, Sink out) {
        // A column's values never have more digits after the point than its type (and a join's common
        // type has the larger scale of the two), so this is exact.
        BigInteger unscaled = (d.scale() == scale ? d
            : d.stripTrailingZeros().setScale(scale, java.math.RoundingMode.UNNECESSARY)).unscaledValue();
        int sign = unscaled.signum();
        if (sign == 0) {
            out.put((byte) 1);
            return;
        }
        byte[] mag = unscaled.abs().toByteArray();
        int from = mag[0] == 0 ? 1 : 0;
        int len = mag.length - from;
        if (sign > 0) {
            out.put((byte) 2);
            out.put((byte) len);
            for (int k = from; k < mag.length; k++) {
                out.put(mag[k]);
            }
        } else {
            out.put((byte) 0);
            out.put((byte) (255 - len));
            for (int k = from; k < mag.length; k++) {
                out.put((byte) ~mag[k]);
            }
        }
    }

    /** A growable run of bytes. */
    static final class Sink {
        byte[] bytes;
        int length;

        Sink(int capacity) {
            bytes = new byte[capacity];
        }

        void put(byte b) {
            if (length == bytes.length) {
                bytes = Arrays.copyOf(bytes, Math.max(16, (int) Math.min(Integer.MAX_VALUE - 8, bytes.length * 2L)));
            }
            bytes[length++] = b;
        }

        void putLong(long v) {
            for (int s = 56; s >= 0; s -= 8) {
                put((byte) (v >>> s));
            }
        }

        void put(byte[] b, int from, int to) {
            int n = to - from;
            if (length + n > bytes.length) {
                bytes = Arrays.copyOf(bytes, (int) Math.min(Integer.MAX_VALUE - 8, Math.max(bytes.length * 2L,
                    (long) length + n)));
            }
            System.arraycopy(b, from, bytes, length, n);
            length += n;
        }

        void invert(int from) {
            for (int k = from; k < length; k++) {
                bytes[k] = (byte) ~bytes[k];
            }
        }

        byte[] trimmed() {
            return length == bytes.length ? bytes : Arrays.copyOf(bytes, length);
        }
    }

    /**
     * The keys of a batch's rows: the bytes of all of them, and where each starts ({@code offsets[i]}
     * to {@code offsets[i + 1]}).
     */
    record KeyBlock(byte[] data, int[] offsets) {

        int rows() {
            return offsets.length - 1;
        }

        /** Roughly the memory it takes, in bytes. */
        long bytes() {
            return 32L + data.length + 4L * offsets.length;
        }

        /** The first eight bytes of a key as an unsigned number, zeros past its end: compares as the bytes do. */
        long prefix(int row) {
            int from = offsets[row];
            int to = Math.min(offsets[row + 1], from + 8);
            long p = 0;
            for (int k = from; k < from + 8; k++) {
                p = (p << 8) | (k < to ? data[k] & 0xFF : 0);
            }
            return p;
        }

        boolean isNullKey(int row) {
            return offsets[row + 1] > offsets[row] && data[offsets[row]] == NULL_KEY;
        }

        static int compare(KeyBlock a, int i, KeyBlock b, int j) {
            return Arrays.compareUnsigned(a.data, a.offsets[i], a.offsets[i + 1], b.data, b.offsets[j],
                b.offsets[j + 1]);
        }

        static boolean equal(KeyBlock a, int i, KeyBlock b, int j) {
            return Arrays.equals(a.data, a.offsets[i], a.offsets[i + 1], b.data, b.offsets[j], b.offsets[j + 1]);
        }
    }
}

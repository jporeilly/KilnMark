package io.kilnmark.kiln.nodes;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.vector.BaseVariableWidthVector;
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.DateMilliVector;
import org.apache.arrow.vector.TimeStampVector;
import org.apache.arrow.vector.UInt8Vector;
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
 *   <li>Whole numbers: eight bytes, sign bit flipped (unsigned 64-bit numbers as they are).</li>
 *   <li>Dates and timestamps: the instant, to the nanosecond, whatever the column's unit.</li>
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
 * (or cannot be matched), so such rows sort last, keep their order, and never equal another key;
 * and each part is encoded by its family alone ({@link #universal}), so the two sides of a join are
 * encoded without knowing each other's types.
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
    private static final long MICROS_PER_DAY = 86_400_000_000L;

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
            FieldVector v = batch.getVector(parts.get(p).column());
            readers[p] = joinKeys ? universal(v) : reader(v, parts.get(p).as());
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

    /** Whether keys of these types can be matched: both numbers, both text, both true/false, or both dates and times. */
    static boolean matchable(Type a, Type b) {
        return a.isNull() || b.isNull() || family(a) == family(b);
    }

    private static int family(Type t) {
        return switch (t.kind()) {
            case INT64, FLOAT64, DECIMAL -> 1;
            case UTF8 -> 2;
            case BOOLEAN -> 3;
            case DATE, TIMESTAMP -> 4;
            case NULL -> 0;
        };
    }

    // ---- join keys: one encoding per family, whatever the column's own type ----

    private static final byte NEG_INF = 1;
    private static final byte NEGATIVE = 2;
    private static final byte ZERO = 3;
    private static final byte POSITIVE = 4;
    private static final byte POS_INF = 5;
    private static final byte NAN = 6;

    /**
     * A join key's part encoded by its family alone, so each side is encoded without knowing the
     * other's types and equal values give equal bytes: numbers by exact value (a whole number, a
     * decimal and a float of the same value are one key; a float as its shortest decimal, as lookup
     * matches it), dates and timestamps as microseconds since 1970 (a date at midnight), text as its
     * UTF-8 bytes, booleans as one byte.
     */
    private static Reader universal(FieldVector v) {
        Type own = Type.of(v.getField().getType());
        if (own == null) {
            throw new IllegalArgumentException("A key column cannot hold " + v.getField().getType() + " values");
        }
        if (own.kind() == Type.Kind.UTF8) {
            return reader(v, Type.UTF8);
        }
        Col c = ArrowIO.read(v);
        return (row, part, out) -> {
            if (c.isNull(row)) {
                return false;
            }
            Object value = c.get(row);
            out.put(VALUE);
            switch (own.kind()) {
                case INT64 -> number((Long) value, out);
                case DECIMAL -> number((BigDecimal) value, out);
                case FLOAT64 -> {
                    double d = (Double) value;
                    if (Double.isNaN(d)) {
                        out.put(NAN);
                    } else if (Double.isInfinite(d)) {
                        out.put(d > 0 ? POS_INF : NEG_INF);
                    } else {
                        number(BigDecimal.valueOf(d), out);
                    }
                }
                case DATE -> {
                    long days = (Long) value;
                    if (Math.abs(days) > Long.MAX_VALUE / MICROS_PER_DAY) {
                        return false; // too far away for a timestamp: no timestamp can equal it
                    }
                    out.putLong(days * MICROS_PER_DAY ^ Long.MIN_VALUE);
                }
                case TIMESTAMP -> out.putLong((Long) value ^ Long.MIN_VALUE);
                case BOOLEAN -> out.put((Boolean) value ? (byte) 1 : (byte) 0);
                case UTF8, NULL -> {
                    return false;
                }
            }
            return true;
        };
    }

    /** A whole number as {@link #number(BigDecimal, Sink)} writes it, without making a BigDecimal or a string. */
    static void number(long v, Sink out) {
        if (v == 0) {
            out.put(ZERO);
            return;
        }
        boolean negative = v < 0;
        long m = negative ? -v : v; // Long.MIN_VALUE stays as is, read as unsigned below
        byte[] digits = new byte[20]; // least significant first
        int n = 0;
        while (m != 0) {
            digits[n++] = (byte) Long.remainderUnsigned(m, 10);
            m = Long.divideUnsigned(m, 10);
        }
        int low = 0;
        while (digits[low] == 0) {
            low++;
        }
        out.put(negative ? NEGATIVE : POSITIVE);
        int at = out.length;
        out.putInt(n ^ Integer.MIN_VALUE);
        for (int k = n - 1; k >= low; k--) {
            out.put((byte) (digits[k] + 1));
        }
        out.put((byte) 0);
        if (negative) {
            out.invert(at);
        }
    }

    /**
     * Any finite number, by value: its sign, then (as 0.d1d2... times 10 to the power e) the power e
     * and the digits without trailing zeros, each a byte, then a zero byte; for a negative number,
     * the power and the digits inverted. Equal values give equal bytes whatever their scale.
     */
    static void number(BigDecimal d, Sink out) {
        if (d.signum() == 0) {
            out.put(ZERO);
            return;
        }
        BigDecimal s = d.stripTrailingZeros();
        String digits = s.unscaledValue().abs().toString();
        digits(s.signum() < 0, digits, digits.length() - s.scale(), out);
    }

    private static void digits(boolean negative, String digits, long power, Sink out) {
        int end = digits.length();
        while (end > 1 && digits.charAt(end - 1) == '0') {
            end--;
        }
        out.put(negative ? NEGATIVE : POSITIVE);
        int at = out.length;
        out.putInt(Math.clamp(power, Integer.MIN_VALUE, Integer.MAX_VALUE) ^ Integer.MIN_VALUE);
        for (int k = 0; k < end; k++) {
            out.put((byte) (digits.charAt(k) - '0' + 1));
        }
        out.put((byte) 0);
        if (negative) {
            out.invert(at);
        }
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
        if (as.kind() == own.kind() && (own.kind() == Type.Kind.TIMESTAMP || own.kind() == Type.Kind.DATE)) {
            return temporal(v);
        }
        if (as.kind() == Type.Kind.INT64 && v instanceof UInt8Vector u) {
            // unsigned 64-bit: its bits in order as they are, so 2^63 and above sort after the rest
            return (row, part, out) -> {
                if (u.isNull(row)) {
                    return false;
                }
                out.put(VALUE);
                int at = out.length;
                out.putLong(u.get(row));
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

    /**
     * A date or timestamp as the instant it stands for, to the nanosecond, whatever its unit: whole
     * seconds since 1970 (eight bytes, sign flipped), then nanoseconds into that second (four bytes).
     * A date is its midnight. Exact for every unit, so timestamps a nanosecond apart sort apart, and
     * every date Arrow can hold fits.
     */
    private static Reader temporal(FieldVector v) {
        Instants at = instants(v);
        return (row, part, out) -> {
            if (v.isNull(row)) {
                return false;
            }
            out.put(VALUE);
            int start = out.length;
            at.write(row, out);
            if (part.descending()) {
                out.invert(start);
            }
            return true;
        };
    }

    /** Writes the instant of a row of a date or timestamp vector. */
    interface Instants {
        void write(int row, Sink out);
    }

    static Instants instants(FieldVector v) {
        if (v instanceof DateDayVector d) {
            return (row, out) -> instant(d.get(row) * 86_400L, 0, out);
        }
        if (v instanceof DateMilliVector d) {
            return (row, out) -> {
                long ms = d.get(row);
                instant(Math.floorDiv(ms, 1_000L), (int) Math.floorMod(ms, 1_000L) * 1_000_000, out);
            };
        }
        TimeStampVector t = (TimeStampVector) v;
        org.apache.arrow.vector.types.TimeUnit unit = ((ArrowType.Timestamp) v.getField().getType()).getUnit();
        long perSecond = switch (unit) {
            case SECOND -> 1L;
            case MILLISECOND -> 1_000L;
            case MICROSECOND -> 1_000_000L;
            case NANOSECOND -> 1_000_000_000L;
        };
        int nanosPer = (int) (1_000_000_000L / perSecond);
        return (row, out) -> {
            long raw = t.get(row);
            instant(Math.floorDiv(raw, perSecond), (int) Math.floorMod(raw, perSecond) * nanosPer, out);
        };
    }

    private static void instant(long seconds, int nanos, Sink out) {
        out.putLong(seconds ^ Long.MIN_VALUE);
        out.putInt(nanos);
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

        void putInt(int v) {
            for (int s = 24; s >= 0; s -= 8) {
                put((byte) (v >>> s));
            }
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

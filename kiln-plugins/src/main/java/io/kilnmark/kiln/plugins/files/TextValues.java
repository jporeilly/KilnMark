package io.kilnmark.kiln.plugins.files;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.regex.Pattern;

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
import org.apache.arrow.vector.TimeStampMicroTZVector;
import org.apache.arrow.vector.TimeStampMicroVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;

/**
 * Turning text from CSV and JSON into typed values: the strict tests type inference uses, and the
 * lenient parsing the reader uses.
 *
 * <p>Inference is strict so a column is only typed when every sampled value fits: integers with a
 * leading zero ({@code 007}, phone numbers) stay text, dates and timestamps must be ISO 8601. Reading
 * is lenient: a value that still does not parse is read as null and counted, never a failure.
 */
final class TextValues {

    private static final Pattern INTEGER = Pattern.compile("[+-]?(0|[1-9][0-9]*)");
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(0|[1-9][0-9]*)\\.[0-9]+");
    private static final Pattern FLOAT = Pattern.compile("[+-]?(0|[1-9][0-9]*)(\\.[0-9]+)?[eE][+-]?[0-9]+");

    private TextValues() {
    }

    /** A date and time, with the offset it was written with or null when it had none. */
    record DateTime(LocalDateTime local, ZoneOffset offset) {

        Instant instant() {
            return local.toInstant(offset == null ? ZoneOffset.UTC : offset);
        }
    }

    static Boolean bool(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "true", "t", "yes" -> Boolean.TRUE;
            case "false", "f", "no" -> Boolean.FALSE;
            default -> null;
        };
    }

    /** Digits with an optional sign and no leading zero, of any size. */
    static boolean isWholeNumber(String s) {
        return INTEGER.matcher(s).matches();
    }

    /** A whole number that fits int64. */
    static boolean isInteger(String s) {
        if (!INTEGER.matcher(s).matches()) {
            return false;
        }
        try {
            Long.parseLong(s);
            return true;
        } catch (NumberFormatException e) {
            return false; // too big for int64
        }
    }

    /** Plain decimal notation with a fraction, for example {@code -12.50}. */
    static boolean isDecimal(String s) {
        return DECIMAL.matcher(s).matches();
    }

    /** Scientific notation, for example {@code 1.5e-3}. */
    static boolean isScientific(String s) {
        return FLOAT.matcher(s).matches();
    }

    static LocalDate date(String s) {
        if (s.length() != 10 || s.charAt(4) != '-' || s.charAt(7) != '-') {
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static LocalTime time(String s) {
        if (s.length() < 5 || s.charAt(2) != ':') {
            return null;
        }
        try {
            return LocalTime.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * An ISO 8601 date and time: {@code T} or a space between them, optional seconds and fraction,
     * and an optional offset written {@code Z}, {@code +01}, {@code +0100} or {@code +01:00}.
     */
    static DateTime dateTime(String s) {
        if (s.length() < 16 || s.charAt(4) != '-' || s.charAt(7) != '-' || s.charAt(13) != ':') {
            return null;
        }
        char sep = s.charAt(10);
        if (sep != 'T' && sep != ' ' && sep != 't') {
            return null;
        }
        String local = s.substring(0, 10) + 'T' + s.substring(11);
        ZoneOffset offset = null;
        int at = -1;
        for (int i = 16; i < local.length(); i++) {
            char c = local.charAt(i);
            if (c == '+' || c == '-' || c == 'Z' || c == 'z') {
                at = i;
                break;
            }
        }
        try {
            if (at >= 0) {
                String o = local.substring(at);
                local = local.substring(0, at);
                if (o.equalsIgnoreCase("z")) {
                    offset = ZoneOffset.UTC;
                } else {
                    if (o.length() == 3) {
                        o = o + ":00";
                    } else if (o.length() == 5 && o.indexOf(':') < 0) {
                        o = o.substring(0, 3) + ":" + o.substring(3);
                    }
                    offset = ZoneOffset.of(o);
                }
            }
            return new DateTime(LocalDateTime.parse(local), offset);
        } catch (DateTimeParseException e) {
            return null;
        } catch (java.time.DateTimeException e) {
            return null; // a malformed offset
        }
    }

    /**
     * Writes {@code text} into {@code vector} at {@code row} as {@code column}'s kind. Null, and an
     * empty value for any kind but text, are written as null.
     *
     * @return false when the value did not parse and was written as null
     */
    static boolean write(FileColumn column, FieldVector vector, int row, String text) {
        if (text == null) {
            vector.setNull(row);
            return true;
        }
        if (column.kind() == io.kilnmark.kiln.plugins.jdbc.JdbcColumn.Kind.TEXT) {
            ((VarCharVector) vector).setSafe(row, text.getBytes(StandardCharsets.UTF_8));
            return true;
        }
        String s = text.strip();
        if (s.isEmpty()) {
            vector.setNull(row);
            return true;
        }
        try {
            switch (column.kind()) {
                case BOOLEAN -> {
                    Boolean b = bool(s);
                    if (b == null) {
                        b = s.equals("1") ? Boolean.TRUE : s.equals("0") ? Boolean.FALSE : null;
                    }
                    if (b == null) {
                        return unparsed(vector, row);
                    }
                    ((BitVector) vector).setSafe(row, b ? 1 : 0);
                }
                case INT16 -> ((SmallIntVector) vector).setSafe(row, Short.parseShort(s));
                case INT32 -> ((IntVector) vector).setSafe(row, Integer.parseInt(s));
                case INT64 -> ((BigIntVector) vector).setSafe(row, Long.parseLong(s));
                case FLOAT32 -> ((Float4Vector) vector).setSafe(row, Float.parseFloat(s));
                case FLOAT64 -> ((Float8Vector) vector).setSafe(row, Double.parseDouble(s));
                case DECIMAL -> {
                    BigDecimal d = new BigDecimal(s).setScale(column.scale(), RoundingMode.HALF_EVEN);
                    if (d.precision() > column.precision()) {
                        return unparsed(vector, row);
                    }
                    ((DecimalVector) vector).setSafe(row, d);
                }
                case BINARY -> ((VarBinaryVector) vector).setSafe(row, text.getBytes(StandardCharsets.UTF_8));
                case DATE -> {
                    LocalDate d = date(s);
                    if (d == null) {
                        DateTime dt = dateTime(s);
                        if (dt == null) {
                            return unparsed(vector, row);
                        }
                        d = dt.local().toLocalDate();
                    }
                    ((DateDayVector) vector).setSafe(row, (int) d.toEpochDay());
                }
                case TIME -> {
                    LocalTime t = time(s);
                    if (t == null) {
                        return unparsed(vector, row);
                    }
                    ((TimeMicroVector) vector).setSafe(row, t.toNanoOfDay() / 1_000);
                }
                case TIMESTAMP -> {
                    LocalDateTime t = localDateTime(s);
                    if (t == null) {
                        return unparsed(vector, row);
                    }
                    ((TimeStampMicroVector) vector).setSafe(row, micros(t.toInstant(ZoneOffset.UTC)));
                }
                case TIMESTAMP_TZ -> {
                    DateTime t = dateTime(s);
                    if (t == null) {
                        LocalDate d = date(s);
                        if (d == null) {
                            return unparsed(vector, row);
                        }
                        t = new DateTime(d.atStartOfDay(), null);
                    }
                    ((TimeStampMicroTZVector) vector).setSafe(row, micros(t.instant()));
                }
                case TEXT -> throw new IllegalStateException("handled above");
            }
            return true;
        } catch (RuntimeException e) {
            return unparsed(vector, row); // NumberFormatException, out of range, ...
        }
    }

    /** A timestamp without zone: one written with an offset is converted to UTC. */
    private static LocalDateTime localDateTime(String s) {
        DateTime t = dateTime(s);
        if (t != null) {
            return t.offset() == null ? t.local() : LocalDateTime.ofInstant(t.instant(), ZoneOffset.UTC);
        }
        LocalDate d = date(s);
        return d == null ? null : d.atStartOfDay();
    }

    private static boolean unparsed(FieldVector vector, int row) {
        vector.setNull(row);
        return false;
    }

    static long micros(Instant t) {
        return ChronoUnit.MICROS.between(Instant.EPOCH, t);
    }
}

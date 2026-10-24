package io.kilnmark.kiln.plugins.profile;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.HexFormat;

import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.TimeMicroVector;
import org.apache.arrow.vector.TimeStampMicroTZVector;
import org.apache.arrow.vector.TimeStampMicroVector;
import org.apache.arrow.vector.TimeStampVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;

/**
 * One Arrow cell as text, the same way whatever the source: integers without a fraction, decimals
 * as written, dates and times in ISO 8601 (timestamps with a zone in UTC, ending {@code Z}), bytes
 * as {@code 0x...} and booleans as {@code true}/{@code false}. The same key value therefore has the
 * same text, and the same hash, in every table and every scan.
 */
public final class CellText {


    private CellText() {
    }

    /** The cell's text, in full, or null for a null. */
    public static String of(FieldVector v, int row) {
        if (v.isNull(row)) {
            return null;
        }
        String s = switch (v) {
            case VarCharVector x -> new String(x.get(row), java.nio.charset.StandardCharsets.UTF_8);
            case VarBinaryVector x -> "0x" + HexFormat.of().withUpperCase().formatHex(x.get(row));
            case BitVector x -> x.get(row) == 1 ? "true" : "false";
            case DecimalVector x -> x.getObject(row).toPlainString();
            case DateDayVector x -> LocalDate.ofEpochDay(x.get(row)).toString();
            case TimeMicroVector x -> LocalTime.ofNanoOfDay(x.get(row) * 1_000L).toString();
            case TimeStampMicroTZVector x -> micros(x.get(row)).toString();
            case TimeStampMicroVector x -> LocalDateTime.ofInstant(micros(x.get(row)), ZoneOffset.UTC).toString();
            case TimeStampVector x -> String.valueOf(x.get(row));
            default -> String.valueOf(v.getObject(row));
        };
        return s;
    }

    /** {@code s} cut to at most {@code max} characters, ending with an ellipsis when cut; null stays null. */
    public static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static Instant micros(long n) {
        return Instant.ofEpochSecond(Math.floorDiv(n, 1_000_000), Math.floorMod(n, 1_000_000) * 1_000L);
    }
}

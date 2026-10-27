package io.kilnmark.kiln.plugins.write;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.HexFormat;

/**
 * Values as text, for CSV files and for text columns that receive other kinds. ISO 8601 throughout,
 * so Kilnmark's own readers type them again: dates {@code 2025-01-31}, times {@code 09:30:00} with
 * a fraction only when there is one, timestamps {@code 2025-01-31 09:30:00.123456} and, in UTC,
 * {@code 2025-01-31 09:30:00Z}; decimals in plain notation; bytes as {@code 0xCAFE}.
 */
public final class TextForm {

    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    private TextForm() {
    }

    /** {@code value} (as {@link WriteColumn#value} gives it) as text; null stays null. */
    public static String of(Object value) {
        return switch (value) {
            case null -> null;
            case String s -> s;
            case BigDecimal d -> d.toPlainString();
            case byte[] b -> "0x" + HEX.formatHex(b);
            case LocalDate d -> date(d);
            case LocalTime t -> time(t);
            case LocalDateTime t -> date(t.toLocalDate()) + " " + time(t.toLocalTime());
            case Instant i -> {
                LocalDateTime t = LocalDateTime.ofInstant(i, ZoneOffset.UTC);
                yield date(t.toLocalDate()) + " " + time(t.toLocalTime()) + "Z";
            }
            default -> value.toString(); // booleans, integers, floats
        };
    }

    /** {@code 2025-01-31}; years before 1 and after 9999 as ISO 8601 writes them ({@code -0044}, {@code +10000}). */
    public static String date(LocalDate d) {
        return d.toString();
    }

    /** {@code 09:30:00}, with a fraction only when there is one (at most six digits). */
    public static String time(LocalTime t) {
        StringBuilder b = new StringBuilder(15);
        two(b, t.getHour()).append(':');
        two(b, t.getMinute()).append(':');
        two(b, t.getSecond());
        int micros = t.getNano() / 1_000;
        if (micros > 0) {
            String f = String.format("%06d", micros);
            int end = f.length();
            while (f.charAt(end - 1) == '0') {
                end--;
            }
            b.append('.').append(f, 0, end);
        }
        return b.toString();
    }

    private static StringBuilder two(StringBuilder b, int n) {
        return b.append((char) ('0' + n / 10)).append((char) ('0' + n % 10));
    }
}

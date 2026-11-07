package io.kilnmark.kiln.nodes.expr;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Set;

/**
 * Dates (days since 1970-01-01) and timestamps (microseconds since 1970-01-01T00:00Z). Every
 * timestamp is UTC: text without an offset is read as UTC, and timestamps are shown in UTC.
 */
final class Temporal {

    static final long MICROS_PER_DAY = 86_400_000_000L;
    /** Text longer than this is never a date or time, so it is not parsed. */
    private static final int MAX_TEXT = 100;

    static final Set<String> TRUNC_UNITS = Set.of("year", "quarter", "month", "week", "day", "hour", "minute", "second");
    static final Set<String> PART_UNITS = Set.of("year", "quarter", "month", "week", "day", "day_of_week", "dow",
        "day_of_year", "doy", "hour", "minute", "second");
    static final Set<String> TIME_UNITS = Set.of("hour", "minute", "second");

    /** The first and last days expressions accept: years -9999 to 9999, so every date is also a timestamp. */
    static final long MIN_DAY = LocalDate.of(-9999, 1, 1).toEpochDay();
    static final long MAX_DAY = LocalDate.of(9999, 12, 31).toEpochDay();

    private Temporal() {
    }

    static boolean inRange(long days) {
        return days >= MIN_DAY && days <= MAX_DAY;
    }

    static Long ranged(Long days) {
        return days == null || !inRange(days) ? null : days;
    }

    static Long rangedMicros(Long micros) {
        return micros == null || !inRange(Math.floorDiv(micros, MICROS_PER_DAY)) ? null : micros;
    }

    /** {@code yyyy-mm-dd} as days, or null. */
    static Long parseDateLiteral(String text) {
        String s = text.trim();
        if (s.length() > MAX_TEXT) {
            return null;
        }
        try {
            return ranged(LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE).toEpochDay());
        } catch (DateTimeException e) {
            return null;
        }
    }

    /**
     * A timestamp as microseconds, or null: {@code yyyy-mm-dd}, optionally followed by a space or
     * {@code T}, {@code hh:mm[:ss[.fraction]]}, and an offset ({@code Z}, {@code +01:00}). Without an
     * offset the time is UTC.
     */
    static Long parseTimestamp(String text) {
        String s = text.trim();
        if (s.length() > MAX_TEXT || s.length() < 10) {
            return null;
        }
        if (s.length() == 10) {
            Long days = parseDateLiteral(s);
            return days == null ? null : days * MICROS_PER_DAY; // in range, so no overflow
        }
        if (s.charAt(10) == ' ') {
            s = s.substring(0, 10) + 'T' + s.substring(11);
        }
        try {
            TemporalAccessor t = DateTimeFormatter.ISO_DATE_TIME.parse(s);
            if (t.isSupported(ChronoField.OFFSET_SECONDS)) {
                return rangedMicros(micros(ZonedDateTime.from(t).toInstant()));
            }
            return rangedMicros(micros(LocalDateTime.from(t).toInstant(ZoneOffset.UTC)));
        } catch (DateTimeException | ArithmeticException e) {
            return null;
        }
    }

    static long micros(Instant i) {
        return Math.addExact(Math.multiplyExact(i.getEpochSecond(), 1_000_000L), i.getNano() / 1_000L);
    }

    static LocalDateTime dateTime(long micros) {
        long seconds = Math.floorDiv(micros, 1_000_000L);
        int nanos = (int) Math.floorMod(micros, 1_000_000L) * 1_000;
        return LocalDateTime.ofEpochSecond(seconds, nanos, ZoneOffset.UTC);
    }

    static long micros(LocalDateTime t) {
        return micros(t.toInstant(ZoneOffset.UTC));
    }

    static LocalDate date(long days) {
        return LocalDate.ofEpochDay(days);
    }

    /** {@code yyyy-mm-dd}. */
    static String dateText(long days) {
        return date(days).toString();
    }

    /** {@code yyyy-mm-dd hh:mm:ss}, with the fraction of a second only when there is one. */
    static String timestampText(long micros) {
        LocalDateTime t = dateTime(micros);
        StringBuilder sb = new StringBuilder(t.toLocalDate().toString()).append(' ');
        sb.append(String.format(Locale.ROOT, "%02d:%02d:%02d", t.getHour(), t.getMinute(), t.getSecond()));
        int us = t.getNano() / 1_000;
        if (us != 0) {
            String f = String.format(Locale.ROOT, "%06d", us);
            int end = f.length();
            while (f.charAt(end - 1) == '0') {
                end--;
            }
            sb.append('.').append(f, 0, end);
        }
        return sb.toString();
    }

    /** The start of the {@code unit} holding this day; {@code unit} is year to day. */
    static long truncDate(String unit, long days) {
        LocalDate d = date(days);
        LocalDate t = switch (unit) {
            case "year" -> d.withDayOfYear(1);
            case "quarter" -> d.withDayOfMonth(1).withMonth(((d.getMonthValue() - 1) / 3) * 3 + 1);
            case "month" -> d.withDayOfMonth(1);
            case "week" -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            default -> d;
        };
        return t.toEpochDay();
    }

    /** The start of the {@code unit} holding this instant. */
    static long truncTimestamp(String unit, long micros) {
        LocalDateTime t = dateTime(micros);
        LocalDateTime r = switch (unit) {
            case "hour" -> t.truncatedTo(ChronoUnit.HOURS);
            case "minute" -> t.truncatedTo(ChronoUnit.MINUTES);
            case "second" -> t.truncatedTo(ChronoUnit.SECONDS);
            default -> LocalDateTime.of(date(truncDate(unit, t.toLocalDate().toEpochDay())), LocalTime.MIDNIGHT);
        };
        return micros(r);
    }

    /** The units {@code date_add} and {@code date_diff} take. */
    static final Set<String> STEP_UNITS = Set.of("year", "quarter", "month", "week", "day", "hour", "minute", "second");

    private static ChronoUnit chrono(String unit) {
        return switch (unit) {
            case "year" -> ChronoUnit.YEARS;
            case "month", "quarter" -> ChronoUnit.MONTHS;
            case "week" -> ChronoUnit.WEEKS;
            case "day" -> ChronoUnit.DAYS;
            case "hour" -> ChronoUnit.HOURS;
            case "minute" -> ChronoUnit.MINUTES;
            default -> ChronoUnit.SECONDS;
        };
    }

    /**
     * A day moved by {@code n} of {@code unit} (year to day): a month or year on from 31 January is the
     * last day of February, as a calendar has it.
     */
    static long addDate(String unit, long days, long n, int pos) throws ExprException {
        long steps = unit.equals("quarter") ? Math.multiplyExact(n, 3L) : n;
        long out = date(days).plus(steps, chrono(unit)).toEpochDay();
        if (!inRange(out)) {
            throw new ExprException("a date is out of range (years -9999 to 9999)", pos);
        }
        return out;
    }

    /** An instant moved by {@code n} of {@code unit}, in UTC. */
    static long addTimestamp(String unit, long micros, long n, int pos) throws ExprException {
        long steps = unit.equals("quarter") ? Math.multiplyExact(n, 3L) : n;
        long out = micros(dateTime(micros).plus(steps, chrono(unit)));
        if (rangedMicros(out) == null) {
            throw new ExprException("a timestamp is out of range (years -9999 to 9999)", pos);
        }
        return out;
    }

    /** Whole units from day {@code a} to day {@code b}: negative when {@code b} is earlier. */
    static long diffDate(String unit, long a, long b) {
        long n = chrono(unit).between(date(a), date(b));
        return unit.equals("quarter") ? n / 3 : n;
    }

    /** Whole units from instant {@code a} to instant {@code b}, in UTC: negative when {@code b} is earlier. */
    static long diffTimestamp(String unit, long a, long b) {
        long n = chrono(unit).between(dateTime(a), dateTime(b));
        return unit.equals("quarter") ? n / 3 : n;
    }

    /** One part of a day; weeks are ISO weeks, day_of_week runs from 1 (Monday) to 7 (Sunday). */
    static long partDate(String unit, long days) {
        LocalDate d = date(days);
        return switch (unit) {
            case "year" -> d.getYear();
            case "quarter" -> (d.getMonthValue() - 1) / 3 + 1;
            case "month" -> d.getMonthValue();
            case "week" -> d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            case "day" -> d.getDayOfMonth();
            case "day_of_week", "dow" -> d.getDayOfWeek().getValue();
            case "day_of_year", "doy" -> d.getDayOfYear();
            default -> 0;
        };
    }

    static long partTimestamp(String unit, long micros) {
        LocalDateTime t = dateTime(micros);
        return switch (unit) {
            case "hour" -> t.getHour();
            case "minute" -> t.getMinute();
            case "second" -> t.getSecond();
            default -> partDate(unit, t.toLocalDate().toEpochDay());
        };
    }

    /**
     * A date and time pattern ({@code yyyy-MM-dd HH:mm:ss} and the other letters of
     * {@link DateTimeFormatter}), read strictly so 31 February is rejected rather than moved.
     *
     * @throws IllegalArgumentException if the pattern is not valid
     */
    static DateTimeFormatter pattern(String pattern) {
        // Strict reading needs 'u' (year) rather than 'y' (year of era); people write 'y'.
        StringBuilder sb = new StringBuilder(pattern.length());
        boolean quoted = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            }
            sb.append(!quoted && c == 'y' ? 'u' : c);
        }
        return DateTimeFormatter.ofPattern(sb.toString(), Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT);
    }

    /** Text read with a pattern as days, or null when it does not match. */
    static Long parseDate(String text, DateTimeFormatter f) {
        if (text.length() > MAX_TEXT) {
            return null;
        }
        try {
            if (f == null) {
                return parseDateLiteral(text);
            }
            return ranged(LocalDate.from(f.parse(text.trim())).toEpochDay());
        } catch (DateTimeException e) {
            return null;
        }
    }

    /** Text read with a pattern as microseconds, or null when it does not match. Without an offset, UTC. */
    static Long parseTimestamp(String text, DateTimeFormatter f) {
        if (f == null) {
            return parseTimestamp(text);
        }
        if (text.length() > MAX_TEXT) {
            return null;
        }
        try {
            TemporalAccessor t = f.parse(text.trim());
            if (t.isSupported(ChronoField.OFFSET_SECONDS)) {
                return rangedMicros(micros(ZonedDateTime.from(t).toInstant()));
            }
            if (t.isSupported(ChronoField.HOUR_OF_DAY)) {
                return rangedMicros(micros(LocalDateTime.from(t)));
            }
            Long days = ranged(LocalDate.from(t).toEpochDay());
            return days == null ? null : days * MICROS_PER_DAY;
        } catch (DateTimeException | ArithmeticException e) {
            return null;
        }
    }

    /** A date or timestamp (UTC) as text in a pattern. */
    static String format(Type.Kind kind, long value, DateTimeFormatter f) throws ExprException {
        try {
            if (kind == Type.Kind.DATE) {
                return f.format(date(value));
            }
            return f.format(dateTime(value).atZone(ZoneOffset.UTC));
        } catch (DateTimeException e) {
            throw new ExprException("format_date: the pattern asks for something a "
                + (kind == Type.Kind.DATE ? "date" : "timestamp") + " does not have (" + e.getMessage() + ")", 0);
        }
    }
}

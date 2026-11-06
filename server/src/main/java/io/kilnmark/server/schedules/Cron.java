package io.kilnmark.server.schedules;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A five-field Unix cron expression (minute, hour, day of the month, month, day of the week), as
 * schedules use it (ADR 0046, docs/design/schedules.md). Pure: no clock, no database.
 *
 * <p>Each field takes {@code *}, a number, a range {@code a-b}, a step {@code *}{@code /n},
 * {@code a-b/n} or {@code a/n} (from a to the end), and lists of these joined by commas. Months
 * take {@code JAN} to {@code DEC} and days {@code SUN} to {@code SAT}, in any case; 0 and 7 are
 * both Sunday. {@code @hourly}, {@code @daily} (or {@code @midnight}), {@code @weekly},
 * {@code @monthly} and {@code @yearly} (or {@code @annually}) stand for their usual fields. There is
 * no seconds field and no {@code @reboot}.
 *
 * <p>The two day fields follow Vixie cron: when both are restricted (neither starts with
 * {@code *}), a day matching either fires; otherwise a day must match both.
 *
 * <p>Times are read in the schedule's zone. A local time that does not exist (the clocks go
 * forward) fires at the same time moved forward by the gap, as {@link ZonedDateTime#ofLocal} does;
 * a local time that happens twice (the clocks go back) fires once, at the first.
 */
public final class Cron {

    /** How far ahead {@link #next} looks before saying an expression never fires: ten years of days. */
    static final int SEARCH_DAYS = 3_660;

    /** The longest expression read. */
    static final int MAX_LENGTH = 200;

    private static final Map<String, String> MACROS = Map.of(
        "@hourly", "0 * * * *",
        "@daily", "0 0 * * *",
        "@midnight", "0 0 * * *",
        "@weekly", "0 0 * * 0",
        "@monthly", "0 0 1 * *",
        "@yearly", "0 0 1 1 *",
        "@annually", "0 0 1 1 *");

    private static final List<String> MONTHS = List.of("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP",
        "OCT", "NOV", "DEC");
    private static final List<String> DAYS = List.of("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT");

    private final String text;
    private final BitSet minutes;
    private final BitSet hours;
    private final BitSet days;
    private final BitSet months;
    private final BitSet weekdays;
    private final boolean daysStar;
    private final boolean weekdaysStar;

    private Cron(String text, String[] f) {
        this.text = text;
        this.minutes = field(f[0], "minute", 0, 59, null);
        this.hours = field(f[1], "hour", 0, 23, null);
        this.days = field(f[2], "day of the month", 1, 31, null);
        this.months = field(f[3], "month", 1, 12, MONTHS);
        BitSet w = field(f[4], "day of the week", 0, 7, DAYS);
        if (w.get(7)) {
            w.set(0);
            w.clear(7);
        }
        this.weekdays = w;
        this.daysStar = f[2].startsWith("*");
        this.weekdaysStar = f[4].startsWith("*");
    }

    /**
     * Reads an expression.
     *
     * @throws IllegalArgumentException saying what is wrong, in words, starting {@code cron:}
     */
    public static Cron parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("cron: give five fields (minute hour day month weekday), such as "
                + "0 2 * * * for 02:00 every day");
        }
        String t = text.strip();
        if (t.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("cron: at most " + MAX_LENGTH + " characters");
        }
        String fields = t;
        if (t.startsWith("@")) {
            String m = t.toLowerCase(Locale.ROOT);
            if (m.equals("@reboot")) {
                throw new IllegalArgumentException("cron: @reboot is not supported; a schedule fires at set times");
            }
            fields = MACROS.get(m);
            if (fields == null) {
                throw new IllegalArgumentException("cron: use @hourly, @daily, @weekly, @monthly or @yearly, or five "
                    + "fields");
            }
        }
        String[] f = fields.split("\\s+");
        if (f.length == 6) {
            throw new IllegalArgumentException("cron: six fields given; Kilnmark's cron has five (minute hour day "
                + "month weekday), with no seconds");
        }
        if (f.length != 5) {
            throw new IllegalArgumentException("cron: give five fields (minute hour day month weekday); " + f.length
                + (f.length == 1 ? " was" : " were") + " given");
        }
        return new Cron(t, f);
    }

    /** The expression as given, trimmed. */
    public String text() {
        return text;
    }

    @Override
    public String toString() {
        return text;
    }

    /**
     * The first time this fires strictly after {@code after}, read in {@code zone}; null when it does
     * not fire in the next ten years ({@value #SEARCH_DAYS} days), such as {@code 0 0 30 2 *}.
     */
    public Instant next(Instant after, ZoneId zone) {
        // Start a day early: a time in a gap the day before can move forward past midnight.
        LocalDate day = LocalDateTime.ofInstant(after, zone).toLocalDate().minusDays(1);
        for (int i = 0; i <= SEARCH_DAYS; i++, day = day.plusDays(1)) {
            Instant first = firstOn(day, after, zone);
            if (first != null) {
                // A gap can move a time on one day past the next day's earliest: take the earlier.
                Instant next = firstOn(day.plusDays(1), after, zone);
                return next != null && next.isBefore(first) ? next : first;
            }
        }
        return null;
    }

    /** The earliest firing on local {@code day} that falls after {@code after}, or null. */
    private Instant firstOn(LocalDate day, Instant after, ZoneId zone) {
        if (!dayMatches(day)) {
            return null;
        }
        Instant best = null;
        for (int h = hours.nextSetBit(0); h >= 0; h = hours.nextSetBit(h + 1)) {
            for (int m = minutes.nextSetBit(0); m >= 0; m = minutes.nextSetBit(m + 1)) {
                // ofLocal with no preferred offset: a gap moves forward by its length, an overlap
                // takes the earlier offset, so a time that happens twice is only ever the first.
                Instant at = ZonedDateTime.ofLocal(LocalDateTime.of(day, LocalTime.of(h, m)), zone, null).toInstant();
                if (at.isAfter(after) && (best == null || at.isBefore(best))) {
                    best = at;
                }
            }
        }
        return best;
    }

    private boolean dayMatches(LocalDate day) {
        if (!months.get(day.getMonthValue())) {
            return false;
        }
        boolean dom = days.get(day.getDayOfMonth());
        boolean dow = weekdays.get(day.getDayOfWeek() == DayOfWeek.SUNDAY ? 0 : day.getDayOfWeek().getValue());
        return (daysStar || weekdaysStar) ? (dom && dow) : (dom || dow);
    }

    private static BitSet field(String text, String what, int min, int max, List<String> names) {
        BitSet out = new BitSet(max + 1);
        for (String item : text.split(",", -1)) {
            if (item.isEmpty()) {
                throw new IllegalArgumentException("cron: the " + what + " field has an empty item in its list");
            }
            int step = 1;
            String range = item;
            int slash = item.indexOf('/');
            if (slash >= 0) {
                range = item.substring(0, slash);
                step = number(item.substring(slash + 1), what, "step");
                if (step < 1 || step > max - min + 1) {
                    throw new IllegalArgumentException("cron: the " + what + " field's step must be 1 to "
                        + (max - min + 1));
                }
            }
            int from;
            int to;
            if (range.equals("*")) {
                from = min;
                to = max == 7 ? 6 : max; // * in the day of the week is 0-6: 7 is Sunday again
            } else {
                int dash = range.indexOf('-');
                if (dash > 0) {
                    from = value(range.substring(0, dash), what, min, max, names);
                    to = value(range.substring(dash + 1), what, min, max, names);
                    if (to < from) {
                        throw new IllegalArgumentException("cron: the " + what + " field's range " + clip(range)
                            + " runs backwards; write it as two ranges joined by a comma");
                    }
                } else {
                    from = value(range, what, min, max, names);
                    to = slash >= 0 ? (max == 7 ? 6 : max) : from;
                }
            }
            for (int v = from; v <= to; v += step) {
                out.set(v);
            }
        }
        return out;
    }

    private static int value(String text, String what, int min, int max, List<String> names) {
        if (names != null) {
            int i = names.indexOf(text.toUpperCase(Locale.ROOT));
            if (i >= 0) {
                return names.size() == 12 ? i + 1 : i; // months from 1, days from 0
            }
        }
        int v = number(text, what, "value");
        if (v < min || v > max) {
            throw new IllegalArgumentException("cron: the " + what + " field takes " + min + " to " + max
                + (names == null ? "" : names.size() == 12 ? ", or JAN to DEC" : ", or SUN to SAT (0 and 7 are Sunday)")
                + "; " + clip(text) + " is out of range");
        }
        return v;
    }

    private static int number(String text, String what, String part) {
        if (text.isEmpty() || text.length() > 4 || !text.chars().allMatch(ch -> ch >= '0' && ch <= '9')) {
            throw new IllegalArgumentException("cron: the " + what + " field has " + clip(text) + " where a " + part
                + " should be");
        }
        return Integer.parseInt(text);
    }

    /** A piece of what was typed, short enough to quote back. */
    private static String clip(String text) {
        String t = text.isEmpty() ? "nothing" : "'" + text.replaceAll("\\p{Cntrl}", "?") + "'";
        return t.length() > 22 ? t.substring(0, 20) + "...'" : t;
    }
}

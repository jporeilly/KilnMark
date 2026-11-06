package io.kilnmark.server.schedules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Five-field cron (ADR 0046): parsing, the next fire time, both day fields, and daylight saving. */
class CronTest {

    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    /** The next fire after a UTC time written as text, as text in UTC. */
    private static String next(String cron, String after) {
        return next(cron, after, UTC);
    }

    private static String next(String cron, String after, ZoneId zone) {
        Instant at = Cron.parse(cron).next(LocalDateTime.parse(after).atZone(zone).toInstant(), zone);
        return at == null ? null : LocalDateTime.ofInstant(at, zone).toString();
    }

    /** The next {@code n} fires after {@code after} (local to {@code zone}), as instants. */
    private static List<Instant> fires(String cron, String after, ZoneId zone, int n) {
        Cron c = Cron.parse(cron);
        Instant at = LocalDateTime.parse(after).atZone(zone).toInstant();
        List<Instant> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            at = c.next(at, zone);
            out.add(at);
        }
        return out;
    }

    private static Instant utc(String text) {
        return Instant.parse(text);
    }

    private static String refused(String cron) {
        return assertThrows(IllegalArgumentException.class, () -> Cron.parse(cron)).getMessage();
    }

    @Test
    void everyMinuteIsStrictlyAfter() {
        assertEquals("2026-11-06T10:01", next("* * * * *", "2026-11-06T10:00"));
        assertEquals("2026-11-06T10:01", next("* * * * *", "2026-11-06T10:00:30"));
        assertEquals("2026-11-07T00:00", next("* * * * *", "2026-11-06T23:59"));
    }

    @Test
    void fixedTimes() {
        assertEquals("2026-11-07T02:00", next("0 2 * * *", "2026-11-06T02:00"));
        assertEquals("2026-11-06T02:00", next("0 2 * * *", "2026-11-06T01:59:59"));
        assertEquals("2027-01-01T00:00", next("0 0 1 1 *", "2026-11-06T10:00"));
        assertEquals("2026-11-06T10:15", next("15 * * * *", "2026-11-06T10:00"));
        assertEquals("2026-11-06T11:15", next("15 * * * *", "2026-11-06T10:15"));
    }

    @Test
    void listsRangesAndSteps() {
        assertEquals("2026-11-06T10:30", next("0,30 * * * *", "2026-11-06T10:00"));
        assertEquals("2026-11-06T10:15", next("*/15 * * * *", "2026-11-06T10:00"));
        assertEquals("2026-11-06T10:05", next("5/20 * * * *", "2026-11-06T10:00"));
        assertEquals("2026-11-06T10:25", next("5/20 * * * *", "2026-11-06T10:05"));
        assertEquals("2026-11-06T11:05", next("5/20 * * * *", "2026-11-06T10:45"));
        assertEquals("2026-11-06T09:00", next("0 9-17/4 * * *", "2026-11-06T08:00"));
        assertEquals("2026-11-06T13:00", next("0 9-17/4 * * *", "2026-11-06T09:00"));
        assertEquals("2026-11-06T17:00", next("0 9-17/4 * * *", "2026-11-06T13:00"));
        assertEquals("2026-11-07T09:00", next("0 9-17/4 * * *", "2026-11-06T17:00"));
        assertEquals("2026-11-06T12:10", next("10-12,40 12 * * *", "2026-11-06T11:00"));
    }

    @Test
    void namesForMonthsAndDays() {
        // 6 November 2026 is a Friday
        assertEquals("2026-11-09T08:00", next("0 8 * * MON", "2026-11-06T10:00"));
        assertEquals("2026-11-09T08:00", next("0 8 * * mon-fri", "2026-11-06T10:00"));
        assertEquals("2027-01-01T00:00", next("0 0 1 jan *", "2026-11-06T10:00"));
        assertEquals("2027-03-01T00:00", next("0 0 1 MAR-MAY *", "2026-11-06T10:00"));
    }

    @Test
    void stepMonthsStartAtJanuary() {
        assertEquals("2027-01-01T00:00", next("0 0 1 */3 *", "2026-11-06T10:00"));
    }

    @Test
    void sundayIsZeroAndSeven() {
        assertEquals("2026-11-08T00:00", next("0 0 * * 0", "2026-11-06T10:00"));
        assertEquals("2026-11-08T00:00", next("0 0 * * 7", "2026-11-06T10:00"));
        assertEquals("2026-11-08T00:00", next("0 0 * * SUN", "2026-11-06T10:00"));
        assertEquals("2026-11-07T00:00", next("0 0 * * 6-7", "2026-11-06T10:00"));
        assertEquals("2026-11-08T00:00", next("0 0 * * 6-7", "2026-11-07T00:00"));
        assertEquals("2026-11-14T00:00", next("0 0 * * 6-7", "2026-11-08T00:00"));
    }

    @Test
    void bothDayFieldsRestrictedMeansEither() {
        // the 13th, or any Friday: from Saturday 7 November, Friday the 13th; from the 14th, Friday 20th
        assertEquals("2026-11-13T00:00", next("0 0 13 * FRI", "2026-11-07T00:00"));
        assertEquals("2026-11-20T00:00", next("0 0 13 * FRI", "2026-11-14T00:00"));
        // the 1st, or Monday: Monday 9 November comes before 1 December
        assertEquals("2026-11-09T00:00", next("0 0 1 * MON", "2026-11-06T10:00"));
        assertEquals("2026-11-16T00:00", next("0 0 1 * MON", "2026-11-09T00:00"));
        assertEquals("2026-12-01T00:00", next("0 0 1 * MON", "2026-11-30T00:00"));
    }

    @Test
    void oneDayFieldStarredMeansBoth() {
        assertEquals("2026-12-01T00:00", next("0 0 1 * *", "2026-11-06T10:00"));
        assertEquals("2026-11-09T00:00", next("0 0 * * MON", "2026-11-06T10:00"));
        // Vixie: a field starting with * counts as unrestricted, so a step on the days is ANDed
        // with the weekday: odd days (*/2 from 1) that are Mondays. 9 November is odd and a Monday.
        assertEquals("2026-11-09T00:00", next("0 0 */2 * MON", "2026-11-06T10:00"));
        // 16 November is a Monday but even; 23 November is odd and a Monday
        assertEquals("2026-11-23T00:00", next("0 0 */2 * MON", "2026-11-09T00:00"));
    }

    @Test
    void macros() {
        assertEquals("2026-11-06T11:00", next("@hourly", "2026-11-06T10:00"));
        assertEquals("2026-11-07T00:00", next("@daily", "2026-11-06T10:00"));
        assertEquals("2026-11-07T00:00", next("@midnight", "2026-11-06T10:00"));
        assertEquals("2026-11-08T00:00", next("@weekly", "2026-11-06T10:00"));
        assertEquals("2026-12-01T00:00", next("@monthly", "2026-11-06T10:00"));
        assertEquals("2027-01-01T00:00", next("@yearly", "2026-11-06T10:00"));
        assertEquals("2027-01-01T00:00", next("@ANNUALLY", "2026-11-06T10:00"));
        assertEquals("@daily", Cron.parse("  @daily ").text());
    }

    @Test
    void dayThirtyOneSkipsShortMonths() {
        assertEquals("2026-12-31T00:00", next("0 0 31 * *", "2026-11-06T10:00"));
        assertEquals("2027-01-31T00:00", next("0 0 31 * *", "2026-12-31T00:00"));
        assertEquals("2027-03-31T00:00", next("0 0 31 * *", "2027-01-31T00:00"));
        assertEquals("2027-05-31T00:00", next("0 0 31 * *", "2027-03-31T00:00"));
        assertEquals("2026-11-30T00:00", next("0 0 30 * *", "2026-11-06T10:00"));
        assertEquals("2027-03-30T00:00", next("0 0 30 * *", "2027-01-30T00:00"));
    }

    @Test
    void februaryTwentyNinthWaitsForALeapYear() {
        assertEquals("2028-02-29T00:00", next("0 0 29 2 *", "2026-11-06T10:00"));
        assertEquals("2032-02-29T00:00", next("0 0 29 2 *", "2028-02-29T00:00"));
        // 2100 is not a leap year: from 2096 the next is 2104, eight years on, inside the search
        assertEquals("2104-02-29T00:00", next("0 0 29 2 *", "2096-02-29T00:00"));
    }

    @Test
    void anImpossibleExpressionNeverFiresAndDoesNotLoop() {
        long start = System.nanoTime();
        assertNull(next("0 0 30 2 *", "2026-11-06T10:00"));
        assertNull(next("0 0 31 4,6,9,11 *", "2026-11-06T10:00"));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 5);
    }

    @Test
    void londonSpringForwardMovesAMissingTimeOnByTheGap() {
        // 28 March 2027: at 01:00 GMT the clocks go to 02:00 BST, so 01:30 does not exist
        Instant at = Cron.parse("30 1 * * *").next(utc("2027-03-27T12:00:00Z"), LONDON);
        assertEquals(ZonedDateTime.parse("2027-03-28T02:30+01:00[Europe/London]").toInstant(), at);
        assertEquals(utc("2027-03-28T01:30:00Z"), at);
        // and the day after, 01:30 BST as usual
        assertEquals(utc("2027-03-29T00:30:00Z"), Cron.parse("30 1 * * *").next(at, LONDON));
    }

    @Test
    void londonSpringForwardFiresOnceWhenTwoTimesMeet() {
        // 01:30 (missing, moved to 02:30) and 02:30 are the same instant: one fire, not two
        List<Instant> f = fires("30 1,2 * * *", "2027-03-27T12:00", LONDON, 3);
        assertEquals(List.of(utc("2027-03-28T01:30:00Z"), utc("2027-03-29T00:30:00Z"), utc("2027-03-29T01:30:00Z")), f);
    }

    @Test
    void londonFallBackFiresOnceAtTheFirst() {
        // 31 October 2027: at 02:00 BST the clocks go back to 01:00 GMT, so 01:30 happens twice
        List<Instant> f = fires("30 1 * * *", "2027-10-30T12:00", LONDON, 2);
        assertEquals(utc("2027-10-31T00:30:00Z"), f.get(0)); // 01:30 BST, the first
        assertEquals(utc("2027-11-01T01:30:00Z"), f.get(1)); // the next day, 01:30 GMT
    }

    @Test
    void londonFallBackEveryQuarterHourSkipsTheRepeatedHour() {
        List<Instant> f = fires("*/15 1 * * *", "2027-10-31T00:50", LONDON, 5);
        assertEquals(List.of(utc("2027-10-31T00:00:00Z"), utc("2027-10-31T00:15:00Z"), utc("2027-10-31T00:30:00Z"),
            utc("2027-10-31T00:45:00Z"), utc("2027-11-01T01:00:00Z")), f);
    }

    @Test
    void newYorkSpringForward() {
        // 14 March 2027: 02:00 EST becomes 03:00 EDT; 02:30 does not exist and fires at 03:30 EDT
        Instant at = Cron.parse("30 2 * * *").next(utc("2027-03-13T12:00:00Z"), NEW_YORK);
        assertEquals(ZonedDateTime.parse("2027-03-14T03:30-04:00[America/New_York]").toInstant(), at);
        // a time after the gap is unchanged
        assertEquals(ZonedDateTime.parse("2027-03-14T03:00-04:00[America/New_York]").toInstant(),
            Cron.parse("0 3 * * *").next(utc("2027-03-13T12:00:00Z"), NEW_YORK));
    }

    @Test
    void newYorkFallBack() {
        // 7 November 2027: 02:00 EDT becomes 01:00 EST; 01:30 happens twice and fires at the first
        List<Instant> f = fires("30 1 * * *", "2027-11-06T12:00", NEW_YORK, 2);
        assertEquals(ZonedDateTime.parse("2027-11-07T01:30-04:00[America/New_York]").toInstant(), f.get(0));
        assertEquals(ZonedDateTime.parse("2027-11-08T01:30-05:00[America/New_York]").toInstant(), f.get(1));
        // hourly runs through the change without firing 01:00 twice
        List<Instant> h = fires("0 * * * *", "2027-11-07T00:30", NEW_YORK, 3);
        assertEquals(List.of(utc("2027-11-07T05:00:00Z"), utc("2027-11-07T07:00:00Z"), utc("2027-11-07T08:00:00Z")), h);
    }

    @Test
    void zoneChangesTheInstant() {
        Instant after = utc("2026-11-06T00:00:00Z");
        assertEquals(utc("2026-11-06T02:00:00Z"), Cron.parse("0 2 * * *").next(after, UTC));
        assertEquals(utc("2026-11-06T07:00:00Z"), Cron.parse("0 2 * * *").next(after, NEW_YORK));
    }

    @Test
    void refusesWhatItCannotRead() {
        assertTrue(refused(null).startsWith("cron: give five fields"));
        assertTrue(refused("  ").startsWith("cron: give five fields"));
        assertTrue(refused("0 0 * *").contains("4 were given"));
        assertTrue(refused("0 0 0 * * *").contains("no seconds"));
        assertTrue(refused("@reboot").contains("@reboot is not supported"));
        assertTrue(refused("@sometimes").contains("@hourly"));
        assertTrue(refused("60 * * * *").contains("minute field takes 0 to 59"));
        assertTrue(refused("* 24 * * *").contains("hour field takes 0 to 23"));
        assertTrue(refused("* * 0 * *").contains("day of the month field takes 1 to 31"));
        assertTrue(refused("* * 32 * *").contains("1 to 31"));
        assertTrue(refused("* * * 13 *").contains("JAN to DEC"));
        assertTrue(refused("* * * * 8").contains("SUN to SAT"));
        assertTrue(refused("* * * * FUN").contains("'FUN'"));
        assertTrue(refused("*/0 * * * *").contains("step must be 1 to 60"));
        assertTrue(refused("*/61 * * * *").contains("step must be 1 to 60"));
        assertTrue(refused("30-10 * * * *").contains("runs backwards"));
        assertTrue(refused("1,,2 * * * *").contains("empty item"));
        assertTrue(refused("a * * * *").contains("'a'"));
        assertTrue(refused("-1 * * * *").contains("'-1'"));
        assertTrue(refused("1-2-3 * * * *").contains("where a value should be"));
        assertTrue(refused("0 ".repeat(150)).contains("at most 200"));
    }
}

package io.kilnmark.kiln.plugins.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.TimeStampMicroTZVector;
import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.plugins.jdbc.JdbcColumn;
import io.kilnmark.kiln.plugins.jdbc.JdbcColumn.Kind;

class TypeInferenceTest {

    private static FileColumn guess(TypeGuess.Origin origin, String... values) {
        TypeGuess g = new TypeGuess();
        for (String v : values) {
            g.offer(v, origin);
        }
        return g.column("c");
    }

    private static Kind text(String... values) {
        return guess(TypeGuess.Origin.TEXT, values).kind();
    }

    @Test
    void wholeNumbersAreInt64UnlessTheyHaveLeadingZeros() {
        assertEquals(Kind.INT64, text("1", "-42", "+7", "0", "9223372036854775807"));
        assertEquals(Kind.TEXT, text("1", "007"), "identifiers with leading zeros stay text");
        assertEquals(Kind.TEXT, text("07700 900001"));
        assertEquals(Kind.DECIMAL, text("1", "9223372036854775808"), "too big for int64");
    }

    @Test
    void decimalsKeepTheLargestScaleSeen() {
        FileColumn c = guess(TypeGuess.Origin.TEXT, "11.68", "235.9", "6", "-0.125");
        assertEquals(Kind.DECIMAL, c.kind());
        assertEquals(3, c.scale());
        assertEquals(38, c.precision());
        assertEquals("decimal(38,3)", c.sourceType());
    }

    @Test
    void scientificNotationAndHugeScalesAreFloat64() {
        assertEquals(Kind.FLOAT64, text("1.5e3", "2"));
        assertEquals(Kind.FLOAT64, text("0.1234567890123456789"));
    }

    @Test
    void booleansDatesTimesAndTimestamps() {
        assertEquals(Kind.BOOLEAN, text("true", "FALSE", "t", "f"));
        assertEquals(Kind.DATE, text("1968-01-09", "2000-02-29"));
        assertEquals(Kind.TEXT, text("2001-02-29"), "not a real date");
        assertEquals(Kind.TIME, text("09:56", "23:59:59.5"));
        assertEquals(Kind.TIMESTAMP, text("2025-01-01 10:00:00", "2025-01-02T11:00", "2025-01-03"));
        assertEquals(Kind.TIMESTAMP_TZ, text("2023-03-29 11:14:08.325687+01", "2025-09-26T09:56:08Z",
            "2025-10-10T09:44:42.528453+01:00", "2025-10-10T09:44:42-0500"));
        assertEquals(Kind.TEXT, text("2025-01-01 10:00:00", "2025-01-01 10:00:00+01"), "mixed offsets");
    }

    @Test
    void blanksSayNothingAndAllBlankIsText() {
        assertEquals(Kind.INT64, text("", "  ", null, "5"));
        assertEquals(Kind.TEXT, text("", null));
        assertEquals(Kind.TEXT, text("1", "two"));
    }

    @Test
    void jsonStringsOnlyBecomeDatesAndTimes() {
        assertEquals(Kind.TEXT, guess(TypeGuess.Origin.JSON_STRING, "123").kind(), "quoted numbers stay text");
        assertEquals(Kind.TEXT, guess(TypeGuess.Origin.JSON_STRING, "true").kind());
        assertEquals(Kind.TIMESTAMP_TZ, guess(TypeGuess.Origin.JSON_STRING, "2025-09-26T09:56:08.177518+01:00").kind());
        assertEquals(Kind.INT64, guess(TypeGuess.Origin.JSON_NUMBER, "1", "2").kind());
        assertEquals(Kind.BOOLEAN, guess(TypeGuess.Origin.JSON_BOOLEAN, "true", "false").kind());
        assertEquals(Kind.TEXT, guess(TypeGuess.Origin.JSON_NESTED, "{\"a\":1}").kind());
        TypeGuess mixed = new TypeGuess();
        mixed.offer("1", TypeGuess.Origin.JSON_NUMBER);
        mixed.offer("x", TypeGuess.Origin.JSON_STRING);
        assertEquals(Kind.TEXT, mixed.column("m").kind());
    }

    @Test
    void offsetsAreReadInEveryCommonForm() {
        assertEquals(ZoneOffset.ofHours(1), TextValues.dateTime("2023-03-29 11:14:08.325687+01").offset());
        assertEquals(ZoneOffset.ofHoursMinutes(5, 30), TextValues.dateTime("2023-03-29T11:14+0530").offset());
        assertEquals(ZoneOffset.UTC, TextValues.dateTime("2023-03-29T11:14:00Z").offset());
        assertNull(TextValues.dateTime("2023-03-29T11:14:00").offset());
        assertNull(TextValues.dateTime("2023-03-29"));
        assertNull(TextValues.dateTime("2023-03-29T11:14:00+99"));
    }

    @Test
    void valuesThatDoNotParseAreNullAndCounted() {
        try (BufferAllocator a = new RootAllocator();
             BigIntVector ints = new BigIntVector("i", a);
             DecimalVector dec = new DecimalVector("d", a, 5, 2);
             TimeStampMicroTZVector ts = new TimeStampMicroTZVector("t", a, "UTC")) {
            FileColumn i = FileColumn.inferred("i", Kind.INT64, 0);
            assertTrue(TextValues.write(i, ints, 0, " 12 "));
            assertFalse(TextValues.write(i, ints, 1, "n/a"));
            assertTrue(TextValues.write(i, ints, 2, ""), "empty is null, not unparsed");
            assertEquals(12, ints.get(0));
            assertTrue(ints.isNull(1));
            assertTrue(ints.isNull(2));

            FileColumn d = new FileColumn("d", "decimal(5,2)", Kind.DECIMAL, 5, 2);
            assertTrue(TextValues.write(d, dec, 0, "1.005"));
            assertEquals(new BigDecimal("1.00"), dec.getObject(0), "rounded half-even to the column's scale");
            assertFalse(TextValues.write(d, dec, 1, "123456.7"), "too many digits");

            FileColumn t = FileColumn.inferred("t", Kind.TIMESTAMP_TZ, 0);
            assertTrue(TextValues.write(t, ts, 0, "1970-01-01 01:00:00+01"));
            assertEquals(0, ts.get(0));
            assertTrue(TextValues.write(t, ts, 1, "1970-01-02"), "a date is midnight UTC");
            assertEquals(86_400_000_000L, ts.get(1));
        }
    }

    @Test
    void arrowTypesMatchTheJdbcConnectors() {
        assertEquals(JdbcColumn.map("x", Types.BIGINT, "int8", 0, 0).arrowType(), FileColumn.arrowType(Kind.INT64, 0, 0));
        assertEquals(JdbcColumn.map("x", Types.SMALLINT, "int2", 0, 0).arrowType(), FileColumn.arrowType(Kind.INT16, 0, 0));
        assertEquals(JdbcColumn.map("x", Types.INTEGER, "int4", 0, 0).arrowType(), FileColumn.arrowType(Kind.INT32, 0, 0));
        assertEquals(JdbcColumn.map("x", Types.DOUBLE, "float8", 0, 0).arrowType(), FileColumn.arrowType(Kind.FLOAT64, 0, 0));
        assertEquals(JdbcColumn.map("x", Types.REAL, "float4", 0, 0).arrowType(), FileColumn.arrowType(Kind.FLOAT32, 0, 0));
        assertEquals(JdbcColumn.map("x", Types.NUMERIC, "numeric", 10, 2).arrowType(),
            FileColumn.arrowType(Kind.DECIMAL, 10, 2));
        assertEquals(JdbcColumn.map("x", Types.BOOLEAN, "bool", 0, 0).arrowType(), FileColumn.arrowType(Kind.BOOLEAN, 0, 0));
        assertEquals(JdbcColumn.map("x", Types.VARCHAR, "text", 0, 0).arrowType(), FileColumn.arrowType(Kind.TEXT, 0, 0));
        assertEquals(JdbcColumn.map("x", Types.VARBINARY, "bytea", 0, 0).arrowType(), FileColumn.arrowType(Kind.BINARY, 0, 0));
        assertEquals(JdbcColumn.map("x", Types.DATE, "date", 0, 0).arrowType(), FileColumn.arrowType(Kind.DATE, 0, 0));
        assertEquals(JdbcColumn.map("x", Types.TIME, "time", 0, 0).arrowType(), FileColumn.arrowType(Kind.TIME, 0, 0));
        assertEquals(JdbcColumn.map("x", Types.TIMESTAMP, "timestamp", 0, 0).arrowType(),
            FileColumn.arrowType(Kind.TIMESTAMP, 0, 0));
        assertEquals(JdbcColumn.map("x", Types.TIMESTAMP_WITH_TIMEZONE, "timestamptz", 0, 0).arrowType(),
            FileColumn.arrowType(Kind.TIMESTAMP_TZ, 0, 0));
    }

    @Test
    void formatsComeFromExtensions() {
        assertEquals(FileFormat.CSV, FileFormat.of("exports/customers_export_2025.csv"));
        assertEquals(FileFormat.JSON_LINES, FileFormat.of("raw/transactions/transactions_2025-q1.ndjson"));
        assertEquals(FileFormat.JSON_LINES, FileFormat.of("a.JSONL"));
        assertEquals(FileFormat.PARQUET, FileFormat.of("finance/invoices_2024_2025.parquet"));
        assertEquals(FileFormat.TSV, FileFormat.of("x.tsv"));
        assertEquals(FileFormat.DOCUMENT, FileFormat.of("2025-03-complaint-ref-4471.txt"));
        assertEquals(FileFormat.DOCUMENT, FileFormat.of(".csv"));
        assertEquals(FileFormat.DOCUMENT, FileFormat.of("README"));
        assertEquals(LocalDate.of(2025, 1, 1), TextValues.date("2025-01-01"));
    }
}

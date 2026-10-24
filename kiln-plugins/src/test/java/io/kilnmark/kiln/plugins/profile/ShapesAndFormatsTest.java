package io.kilnmark.kiln.plugins.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.plugins.profile.Formats.Format;

/** Value shapes, format detectors and the pattern profile of a text column. */
class ShapesAndFormatsTest {

    private static Set<Format> formats(String v) {
        int bits = Formats.detect(v);
        Set<Format> out = EnumSet.noneOf(Format.class);
        for (Format f : Format.values()) {
            if ((bits & f.bit()) != 0) {
                out.add(f);
            }
        }
        return out;
    }

    @Test
    void shapesKeepPunctuationAndClassifyLettersAndDigits() {
        assertEquals("AA9A 9AA", ValueShape.of("SW1A 1AA"));
        assertEquals("9999-99-99", ValueShape.of("2024-03-01"));
        assertEquals("Aaaaaa", ValueShape.of("Émilie"), "accented letters keep their case");
        assertEquals("aaaa@aaaaaaa.aaa", ValueShape.of("jane@example.com"));
        assertEquals("", ValueShape.of(""));
        assertEquals("  aa", ValueShape.of("  ab"));
        assertEquals("?", ValueShape.of("\u0001"));
        String longValue = "x".repeat(100);
        assertEquals("a".repeat(ValueShape.MAX) + ValueShape.CUT, ValueShape.of(longValue));
    }

    @Test
    void collapsedShapesWriteRunsOnce() {
        assertEquals("Aa+ Aa+", ValueShape.collapse(ValueShape.of("Jane Smith")));
        assertEquals("Aa+ Aa+", ValueShape.collapse(ValueShape.of("Jo Li")));
        assertEquals("A+9+A 9+A+", ValueShape.collapse("AA9A 9AA"));
        assertEquals("9+-9+-9+", ValueShape.collapse("9999-99-99"));
        assertEquals("a+", ValueShape.collapse("a"));
        assertEquals("\\+9+ 9+", ValueShape.collapse(ValueShape.of("+44 207")), "a literal plus is escaped");
    }

    @Test
    void detectsEmailsPhonesAndUkIdentifiers() {
        assertEquals(Set.of(Format.EMAIL), formats("jane.doe+news@example.co.uk"));
        assertFalse(formats("jane@").contains(Format.EMAIL));
        assertFalse(formats("@example.com").contains(Format.EMAIL));
        assertEquals(Set.of(Format.PHONE), formats("+44 20 7946 0958"));
        assertEquals(Set.of(Format.PHONE), formats("020 7946 0958"));
        assertEquals(Set.of(Format.PHONE), formats("07700900123"));
        assertTrue(formats("(555) 123-4567").contains(Format.PHONE));
        assertFalse(formats("1234567").contains(Format.PHONE), "a plain number is not a phone number");
        assertFalse(formats("01-03-2024").contains(Format.PHONE), "a date is not a phone number");
        assertEquals(Set.of(Format.UK_POSTCODE), formats("SW1A 1AA"));
        assertEquals(Set.of(Format.UK_POSTCODE), formats("m1 1ae"));
        assertEquals(Set.of(Format.UK_POSTCODE), formats("EC1A1BB"));
        assertFalse(formats("SW1A 1A").contains(Format.UK_POSTCODE));
        assertEquals(Set.of(Format.NI_NUMBER), formats("AB 12 34 56 C"));
        assertEquals(Set.of(Format.NI_NUMBER), formats("AB123456D"));
        assertFalse(formats("GB123456A").contains(Format.NI_NUMBER), "GB is not an allowed prefix");
        assertFalse(formats("AB123456E").contains(Format.NI_NUMBER), "suffix A to D only");
    }

    @Test
    void checkDigitsAreChecked() {
        assertEquals(Set.of(Format.IBAN), formats("GB82 WEST 1234 5698 7654 32"));
        assertEquals(Set.of(Format.IBAN), formats("DE89370400440532013000"));
        assertFalse(formats("GB82 WEST 1234 5698 7654 33").contains(Format.IBAN), "wrong check digits");
        assertTrue(formats("4111 1111 1111 1111").contains(Format.CARD_NUMBER));
        assertTrue(formats("5500-0000-0000-0004").contains(Format.CARD_NUMBER));
        assertFalse(formats("4111 1111 1111 1112").contains(Format.CARD_NUMBER), "fails Luhn");
        assertFalse(formats("1111111111111117").contains(Format.CARD_NUMBER), "no card network starts with 1");
    }

    @Test
    void detectsDatesNumbersBooleansJsonAndPadding() {
        assertEquals(Set.of(Format.ISO_DATE), formats("2024-03-01"));
        assertFalse(formats("2024-02-30").contains(Format.ISO_DATE), "no such day");
        assertEquals(Set.of(Format.ISO_TIMESTAMP), formats("2024-03-01T10:15:00Z"));
        assertEquals(Set.of(Format.ISO_TIMESTAMP), formats("2024-03-01 10:15:00.123+01:00"));
        assertEquals(Set.of(Format.NUMBER_TEXT), formats("-12.50"));
        assertEquals(Set.of(Format.NUMBER_TEXT), formats("1,234,567.89"));
        assertEquals(Set.of(Format.NUMBER_TEXT), formats("6.02e23"));
        assertEquals(Set.of(), formats("007"), "a code, not a number");
        assertEquals(Set.of(Format.BOOLEAN_TEXT), formats("Yes"));
        assertEquals(Set.of(Format.BOOLEAN_TEXT), formats("FALSE"));
        assertEquals(Set.of(Format.JSON_TEXT), formats("{\"a\": [1, 2]}"));
        assertEquals(Set.of(Format.JSON_TEXT), formats("[1,2]"));
        assertFalse(formats("{not json}").contains(Format.JSON_TEXT));
        assertFalse(formats("{\"a\":1}{\"b\":2}").contains(Format.JSON_TEXT), "two documents are not one");
        assertEquals(Set.of(Format.PADDED, Format.EMAIL), formats(" jane@example.com "));
        assertEquals(Set.of(), formats("   "), "a blank value is blank, not padded");
        assertEquals(Set.of(), formats("Jane Smith"));
    }

    @Test
    void patternProfileCountsShapesFormatsBlanksAndKeepsStableExamples() {
        PatternProfile p = new PatternProfile(true, 3);
        for (int i = 0; i < 90; i++) {
            p.add("SW" + (i % 9 + 1) + "A " + (i % 9) + "AA");
        }
        for (int i = 0; i < 8; i++) {
            p.add("M" + (i + 1) + " 1AE");
        }
        p.add("unknown");
        p.add(" ");
        Map<String, String> a = new java.util.LinkedHashMap<>();
        p.attributes(a);
        assertEquals("1", a.get("blanks"));
        assertTrue(a.get("patterns").startsWith("[{\"shape\":\"AA9A 9AA\",\"count\":90}"), a.get("patterns"));
        assertTrue(a.get("patterns").contains("{\"shape\":\"A9 9AA\",\"count\":8}"));
        assertEquals("0", a.get("patterns_other"), "four shapes, all listed");
        assertEquals("true", a.get("patterns_exact"));
        assertEquals("false", a.get("patterns_collapsed"));
        assertTrue(a.get("formats").contains("{\"format\":\"uk_postcode\",\"count\":98}"), a.get("formats"));
        assertEquals(98, p.format(Format.UK_POSTCODE));

        String examples = p.examplesJson();
        assertTrue(examples.contains("\"AA9A 9AA\":["), examples);
        // The same values in another order choose the same examples.
        PatternProfile again = new PatternProfile(true, 3);
        java.util.List<String> values = new java.util.ArrayList<>();
        for (int i = 0; i < 90; i++) {
            values.add("SW" + (i % 9 + 1) + "A " + (i % 9) + "AA");
        }
        for (int i = 0; i < 8; i++) {
            values.add("M" + (i + 1) + " 1AE");
        }
        values.add("unknown");
        values.add(" ");
        java.util.Collections.reverse(values);
        values.forEach(again::add);
        assertEquals(examples, again.examplesJson());
    }

    @Test
    void freeTextIsReportedAsCollapsedShapesAndValuesCanBeKeptOut() {
        PatternProfile p = new PatternProfile(false, 5);
        String[] first = {"Jo", "Ann", "Mary", "Peter", "Alexandra", "Bob", "Christopher", "Eve"};
        String[] last = {"Li", "Khan", "Smith", "Jones", "Robertson", "Ng", "Okonkwo", "Fitzgerald", "Day"};
        for (String f : first) {
            for (String l : last) {
                p.add(f + " " + l);
            }
        }
        Map<String, String> a = new java.util.LinkedHashMap<>();
        p.attributes(a);
        assertEquals("true", a.get("patterns_collapsed"));
        assertEquals("[{\"shape\":\"Aa+ Aa+\",\"count\":72}]", a.get("patterns"));
        assertEquals(null, p.examplesJson(), "no values kept");
    }

    @Test
    void columnStatsAddQualityAttributesAndDropValuesWhenAsked() {
        ColumnStats n = new ColumnStats("qty", ColumnStats.Shape.INTEGER);
        for (int i = 1; i <= 100; i++) {
            n.addLong(i);
        }
        n.addLong(-500);
        n.addLong(10_000);
        Map<String, String> a = n.attributes();
        assertEquals("1", a.get("negatives"));
        assertEquals("14550", a.get("sum"));
        assertEquals("2", a.get("outliers"), "-500 and 10000 lie beyond the fences");
        assertTrue(a.get("quantiles").startsWith("[-500,"), a.get("quantiles"));
        assertTrue(a.get("quantiles").endsWith(",10000]"));

        ColumnStats t = new ColumnStats("code", ColumnStats.Shape.TEXT, false, 5);
        t.addText("AB".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        t.addText("AB".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, String> b = t.attributes();
        assertFalse(b.containsKey("top"), "values are kept out");
        assertEquals("[{\"shape\":\"AA\",\"count\":2}]", b.get("patterns"));
    }
}

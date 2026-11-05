package io.kilnmark.kiln.plugins.profile;

import java.io.IOException;
import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

/**
 * Detectors for common formats in text. Each is cheap: a value is only tested against a format
 * when a quick look (length, first character, an {@code @}) says it could be one. Checks that
 * have a check digit use it: IBAN (mod 97) and card numbers (Luhn).
 */
public final class Formats {

    /** A format a text value can have. {@link #id()} is the name used in profiles and the API. */
    public enum Format {
        EMAIL, PHONE, UK_POSTCODE, NI_NUMBER, IBAN, CARD_NUMBER, ISO_DATE, ISO_TIMESTAMP, NUMBER_TEXT,
        BOOLEAN_TEXT, JSON_TEXT, PADDED;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The bit for this format in {@link #detect}'s result. */
        @SuppressWarnings("EnumOrdinal") // the order is the bit's; formats are only ever added at the end
        public int bit() {
            return 1 << ordinal();
        }
    }

    private static final Pattern EMAIL = Pattern.compile(
        "[A-Za-z0-9._%+'-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*"
            + "\\.[A-Za-z]{2,}");
    private static final Pattern PHONE_CHARS = Pattern.compile("\\+?[0-9 ().-]+");
    private static final Pattern UK_POSTCODE = Pattern.compile(
        "(?:GIR ?0AA|[A-Z]{1,2}[0-9][A-Z0-9]? ?[0-9][A-Z]{2})", Pattern.CASE_INSENSITIVE);
    private static final Pattern NI_NUMBER = Pattern.compile(
        "(?!BG|GB|NK|KN|TN|NT|ZZ)[A-CEGHJ-PR-TW-Z][A-CEGHJ-NPR-TW-Z] ?[0-9]{2} ?[0-9]{2} ?[0-9]{2} ?[A-D]",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern IBAN = Pattern.compile("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}");
    private static final Pattern ISO_DATE = Pattern.compile("([0-9]{4})-([0-9]{2})-([0-9]{2})");
    private static final Pattern ISO_TIMESTAMP = Pattern.compile(
        "[0-9]{4}-[0-9]{2}-[0-9]{2}[T ][0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:[.,][0-9]{1,9})?)?(?:Z|[+-][0-9]{2}(?::?[0-9]{2})?)?");
    /** Dates in other orders ({@code 01-03-2024}), which phone punctuation would otherwise admit. */
    private static final Pattern DATE_LIKE = Pattern.compile("[0-9]{1,4}[-./][0-9]{1,2}[-./][0-9]{1,4}");
    /** A number as text; a leading zero ({@code 007}, phone numbers) makes it a code instead. */
    private static final Pattern NUMBER = Pattern.compile(
        "[+-]?(?:(?:0|[1-9][0-9]*)(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?|[+-]?[1-9][0-9]{0,2}(?:,[0-9]{3})+(?:\\.[0-9]+)?");
    private static final java.util.Set<String> BOOLEANS = java.util.Set.of("true", "false", "yes", "no", "y", "n",
        "t", "f");
    private static final JsonFactory JSON = new JsonFactory();

    private Formats() {
    }

    /** Every format {@code value} has, as {@link Format#bit()}s; 0 for none. */
    public static int detect(String value) {
        int found = 0;
        if (value.isEmpty()) {
            return 0;
        }
        String v = value.strip();
        if (v.length() != value.length() && !v.isEmpty()) {
            found |= Format.PADDED.bit();
        }
        int n = v.length();
        if (n == 0) {
            return found;
        }
        char first = v.charAt(0);
        if (v.indexOf('@') > 0 && EMAIL.matcher(v).matches()) {
            found |= Format.EMAIL.bit();
        }
        if (n >= 5 && n <= 8 && Character.isLetter(first) && UK_POSTCODE.matcher(v).matches()) {
            found |= Format.UK_POSTCODE.bit();
        }
        if (n >= 9 && n <= 13 && Character.isLetter(first) && NI_NUMBER.matcher(v).matches()) {
            found |= Format.NI_NUMBER.bit();
        }
        if (n >= 15 && n <= 42 && Character.isLetter(first) && iban(v)) {
            found |= Format.IBAN.bit();
        }
        if (Character.isDigit(first)) {
            if (n == 10 && ISO_DATE.matcher(v).matches() && date(v)) {
                found |= Format.ISO_DATE.bit();
            } else if (n >= 16 && ISO_TIMESTAMP.matcher(v).matches() && date(v.substring(0, 10))) {
                found |= Format.ISO_TIMESTAMP.bit();
            }
        }
        boolean dated = (found & (Format.ISO_DATE.bit() | Format.ISO_TIMESTAMP.bit())) != 0;
        if (!dated && (Character.isDigit(first) || first == '+' || first == '(') && n >= 7 && n <= 24
            && !DATE_LIKE.matcher(v).matches()) {
            if (card(v)) {
                found |= Format.CARD_NUMBER.bit();
            } else if (phone(v)) {
                found |= Format.PHONE.bit();
            }
        }
        if ((Character.isDigit(first) || first == '-' || first == '+' || first == '.') && n <= 40
            && NUMBER.matcher(v).matches()) {
            found |= Format.NUMBER_TEXT.bit();
        }
        if (n <= 5 && BOOLEANS.contains(v.toLowerCase(Locale.ROOT))) {
            found |= Format.BOOLEAN_TEXT.bit();
        }
        if ((first == '{' && v.charAt(n - 1) == '}') || (first == '[' && v.charAt(n - 1) == ']')) {
            if (json(v)) {
                found |= Format.JSON_TEXT.bit();
            }
        }
        return found;
    }

    /** True when {@code value} has {@code format}. */
    public static boolean has(String value, Format format) {
        return (detect(value) & format.bit()) != 0;
    }

    private static boolean date(String yyyyMmDd) {
        try {
            LocalDate.parse(yyyyMmDd);
            return true;
        } catch (DateTimeException e) {
            return false;
        }
    }

    /**
     * A phone number: 7 to 15 digits with only phone punctuation, and either written
     * internationally ({@code +} or {@code 00}), as a UK national number ({@code 0} and 10 or 11
     * digits), or with separators and at least 9 digits. Plain runs of digits are left to be numbers or identifiers.
     */
    private static boolean phone(String v) {
        if (!PHONE_CHARS.matcher(v).matches()) {
            return false;
        }
        int digits = 0;
        boolean separators = false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c >= '0' && c <= '9') {
                digits++;
            } else if (c == ' ' || c == '-' || c == '(' || c == ')' || c == '.') {
                separators = true;
            }
        }
        if (digits < 7 || digits > 15 || v.indexOf('+', 1) >= 0) {
            return false;
        }
        if (v.startsWith("+") || v.startsWith("00")) {
            return true;
        }
        if (v.startsWith("0") && (digits == 10 || digits == 11)) {
            return true;
        }
        // Written with separators: at least 9 digits, and not a decimal number (one dot).
        return separators && digits >= 9 && v.chars().filter(c -> c == '.').count() != 1;
    }

    /** 13 to 19 digits, optionally grouped with spaces or dashes, from a card network, passing Luhn. */
    private static boolean card(String v) {
        int[] digits = new int[19];
        int n = 0;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c >= '0' && c <= '9') {
                if (n == 19) {
                    return false;
                }
                digits[n++] = c - '0';
            } else if (c != ' ' && c != '-') {
                return false;
            }
        }
        if (n < 13 || digits[0] < 2 || digits[0] > 6) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < n; i++) {
            int d = digits[n - 1 - i];
            if (i % 2 == 1) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
        }
        return sum % 10 == 0;
    }

    private static boolean iban(String v) {
        String s = v.replace(" ", "").toUpperCase(Locale.ROOT);
        if (!IBAN.matcher(s).matches()) {
            return false;
        }
        String moved = s.substring(4) + s.substring(0, 4);
        StringBuilder digits = new StringBuilder(moved.length() * 2);
        for (int i = 0; i < moved.length(); i++) {
            char c = moved.charAt(i);
            digits.append(Character.isDigit(c) ? String.valueOf(c) : String.valueOf(c - 'A' + 10));
        }
        return new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }

    private static boolean json(String v) {
        try (JsonParser p = JSON.createParser(v)) {
            int depth = 0;
            JsonToken t;
            while ((t = p.nextToken()) != null) {
                if (t.isStructStart()) {
                    depth++;
                } else if (t.isStructEnd()) {
                    depth--;
                    if (depth == 0) {
                        return p.nextToken() == null;
                    }
                }
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }
}

package io.kilnmark.kiln.plugins.files;

import io.kilnmark.kiln.plugins.jdbc.JdbcColumn.Kind;

/**
 * Infers one column's kind from sample values. Every non-empty value must fit a kind for the
 * column to get it; the first that fits, in this order, wins: boolean, int64, decimal, float64,
 * date, time, timestamp (no offset), timestamp_tz (every value has an offset), else text.
 *
 * <p>Plain decimals become {@code decimal(38, s)}, with {@code s} the most fractional digits seen;
 * when that cannot hold the sample (more than 18 fractional digits, or more than 38 digits in all)
 * or a value uses scientific notation, the column is float64.
 */
final class TypeGuess {

    /** Where a sample value came from, which limits what it can be. */
    enum Origin {
        /** Text with no type of its own: a CSV field. Anything goes. */
        TEXT,
        /** A JSON string: only dates and times are recognised; numbers in quotes stay text. */
        JSON_STRING,
        /** A JSON number. */
        JSON_NUMBER,
        /** A JSON true or false. */
        JSON_BOOLEAN,
        /** A JSON object or array: always text. */
        JSON_NESTED
    }

    static final int MAX_SCALE = 18;

    private boolean bool = true;
    private boolean integer = true;
    private boolean decimal = true;
    private boolean floating = true;
    private boolean date = true;
    private boolean time = true;
    private boolean timestamp = true;
    private boolean timestampTz = true;
    private int maxScale;
    private int maxIntegerDigits;
    private long seen;

    /** Offers one sample value; null and blank values say nothing about the type. */
    void offer(String value, Origin origin) {
        if (value == null) {
            return;
        }
        String s = value.strip();
        if (s.isEmpty()) {
            return;
        }
        seen++;
        switch (origin) {
            case TEXT -> {
                bool &= TextValues.bool(s) != null;
                numbers(s);
                temporal(s);
            }
            case JSON_STRING -> {
                bool = false;
                integer = false;
                decimal = false;
                floating = false;
                temporal(s);
            }
            case JSON_NUMBER -> {
                bool = false;
                numbers(s);
                noTemporal();
            }
            case JSON_BOOLEAN -> {
                integer = false;
                decimal = false;
                floating = false;
                noTemporal();
            }
            case JSON_NESTED -> {
                bool = false;
                integer = false;
                decimal = false;
                floating = false;
                noTemporal();
            }
        }
    }

    private void numbers(String s) {
        boolean whole = TextValues.isWholeNumber(s);
        boolean fraction = TextValues.isDecimal(s);
        integer &= whole && TextValues.isInteger(s);
        if (whole || fraction) {
            String digits = s.charAt(0) == '-' || s.charAt(0) == '+' ? s.substring(1) : s;
            int dot = digits.indexOf('.');
            maxScale = Math.max(maxScale, dot < 0 ? 0 : digits.length() - dot - 1);
            maxIntegerDigits = Math.max(maxIntegerDigits, dot < 0 ? digits.length() : dot);
        } else {
            decimal = false;
            floating &= TextValues.isScientific(s);
        }
    }

    private void temporal(String s) {
        boolean isDate = TextValues.date(s) != null;
        date &= isDate;
        time &= TextValues.time(s) != null;
        TextValues.DateTime dt = isDate ? null : TextValues.dateTime(s);
        timestamp &= isDate || (dt != null && dt.offset() == null);
        timestampTz &= dt != null && dt.offset() != null;
    }

    private void noTemporal() {
        date = false;
        time = false;
        timestamp = false;
        timestampTz = false;
    }

    /** The column inferred from what was offered: text when nothing was. */
    FileColumn column(String name) {
        if (seen == 0) {
            return FileColumn.inferred(name, Kind.TEXT, 0);
        }
        if (bool) {
            return FileColumn.inferred(name, Kind.BOOLEAN, 0);
        }
        if (integer) {
            return FileColumn.inferred(name, Kind.INT64, 0);
        }
        if (decimal) {
            if (maxScale <= MAX_SCALE && maxScale + maxIntegerDigits <= FileColumn.MAX_PRECISION) {
                return FileColumn.inferred(name, Kind.DECIMAL, maxScale);
            }
            return FileColumn.inferred(name, Kind.FLOAT64, 0);
        }
        if (floating) {
            return FileColumn.inferred(name, Kind.FLOAT64, 0);
        }
        if (date) {
            return FileColumn.inferred(name, Kind.DATE, 0);
        }
        if (time) {
            return FileColumn.inferred(name, Kind.TIME, 0);
        }
        if (timestamp) {
            return FileColumn.inferred(name, Kind.TIMESTAMP, 0);
        }
        if (timestampTz) {
            return FileColumn.inferred(name, Kind.TIMESTAMP_TZ, 0);
        }
        return FileColumn.inferred(name, Kind.TEXT, 0);
    }
}

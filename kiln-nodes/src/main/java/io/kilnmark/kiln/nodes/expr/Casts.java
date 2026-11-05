package io.kilnmark.kiln.nodes.expr;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

import io.kilnmark.kiln.nodes.expr.Type.Kind;

/** Conversions between types: the implicit widening and {@code cast(x as type)}. */
final class Casts {

    /** The longest text one value may become, in characters. */
    static final int MAX_TEXT = 1_000_000;

    private Casts() {
    }

    static ExprException tooLong(int pos) {
        return new ExprException("a text value would be longer than 1,000,000 characters", pos);
    }

    /** {@code s} with every {@code from} replaced by {@code to}, refusing results over {@link #MAX_TEXT}. */
    static String replace(String s, String from, String to, int pos, TextBudget budget) throws ExprException {
        String out = replace(s, from, to, pos);
        budget.spend(out.length(), pos);
        return out;
    }

    static String replace(String s, String from, String to, int pos) throws ExprException {
        if (from.isEmpty()) {
            return s;
        }
        if (to.length() > from.length()) {
            long count = 0;
            for (int i = s.indexOf(from); i >= 0; i = s.indexOf(from, i + from.length())) {
                count++;
            }
            if (s.length() + count * (to.length() - from.length()) > MAX_TEXT) {
                throw tooLong(pos);
            }
        }
        return s.replace(from, to);
    }

    /** Whether {@code cast} can turn {@code from} into {@code to}. */
    static boolean canCast(Type from, Type to) {
        if (from.isNull() || from.kind() == to.kind() || to.kind() == Kind.UTF8 || from.kind() == Kind.UTF8) {
            return to.kind() != Kind.NULL;
        }
        if (from.isNumeric() && to.isNumeric()) {
            return true;
        }
        return switch (from.kind()) {
            case BOOLEAN -> to.kind() == Kind.INT64;
            case INT64 -> to.kind() == Kind.BOOLEAN;
            case DATE -> to.kind() == Kind.TIMESTAMP;
            case TIMESTAMP -> to.kind() == Kind.DATE;
            default -> false;
        };
    }

    /**
     * Converts every row (as {@code cast} does) into a new column of type {@code to}; returns
     * {@code col} itself when it already has that type.
     */
    static Col convert(Col col, Type to, int pos) throws ExprException {
        return convert(col, to, pos, null);
    }

    static Col convert(Col col, Type to, int pos, boolean[] mask) throws ExprException {
        if (col.type.equals(to)) {
            return col;
        }
        int n = col.rows;
        Col out = Col.blank(to, n);
        Kind from = col.type.kind();
        for (int i = 0; i < n; i++) {
            if (col.nulls[i] || (mask != null && !mask[i])) {
                out.nulls[i] = true;
                continue;
            }
            switch (to.kind()) {
                case INT64 -> out.longs[i] = switch (from) {
                    case BOOLEAN -> col.bools[i] ? 1 : 0;
                    case FLOAT64 -> floatToLong(col.doubles[i], pos);
                    case DECIMAL -> decimalToLong((BigDecimal) col.objects[i], pos);
                    case UTF8 -> textToLong((String) col.objects[i], pos);
                    default -> col.longs[i];
                };
                case FLOAT64 -> out.doubles[i] = switch (from) {
                    case INT64 -> col.longs[i];
                    case DECIMAL -> ((BigDecimal) col.objects[i]).doubleValue();
                    case UTF8 -> textToDouble((String) col.objects[i], pos);
                    default -> col.doubles[i];
                };
                case DECIMAL -> out.objects[i] = toDecimal(col, i, to, pos);
                case BOOLEAN -> out.bools[i] = switch (from) {
                    case INT64 -> col.longs[i] != 0;
                    case UTF8 -> textToBoolean((String) col.objects[i], pos);
                    default -> col.bools[i];
                };
                case UTF8 -> out.objects[i] = text(col, i);
                case DATE -> out.longs[i] = switch (from) {
                    case TIMESTAMP -> Math.floorDiv(col.longs[i], Temporal.MICROS_PER_DAY);
                    case UTF8 -> {
                        Long d = Temporal.parseDateLiteral((String) col.objects[i]);
                        if (d == null) {
                            throw new ExprException("cannot cast a text value to date: it is not in the form yyyy-mm-dd",
                                pos, "use parse_date(text, 'pattern') for other forms; it gives null when a value does not match");
                        }
                        yield d;
                    }
                    default -> col.longs[i];
                };
                case TIMESTAMP -> out.longs[i] = switch (from) {
                    case DATE -> {
                        try {
                            yield Math.multiplyExact(col.longs[i], Temporal.MICROS_PER_DAY);
                        } catch (ArithmeticException e) {
                            throw new ExprException("a date is too far from today to be a timestamp", pos);
                        }
                    }
                    case UTF8 -> {
                        Long t = Temporal.parseTimestamp((String) col.objects[i]);
                        if (t == null) {
                            throw new ExprException("cannot cast a text value to timestamp: it is not in the form "
                                + "yyyy-mm-dd hh:mm:ss", pos,
                                "use parse_timestamp(text, 'pattern') for other forms; it gives null when a value does not match");
                        }
                        yield t;
                    }
                    default -> col.longs[i];
                };
                case NULL -> out.nulls[i] = true;
            }
        }
        return out;
    }

    private static BigDecimal toDecimal(Col col, int i, Type to, int pos) throws ExprException {
        BigDecimal v = switch (col.type.kind()) {
            case INT64 -> BigDecimal.valueOf(col.longs[i]);
            case FLOAT64 -> {
                double d = col.doubles[i];
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    throw new ExprException("cannot turn " + d + " into a decimal", pos);
                }
                yield BigDecimal.valueOf(d);
            }
            case BOOLEAN -> col.bools[i] ? BigDecimal.ONE : BigDecimal.ZERO;
            case UTF8 -> textToDecimal((String) col.objects[i], pos);
            default -> (BigDecimal) col.objects[i];
        };
        return fit(v, to, pos);
    }

    /** A decimal rounded to the type's scale, checked against its precision. */
    static BigDecimal fit(BigDecimal v, Type to, int pos) throws ExprException {
        if (v.precision() - v.scale() > to.precision() - to.scale()) {
            throw new ExprException("a value is too large for " + to, pos);
        }
        BigDecimal r = v.setScale(to.scale(), RoundingMode.HALF_UP);
        if (r.precision() > to.precision()) {
            throw new ExprException("a value is too large for " + to, pos);
        }
        return r;
    }

    private static long floatToLong(double d, int pos) throws ExprException {
        if (Double.isNaN(d) || Double.isInfinite(d) || d >= 0x1p63 || d < -0x1p63) {
            throw new ExprException("cannot turn a floating-point value into int64: it is not a number or too large", pos);
        }
        return (long) (d < 0 ? Math.ceil(d - 0.5) : Math.floor(d + 0.5));
    }

    private static long decimalToLong(BigDecimal v, int pos) throws ExprException {
        try {
            return v.setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (ArithmeticException e) {
            throw new ExprException("a decimal value is too large for int64", pos);
        }
    }

    private static long textToLong(String s, int pos) throws ExprException {
        String t = s.trim();
        if (t.length() <= 20 && isAsciiWhole(t)) {
            try {
                return Long.parseLong(t);
            } catch (NumberFormatException e) {
                // fall through to the error
            }
        }
        throw new ExprException("cannot cast a text value to int64: it is not a whole number", pos,
            "guard it, for example case when regex_match(x, '^-?[0-9]+$') then cast(x as int64) end");
    }

    private static boolean isAsciiWhole(String t) {
        int start = !t.isEmpty() && (t.charAt(0) == '+' || t.charAt(0) == '-') ? 1 : 0;
        if (start == t.length()) {
            return false;
        }
        for (int i = start; i < t.length(); i++) {
            if (t.charAt(i) < '0' || t.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    private static double textToDouble(String s, int pos) throws ExprException {
        String t = s.trim();
        if (t.length() <= 400 && isDecimalText(t, true)) {
            double d = Double.parseDouble(t);
            if (!Double.isInfinite(d)) {
                return d;
            }
            throw new ExprException("cannot cast a text value to float64: it is too large", pos);
        }
        throw new ExprException("cannot cast a text value to float64: it is not a number", pos);
    }

    private static BigDecimal textToDecimal(String s, int pos) throws ExprException {
        String t = s.trim();
        if (t.length() <= 100 && isDecimalText(t, true)) {
            BigDecimal v = new BigDecimal(t);
            if (v.precision() - v.scale() <= Type.MAX_PRECISION && v.scale() <= 1_000) {
                return v;
            }
        }
        throw new ExprException("cannot cast a text value to decimal: it is not a number, or it is too large", pos);
    }

    /** Digits with an optional sign, point and (if allowed) exponent: no hexadecimal, NaN or suffixes. */
    static boolean isDecimalText(String t, boolean exponent) {
        int i = 0;
        int n = t.length();
        if (i < n && (t.charAt(i) == '+' || t.charAt(i) == '-')) {
            i++;
        }
        int digits = 0;
        while (i < n && t.charAt(i) >= '0' && t.charAt(i) <= '9') {
            i++;
            digits++;
        }
        if (i < n && t.charAt(i) == '.') {
            i++;
            while (i < n && t.charAt(i) >= '0' && t.charAt(i) <= '9') {
                i++;
                digits++;
            }
        }
        if (digits == 0) {
            return false;
        }
        if (exponent && i < n && (t.charAt(i) == 'e' || t.charAt(i) == 'E')) {
            i++;
            if (i < n && (t.charAt(i) == '+' || t.charAt(i) == '-')) {
                i++;
            }
            int e = 0;
            while (i < n && t.charAt(i) >= '0' && t.charAt(i) <= '9') {
                i++;
                e++;
            }
            if (e == 0 || e > 4) {
                return false;
            }
        }
        return i == n;
    }

    private static boolean textToBoolean(String s, int pos) throws ExprException {
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "true", "t", "yes", "y", "1" -> true;
            case "false", "f", "no", "n", "0" -> false;
            default -> throw new ExprException("cannot cast a text value to boolean: use true or false (also yes/no, 1/0)",
                pos);
        };
    }

    /** Row {@code i} as text, as {@code cast(x as utf8)} and {@code concat} write it. */
    static String text(Col col, int i) {
        return switch (col.type.kind()) {
            case BOOLEAN -> col.bools[i] ? "true" : "false";
            case INT64 -> Long.toString(col.longs[i]);
            case FLOAT64 -> floatText(col.doubles[i]);
            case DECIMAL -> ((BigDecimal) col.objects[i]).toPlainString();
            case UTF8 -> (String) col.objects[i];
            case DATE -> Temporal.dateText(col.longs[i]);
            case TIMESTAMP -> Temporal.timestampText(col.longs[i]);
            case NULL -> null;
        };
    }

    static String floatText(double d) {
        if (Double.isNaN(d)) {
            return "NaN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "Infinity" : "-Infinity";
        }
        if (d == 0) {
            return "0";
        }
        double a = Math.abs(d);
        if (a >= 1e-6 && a < 1e16) {
            return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
        }
        return Double.toString(d);
    }
}

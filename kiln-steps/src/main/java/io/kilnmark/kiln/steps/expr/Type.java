package io.kilnmark.kiln.steps.expr;

import java.util.Locale;

import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;

/**
 * The type of an expression's value. Expressions know seven types, plus the type of a bare
 * {@code null}:
 *
 * <ul>
 *   <li>{@code boolean}</li>
 *   <li>{@code int64}: whole numbers (every Arrow integer column reads as this)</li>
 *   <li>{@code float64}: floating-point numbers</li>
 *   <li>{@code decimal(p,s)}: exact decimals, up to 38 digits; results of arithmetic have precision 38</li>
 *   <li>{@code utf8}: text</li>
 *   <li>{@code date}: a calendar day</li>
 *   <li>{@code timestamp}: an instant, in UTC, to the microsecond</li>
 * </ul>
 *
 * @param kind      the kind of value
 * @param precision digits in all, for decimals; 0 otherwise
 * @param scale     digits after the decimal point, for decimals; 0 otherwise
 */
public record Type(Kind kind, int precision, int scale) {

    /** Kinds of value. */
    public enum Kind { NULL, BOOLEAN, INT64, FLOAT64, DECIMAL, UTF8, DATE, TIMESTAMP }

    /** The most digits a decimal holds. */
    public static final int MAX_PRECISION = 38;
    /** The most digits after the point that arithmetic keeps. */
    public static final int MAX_SCALE = 18;

    public static final Type NULL = new Type(Kind.NULL, 0, 0);
    public static final Type BOOLEAN = new Type(Kind.BOOLEAN, 0, 0);
    public static final Type INT64 = new Type(Kind.INT64, 0, 0);
    public static final Type FLOAT64 = new Type(Kind.FLOAT64, 0, 0);
    public static final Type UTF8 = new Type(Kind.UTF8, 0, 0);
    public static final Type DATE = new Type(Kind.DATE, 0, 0);
    public static final Type TIMESTAMP = new Type(Kind.TIMESTAMP, 0, 0);

    /** A decimal with 38 digits and this scale. */
    public static Type decimal(int scale) {
        return new Type(Kind.DECIMAL, MAX_PRECISION, Math.clamp(scale, 0, MAX_SCALE));
    }

    /** A decimal with this precision and scale. */
    public static Type decimal(int precision, int scale) {
        return new Type(Kind.DECIMAL, precision, scale);
    }

    public boolean isNumeric() {
        return kind == Kind.INT64 || kind == Kind.FLOAT64 || kind == Kind.DECIMAL;
    }

    public boolean isNull() {
        return kind == Kind.NULL;
    }

    /** Whether values of this type can be ordered, so used in {@code <}, {@code min} and {@code max}. */
    public boolean isOrdered() {
        return kind != Kind.NULL;
    }

    @Override
    public String toString() {
        return kind == Kind.DECIMAL ? "decimal(" + precision + "," + scale + ")" : kind.name().toLowerCase(Locale.ROOT);
    }

    /** Short name for messages: {@code decimal} rather than {@code decimal(38,2)}. */
    public String family() {
        return kind == Kind.NULL ? "null" : kind.name().toLowerCase(Locale.ROOT);
    }

    /**
     * The type both can be widened to without losing meaning, or null if there is none. Whole
     * numbers widen to decimals, whole numbers and decimals to floating point, and dates to
     * timestamps (midnight UTC); {@code null} widens to anything.
     */
    public static Type common(Type a, Type b) {
        if (a.kind == Kind.NULL) {
            return b;
        }
        if (b.kind == Kind.NULL || a.equals(b)) {
            return a;
        }
        if (a.kind == b.kind) {
            if (a.kind == Kind.DECIMAL) {
                int scale = Math.max(a.scale, b.scale);
                int whole = Math.max(a.precision - a.scale, b.precision - b.scale);
                return whole + scale <= MAX_PRECISION ? decimal(whole + scale, scale) : decimal(scale);
            }
            return a;
        }
        if (a.isNumeric() && b.isNumeric()) {
            if (a.kind == Kind.FLOAT64 || b.kind == Kind.FLOAT64) {
                return FLOAT64;
            }
            Type d = a.kind == Kind.DECIMAL ? a : b;
            return decimal(Math.max(d.precision, Math.min(MAX_PRECISION, 19 + d.scale)), d.scale);
        }
        if ((a.kind == Kind.DATE && b.kind == Kind.TIMESTAMP) || (a.kind == Kind.TIMESTAMP && b.kind == Kind.DATE)) {
            return TIMESTAMP;
        }
        return null;
    }

    /**
     * A type named in {@code cast(x as ...)} or a select step's {@code cast} setting, or null if the
     * name is not a type: {@code boolean}, {@code int64}, {@code float64}, {@code decimal(p,s)},
     * {@code utf8}, {@code date}, {@code timestamp}, and common other names for them
     * ({@code bigint}, {@code integer}, {@code double}, {@code numeric}, {@code text}, {@code string},
     * {@code varchar}, ...).
     */
    public static Type named(String name) {
        String n = name.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        int paren = n.indexOf('(');
        String base = paren < 0 ? n : n.substring(0, paren);
        switch (base) {
            case "boolean", "bool" -> {
                return paren < 0 ? BOOLEAN : null;
            }
            case "int64", "bigint", "int", "integer", "long", "int32", "smallint" -> {
                return paren < 0 ? INT64 : null;
            }
            case "float64", "double", "float", "real", "doubleprecision" -> {
                return paren < 0 ? FLOAT64 : null;
            }
            case "utf8", "text", "string", "varchar" -> {
                return paren < 0 || base.equals("varchar") ? UTF8 : null;
            }
            case "date" -> {
                return paren < 0 ? DATE : null;
            }
            case "timestamp" -> {
                return paren < 0 ? TIMESTAMP : null;
            }
            case "decimal", "numeric" -> {
                if (paren < 0) {
                    return decimal(MAX_PRECISION, 0);
                }
                if (!n.endsWith(")")) {
                    return null;
                }
                String[] parts = n.substring(paren + 1, n.length() - 1).split(",", -1);
                try {
                    int p = Integer.parseInt(parts[0]);
                    int s = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
                    if (parts.length > 2 || p < 1 || p > MAX_PRECISION || s < 0 || s > p) {
                        return null;
                    }
                    return decimal(p, s);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            default -> {
                return null;
            }
        }
    }

    /** Whether {@code cast} can turn values of type {@code from} into {@code to}. */
    public static boolean canCast(Type from, Type to) {
        return Casts.canCast(from, to);
    }

    /** The expression type of an Arrow column, or null if expressions cannot use it (binary, lists, ...). */
    public static Type of(ArrowType t) {
        return switch (t) {
            case ArrowType.Null _ -> NULL;
            case ArrowType.Bool _ -> BOOLEAN;
            case ArrowType.Int _ -> INT64;
            case ArrowType.FloatingPoint _ -> FLOAT64;
            case ArrowType.Decimal d -> decimal(d.getPrecision(), d.getScale());
            case ArrowType.Utf8 _ -> UTF8;
            case ArrowType.LargeUtf8 _ -> UTF8;
            case ArrowType.Utf8View _ -> UTF8;
            case ArrowType.Date _ -> DATE;
            case ArrowType.Timestamp _ -> TIMESTAMP;
            default -> null;
        };
    }

    /** The Arrow type results of this type are written as. A bare null is written as text. */
    public ArrowType arrow() {
        return switch (kind) {
            case NULL, UTF8 -> ArrowType.Utf8.INSTANCE;
            case BOOLEAN -> ArrowType.Bool.INSTANCE;
            case INT64 -> new ArrowType.Int(64, true);
            case FLOAT64 -> new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
            case DECIMAL -> new ArrowType.Decimal(precision, scale, 128);
            case DATE -> new ArrowType.Date(DateUnit.DAY);
            case TIMESTAMP -> new ArrowType.Timestamp(TimeUnit.MICROSECOND, "UTC");
        };
    }

    /** A nullable column of this type. */
    public Field field(String name) {
        return new Field(name, FieldType.nullable(arrow()), null);
    }
}

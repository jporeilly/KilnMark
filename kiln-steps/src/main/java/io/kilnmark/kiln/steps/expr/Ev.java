package io.kilnmark.kiln.steps.expr;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.kilnmark.kiln.steps.expr.Type.Kind;

/**
 * A checked expression, ready to work out over a batch at a time. Every node produces a whole
 * column. A mask, when given, marks the rows that need a value: the others come out null and
 * are never worked out, so {@code case when x <> 0 then 10 / x end} never divides by zero, and
 * {@code and}, {@code or}, {@code coalesce} and {@code case} only work out what they need.
 */
abstract class Ev {

    final Type type;
    final int pos;

    Ev(Type type, int pos) {
        this.type = type;
        this.pos = pos;
    }

    /** The value for every row; rows not in {@code mask} (when not null) are null. */
    abstract Col eval(Batch b, boolean[] mask) throws ExprException;

    static boolean on(boolean[] mask, int i) {
        return mask == null || mask[i];
    }

    /** {@code mask} and the rows where {@code cond} is (or is not) true. */
    static boolean[] and(boolean[] mask, Col cond, boolean wantTrue) {
        boolean[] m = new boolean[cond.rows];
        for (int i = 0; i < m.length; i++) {
            m[i] = on(mask, i) && cond.isTrue(i) == wantTrue;
        }
        return m;
    }

    // ---- leaves ----

    static final class Const extends Ev {
        private final Object value;

        Const(Type type, Object value, int pos) {
            super(type, pos);
            this.value = value;
        }

        Object value() {
            return value;
        }

        @Override
        Col eval(Batch b, boolean[] mask) {
            Col c = Col.blank(type, b.rows());
            for (int i = 0; i < c.rows; i++) {
                c.set(i, on(mask, i) ? value : null);
            }
            return c;
        }
    }

    static final class Column extends Ev {
        final String name;

        Column(String name, Type type, int pos) {
            super(type, pos);
            this.name = name;
        }

        @Override
        Col eval(Batch b, boolean[] mask) throws ExprException {
            Col c = b.column(name, pos);
            if (!c.type.equals(type)) {
                if (c.type.kind() == type.kind() || Casts.canCast(c.type, type)) {
                    return Casts.convert(c, type, pos, mask);
                }
                throw new ExprException("the column '" + name + "' is " + c.type.family() + " in these rows, not "
                    + type.family(), pos);
            }
            if (mask == null) {
                return c;
            }
            Col masked = Col.blank(type, c.rows);
            for (int i = 0; i < c.rows; i++) {
                if (mask[i]) {
                    masked.copy(c, i, i);
                } else {
                    masked.nulls[i] = true;
                }
            }
            return masked;
        }
    }

    static final class Now extends Ev {
        Now(Type type, int pos) {
            super(type, pos);
        }

        @Override
        Col eval(Batch b, boolean[] mask) {
            long v = type.kind() == Kind.DATE ? Math.floorDiv(b.now(), Temporal.MICROS_PER_DAY) : b.now();
            Col c = Col.blank(type, b.rows());
            for (int i = 0; i < c.rows; i++) {
                c.set(i, on(mask, i) ? v : null);
            }
            return c;
        }
    }

    /** Reads a column worked out once by the node that owns the slot (a simple case's operand). */
    static final class Slot extends Ev {
        private final Col[] slot;

        Slot(Col[] slot, Type type, int pos) {
            super(type, pos);
            this.slot = slot;
        }

        @Override
        Col eval(Batch b, boolean[] mask) {
            return slot[0];
        }
    }

    // ---- conversions ----

    static final class Convert extends Ev {
        private final Ev inner;

        Convert(Ev inner, Type to) {
            super(to, inner.pos);
            this.inner = inner;
        }

        @Override
        Col eval(Batch b, boolean[] mask) throws ExprException {
            return Casts.convert(inner.eval(b, mask), type, pos, mask);
        }
    }

    /** Converts to {@code to} unless it already has that type. */
    static Ev widen(Ev e, Type to) {
        if (e.type.equals(to)) {
            return e;
        }
        if (e instanceof Const c && c.type.isNull()) {
            return new Const(to, null, c.pos);
        }
        return new Convert(e, to);
    }

    // ---- arithmetic ----

    static final class Negate extends Ev {
        private final Ev operand;

        Negate(Ev operand, int pos) {
            super(operand.type, pos);
            this.operand = operand;
        }

        @Override
        Col eval(Batch b, boolean[] mask) throws ExprException {
            Col x = operand.eval(b, mask);
            Col out = Col.blank(type, x.rows);
            for (int i = 0; i < x.rows; i++) {
                out.nulls[i] = x.nulls[i];
                if (x.nulls[i]) {
                    continue;
                }
                switch (type.kind()) {
                    case INT64 -> {
                        if (x.longs[i] == Long.MIN_VALUE) {
                            throw overflow(pos);
                        }
                        out.longs[i] = -x.longs[i];
                    }
                    case FLOAT64 -> out.doubles[i] = -x.doubles[i];
                    case DECIMAL -> out.objects[i] = ((BigDecimal) x.objects[i]).negate();
                    default -> out.nulls[i] = true;
                }
            }
            return out;
        }
    }

    static ExprException overflow(int pos) {
        return new ExprException("a whole number is too large for int64", pos,
            "cast to decimal first, like cast(x as decimal(38,0))");
    }

    /** {@code + - * / %} on two operands of the same kind as the result. */
    static final class Arith extends Ev {
        private final String op;
        private final Ev left;
        private final Ev right;

        Arith(String op, Ev left, Ev right, Type type, int pos) {
            super(type, pos);
            this.op = op;
            this.left = left;
            this.right = right;
        }

        @Override
        Col eval(Batch b, boolean[] mask) throws ExprException {
            Col l = left.eval(b, mask);
            Col r = right.eval(b, mask);
            Col out = Col.blank(type, l.rows);
            for (int i = 0; i < l.rows; i++) {
                if (l.nulls[i] || r.nulls[i]) {
                    out.nulls[i] = true;
                    continue;
                }
                switch (type.kind()) {
                    case INT64 -> out.longs[i] = ints(l.longs[i], r.longs[i]);
                    case FLOAT64 -> out.doubles[i] = floats(l.doubles[i], r.doubles[i]);
                    case DECIMAL -> out.objects[i] = decimals((BigDecimal) l.objects[i], (BigDecimal) r.objects[i]);
                    default -> out.nulls[i] = true;
                }
            }
            return out;
        }

        private long ints(long a, long c) throws ExprException {
            try {
                return switch (op) {
                    case "+" -> Math.addExact(a, c);
                    case "-" -> Math.subtractExact(a, c);
                    case "*" -> Math.multiplyExact(a, c);
                    default -> {
                        if (c == 0) {
                            throw divideByZero();
                        }
                        yield a % c;
                    }
                };
            } catch (ArithmeticException e) {
                throw overflow(pos);
            }
        }

        private double floats(double a, double c) throws ExprException {
            return switch (op) {
                case "+" -> a + c;
                case "-" -> a - c;
                case "*" -> a * c;
                case "/" -> {
                    if (c == 0) {
                        throw divideByZero();
                    }
                    yield a / c;
                }
                default -> {
                    if (c == 0) {
                        throw divideByZero();
                    }
                    yield a % c;
                }
            };
        }

        private BigDecimal decimals(BigDecimal a, BigDecimal c) throws ExprException {
            BigDecimal v = switch (op) {
                case "+" -> a.add(c);
                case "-" -> a.subtract(c);
                case "*" -> a.multiply(c);
                case "/" -> {
                    if (c.signum() == 0) {
                        throw divideByZero();
                    }
                    yield a.divide(c, type.scale(), RoundingMode.HALF_UP);
                }
                default -> {
                    if (c.signum() == 0) {
                        throw divideByZero();
                    }
                    yield a.remainder(c);
                }
            };
            return Casts.fit(v, type, pos);
        }

        private ExprException divideByZero() {
            return new ExprException("division by zero", pos, "guard it with nullif, like x / nullif(y, 0)");
        }
    }

    /** {@code date + n}, {@code date - n} (days) and {@code date - date} (days between). */
    static final class DateArith extends Ev {
        private final String op;
        private final Ev left;
        private final Ev right;

        DateArith(String op, Ev left, Ev right, Type type, int pos) {
            super(type, pos);
            this.op = op;
            this.left = left;
            this.right = right;
        }

        @Override
        Col eval(Batch b, boolean[] mask) throws ExprException {
            Col l = left.eval(b, mask);
            Col r = right.eval(b, mask);
            Col out = Col.blank(type, l.rows);
            for (int i = 0; i < l.rows; i++) {
                if (l.nulls[i] || r.nulls[i]) {
                    out.nulls[i] = true;
                    continue;
                }
                try {
                    out.longs[i] = op.equals("+") ? Math.addExact(l.longs[i], r.longs[i])
                        : Math.subtractExact(l.longs[i], r.longs[i]);
                } catch (ArithmeticException e) {
                    throw overflow(pos);
                }
                if (type.kind() == Kind.DATE && !Temporal.inRange(out.longs[i])) {
                    throw new ExprException("a date is out of range (years -9999 to 9999)", pos);
                }
            }
            return out;
        }
    }

    /** {@code text || text}. */
    static final class Concat extends Ev {
        private final Ev left;
        private final Ev right;

        Concat(Ev left, Ev right, int pos) {
            super(Type.UTF8, pos);
            this.left = left;
            this.right = right;
        }

        @Override
        Col eval(Batch b, boolean[] mask) throws ExprException {
            Col l = left.eval(b, mask);
            Col r = right.eval(b, mask);
            Col out = Col.blank(type, l.rows);
            for (int i = 0; i < l.rows; i++) {
                if (l.nulls[i] || r.nulls[i]) {
                    out.nulls[i] = true;
                } else {
                    String a = (String) l.objects[i];
                    String z = (String) r.objects[i];
                    if ((long) a.length() + z.length() > Casts.MAX_TEXT) {
                        throw Casts.tooLong(pos);
                    }
                    out.objects[i] = a + z;
                }
            }
            return out;
        }
    }

    // ---- comparisons and logic ----

    static final class Compare extends Ev {
        private final String op;
        private final Ev left;
        private final Ev right;

        /** Both operands must already have the same type. */
        Compare(String op, Ev left, Ev right, int pos) {
            super(Type.BOOLEAN, pos);
            this.op = op;
            this.left = left;
            this.right = right;
        }

        @Override
        Col eval(Batch b, boolean[] mask) throws ExprException {
            Col l = left.eval(b, mask);
            Col r = right.eval(b, mask);
            Col out = Col.blank(type, l.rows);
            for (int i = 0; i < l.rows; i++) {
                if (l.nulls[i] || r.nulls[i]) {
                    out.nulls[i] = true;
                    continue;
                }
                int c = compare(l, r, i);
                out.bools[i] = switch (op) {
                    case "=" -> c == 0;
                    case "<>" -> c != 0;
                    case "<" -> c < 0;
                    case "<=" -> c <= 0;
                    case ">" -> c > 0;
                    default -> c >= 0;
                };
            }
            return out;
        }
    }

    /** Orders row {@code i} of two columns of the same type. */
    static int compare(Col l, Col r, int i) {
        return compare(l, i, r, i);
    }

    static int compare(Col l, int i, Col r, int j) {
        return switch (l.type.kind()) {
            case BOOLEAN -> Boolean.compare(l.bools[i], r.bools[j]);
            case INT64, DATE, TIMESTAMP -> Long.compare(l.longs[i], r.longs[j]);
            case FLOAT64 -> l.doubles[i] == r.doubles[j] ? 0 : Double.compare(l.doubles[i], r.doubles[j]);
            case DECIMAL -> ((BigDecimal) l.objects[i]).compareTo((BigDecimal) r.objects[j]);
            case UTF8 -> compareText((String) l.objects[i], (String) r.objects[j]);
            case NULL -> 0;
        };
    }

    /** Text in Unicode code point order (the order of UTF-8 bytes), not Java's UTF-16 order. */
    static int compareText(String a, String b) {
        int n = Math.min(a.length(), b.length());
        for (int k = 0; k < n; k++) {
            char x = a.charAt(k);
            char y = b.charAt(k);
            if (x != y) {
                // surrogates (U+D800-DFFF, for code points above U+FFFF) sort after U+E000-FFFF in code point order
                boolean sx = Character.isSurrogate(x);
                boolean sy = Character.isSurrogate(y);
                if (sx != sy && (x >= 0xE000 || y >= 0xE000)) {
                    return sx ? 1 : -1;
                }
                return x - y;
            }
        }
        return a.length() - b.length();
    }

    /** SQL's three-valued {@code and} / {@code or}; the right side is only worked out where it matters. */
    static final class Logic extends Ev {
        private final boolean isAnd;
        private final Ev left;
        private final Ev right;

        Logic(boolean isAnd, Ev left, Ev right, int pos) {
            super(Type.BOOLEAN, pos);
            this.isAnd = isAnd;
            this.left = left;
            this.right = right;
        }

        @Override
        Col eval(Batch b, boolean[] mask) throws ExprException {
            Col l = left.eval(b, mask);
            int n = l.rows;
            // and: the right side only matters where the left is not false; or: where it is not true
            boolean[] need = new boolean[n];
            for (int i = 0; i < n; i++) {
                need[i] = on(mask, i) && (l.nulls[i] || l.bools[i] == isAnd);
            }
            Col r = right.eval(b, need);
            Col out = Col.blank(type, n);
            for (int i = 0; i < n; i++) {
                if (!on(mask, i)) {
                    out.nulls[i] = true;
                } else if (!need[i]) {
                    out.bools[i] = l.bools[i]; // and: false; or: true
                } else if (!r.nulls[i] && r.bools[i] != isAnd) {
                    out.bools[i] = !isAnd; // and with a false: false; or with a true: true
                } else if (l.nulls[i] || r.nulls[i]) {
                    out.nulls[i] = true;
                } else {
                    out.bools[i] = isAnd;
                }
            }
            return out;
        }
    }

    /** {@code x [not] between low and high}: {@code x >= low and x <= high}, with SQL's nulls. */
    static final class Between extends Ev {
        private final Ev operand;
        private final Ev low;
        private final Ev high;
        private final boolean negated;

        /** All three must already have the same type. */
        Between(Ev operand, Ev low, Ev high, boolean negated, int pos) {
            super(Type.BOOLEAN, pos);
            this.operand = operand;
            this.low = low;
            this.high = high;
            this.negated = negated;
        }

        @Override
        Col eval(Batch b, boolean[] mask) throws ExprException {
            Col x = operand.eval(b, mask);
            Col lo = low.eval(b, mask);
            Col hi = high.eval(b, mask);
            Col out = Col.blank(type, x.rows);
            for (int i = 0; i < x.rows; i++) {
                if (x.nulls[i]) {
                    out.nulls[i] = true;
                    continue;
                }
                // each side: true, false or unknown (null)
                Boolean above = lo.nulls[i] ? null : compare(x, lo, i) >= 0;
                Boolean below = hi.nulls[i] ? null : compare(x, hi, i) <= 0;
                Boolean both;
                if (Boolean.FALSE.equals(above) || Boolean.FALSE.equals(below)) {
                    both = false;
                } else if (above == null || below == null) {
                    both = null;
                } else {
                    both = true;
                }
                if (both == null) {
                    out.nulls[i] = true;
                } else {
                    out.bools[i] = both != negated;
                }
            }
            return out;
        }
    }

    static final class Not extends Ev {
        private final Ev operand;

        Not(Ev operand, int pos) {
            super(Type.BOOLEAN, pos);
            this.operand = operand;
        }

        @Override
        Col eval(Batch b, boolean[] mask) throws ExprException {
            Col x = operand.eval(b, mask);
            Col out = Col.blank(type, x.rows);
            for (int i = 0; i < x.rows; i++) {
                out.nulls[i] = x.nulls[i];
                out.bools[i] = !x.nulls[i] && !x.bools[i];
            }
            return out;
        }
    }

    static final class IsNull extends Ev {
        private final Ev operand;
        private final boolean negated;

        IsNull(Ev operand, boolean negated, int pos) {
            super(Type.BOOLEAN, pos);
            this.operand = operand;
            this.negated = negated;
        }

        @Override
        Col eval(Batch b, boolean[] mask) throws ExprException {
            Col x = operand.eval(b, mask);
            Col out = Col.blank(type, x.rows);
            for (int i = 0; i < x.rows; i++) {
                if (!on(mask, i)) {
                    out.nulls[i] = true;
                } else {
                    out.bools[i] = x.nulls[i] != negated;
                }
            }
            return out;
        }
    }

    /** {@code x in (...)}: true if any item equals x; else null if x or an item is null; else false. */
    static final class In extends Ev {
        private final Ev operand;
        private final List<Ev> items;
        private final Set<Object> constants;
        private final boolean constantNull;
        private final boolean negated;

        In(Ev operand, List<Ev> items, boolean negated, int pos) {
            super(Type.BOOLEAN, pos);
            this.operand = operand;
            this.items = items;
            this.negated = negated;
            boolean allConst = items.stream().allMatch(e -> e instanceof Const);
            if (allConst) {
                Set<Object> set = new HashSet<>();
                boolean sawNull = false;
                for (Ev e : items) {
                    Object v = ((Const) e).value();
                    if (v == null) {
                        sawNull = true;
                    } else {
                        set.add(key(v));
                    }
                }
                constants = set;
                constantNull = sawNull;
            } else {
                constants = null;
                constantNull = false;
            }
        }

        /** Numeric equality: 1.0 and 1.00 are one decimal, -0.0 and 0.0 one float. */
        private static Object key(Object v) {
            if (v instanceof BigDecimal d) {
                return d.signum() == 0 ? BigDecimal.ZERO : d.stripTrailingZeros();
            }
            if (v instanceof Double d && d == 0.0) {
                return 0.0;
            }
            return v;
        }

        @Override
        Col eval(Batch b, boolean[] mask) throws ExprException {
            Col x = operand.eval(b, mask);
            int n = x.rows;
            Col out = Col.blank(type, n);
            if (constants != null) {
                for (int i = 0; i < n; i++) {
                    if (x.nulls[i]) {
                        out.nulls[i] = true;
                    } else if (constants.contains(key(x.get(i)))) {
                        out.bools[i] = !negated;
                    } else if (constantNull) {
                        out.nulls[i] = true;
                    } else {
                        out.bools[i] = negated;
                    }
                }
                return out;
            }
            boolean[] found = new boolean[n];
            boolean[] sawNull = new boolean[n];
            for (Ev item : items) {
                Col c = item.eval(b, mask);
                for (int i = 0; i < n; i++) {
                    if (x.nulls[i] || found[i]) {
                        continue;
                    }
                    if (c.nulls[i]) {
                        sawNull[i] = true;
                    } else if (compare(x, c, i) == 0) {
                        found[i] = true;
                    }
                }
            }
            for (int i = 0; i < n; i++) {
                if (x.nulls[i] || (!found[i] && sawNull[i])) {
                    out.nulls[i] = true;
                } else {
                    out.bools[i] = found[i] != negated;
                }
            }
            return out;
        }
    }

    static final class LikeEv extends Ev {
        private final Ev operand;
        private final Ev pattern;
        private final Like fixed;
        private final boolean negated;

        LikeEv(Ev operand, Ev pattern, boolean negated, int pos) {
            super(Type.BOOLEAN, pos);
            this.operand = operand;
            this.pattern = pattern;
            this.negated = negated;
            this.fixed = pattern instanceof Const c && c.value() != null ? new Like((String) c.value()) : null;
        }

        @Override
        Col eval(Batch b, boolean[] mask) throws ExprException {
            Col x = operand.eval(b, mask);
            Col p = fixed == null ? pattern.eval(b, mask) : null;
            Col out = Col.blank(type, x.rows);
            Like last = null;
            String lastText = null;
            for (int i = 0; i < x.rows; i++) {
                if (x.nulls[i] || (p != null && p.nulls[i]) || (fixed == null && p == null)) {
                    out.nulls[i] = true;
                    continue;
                }
                Like like = fixed;
                if (like == null) {
                    String t = (String) p.objects[i];
                    if (!t.equals(lastText)) {
                        last = new Like(t);
                        lastText = t;
                    }
                    like = last;
                }
                out.bools[i] = like.matches((String) x.objects[i], pos) != negated;
            }
            return out;
        }
    }

    // ---- choices ----

    /** {@code case when c1 then r1 ... else e end}; each result only worked out for its rows. */
    static final class Case extends Ev {
        private final Ev operand;
        private final Col[] slot;
        private final List<Ev> whens;
        private final List<Ev> thens;
        private final Ev otherwise;

        /** {@code operand} (or null) is worked out once into {@code slot}, which the 'when' comparisons read. */
        Case(Ev operand, Col[] slot, List<Ev> whens, List<Ev> thens, Ev otherwise, Type type, int pos) {
            super(type, pos);
            this.operand = operand;
            this.slot = slot;
            this.whens = whens;
            this.thens = thens;
            this.otherwise = otherwise;
        }

        @Override
        Col eval(Batch b, boolean[] mask) throws ExprException {
            int n = b.rows();
            if (operand != null) {
                slot[0] = operand.eval(b, mask);
            }
            Col out = Col.nulls(type, n);
            boolean[] left = new boolean[n];
            for (int i = 0; i < n; i++) {
                left[i] = on(mask, i);
            }
            for (int w = 0; w < whens.size(); w++) {
                Col cond = whens.get(w).eval(b, left);
                boolean[] hit = and(left, cond, true);
                Col val = thens.get(w).eval(b, hit);
                for (int i = 0; i < n; i++) {
                    if (hit[i]) {
                        out.copy(val, i, i);
                        left[i] = false;
                    }
                }
            }
            if (otherwise != null) {
                Col val = otherwise.eval(b, left);
                for (int i = 0; i < n; i++) {
                    if (left[i]) {
                        out.copy(val, i, i);
                    }
                }
            }
            return out;
        }
    }

    static final class Coalesce extends Ev {
        private final List<Ev> args;

        Coalesce(List<Ev> args, Type type, int pos) {
            super(type, pos);
            this.args = args;
        }

        @Override
        Col eval(Batch b, boolean[] mask) throws ExprException {
            int n = b.rows();
            Col out = Col.nulls(type, n);
            boolean[] left = new boolean[n];
            for (int i = 0; i < n; i++) {
                left[i] = on(mask, i);
            }
            for (Ev arg : args) {
                Col val = arg.eval(b, left);
                boolean any = false;
                for (int i = 0; i < n; i++) {
                    if (left[i] && !val.nulls[i]) {
                        out.copy(val, i, i);
                        left[i] = false;
                    }
                    any |= left[i];
                }
                if (!any) {
                    break;
                }
            }
            return out;
        }
    }

    // ---- functions ----

    /** A function worked out row by row on boxed values. */
    @FunctionalInterface
    interface RowFn {
        Object apply(Object[] args) throws ExprException;
    }

    static final class Call extends Ev {
        private final List<Ev> args;
        private final RowFn fn;
        private final boolean strict;
        private final Runnable beforeBatch;

        /**
         * @param strict whether a null argument makes the result null without calling {@code fn}
         */
        Call(List<Ev> args, Type type, boolean strict, RowFn fn, int pos) {
            this(args, type, strict, fn, pos, null);
        }

        /** @param beforeBatch run before each batch, to reset per-batch budgets; may be null */
        Call(List<Ev> args, Type type, boolean strict, RowFn fn, int pos, Runnable beforeBatch) {
            super(type, pos);
            this.args = args;
            this.fn = fn;
            this.strict = strict;
            this.beforeBatch = beforeBatch;
        }

        @Override
        Col eval(Batch b, boolean[] mask) throws ExprException {
            if (beforeBatch != null) {
                beforeBatch.run();
            }
            int n = b.rows();
            Col[] cols = new Col[args.size()];
            for (int a = 0; a < cols.length; a++) {
                cols[a] = args.get(a).eval(b, mask);
            }
            Col out = Col.blank(type, n);
            Object[] values = new Object[cols.length];
            rows:
            for (int i = 0; i < n; i++) {
                if (!on(mask, i)) {
                    out.nulls[i] = true;
                    continue;
                }
                for (int a = 0; a < cols.length; a++) {
                    values[a] = cols[a].get(i);
                    if (strict && values[a] == null) {
                        out.nulls[i] = true;
                        continue rows;
                    }
                }
                out.set(i, fn.apply(values));
            }
            return out;
        }
    }
}

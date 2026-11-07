package io.kilnmark.kiln.nodes.expr;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;

import io.kilnmark.kiln.nodes.expr.Type.Kind;

/**
 * Checks a parsed expression against the columns it will see and turns it into an {@link Ev}.
 * Every type rule lives here: implicit conversion only widens numbers (int64 to decimal to
 * float64) and dates to timestamps; anything else needs {@code cast}.
 */
final class Compiler {

    /** Functions, for "did you mean" and the reference. */
    static final Set<String> FUNCTIONS = Set.of("trim", "lower", "upper", "length", "substring", "replace", "coalesce",
        "nullif", "abs", "round", "floor", "ceil", "concat", "regex_match", "regex_replace", "now", "today",
        "date_trunc", "date_part", "parse_date", "parse_timestamp", "format_date", "if", "lpad", "rpad", "left", "right",
        "split_part", "starts_with", "ends_with", "contains", "greatest", "least", "date_add", "date_diff", "sha256");
    static final Set<String> AGGREGATES = Set.of("count", "count_distinct", "sum", "min", "max", "avg");

    /** The deepest an expression's operations may sit inside each other, counting chains like a + b + c. */
    static final int MAX_NODE_DEPTH = 1_000;
    /** The most parts (columns, values, operations, calls) one compiled expression may have. */
    static final int MAX_NODES = 10_000;

    /** The columns an expression can use. */
    static final class Scope {
        final Map<String, Type> types = new LinkedHashMap<>();
        final Map<String, String> unusable = new LinkedHashMap<>();

        static Scope of(Schema schema) {
            Scope s = new Scope();
            for (Field f : schema.getFields()) {
                Type t = Type.of(f.getType());
                if (t != null) {
                    s.types.putIfAbsent(f.getName(), t);
                } else {
                    s.unusable.putIfAbsent(f.getName(), f.getType().toString());
                }
            }
            return s;
        }

        static Scope of(Map<String, Type> columns) {
            Scope s = new Scope();
            s.types.putAll(columns);
            return s;
        }
    }

    /** Compiles aggregate calls, which only an aggregate node allows. */
    interface AggregateHook {
        Ev aggregate(Node.Call call) throws ExprException;

        /** Why a column cannot be used outside an aggregate call, or null if it can. */
        default String outside(String column) {
            return null;
        }
    }

    // Built once: formatting a number needs a lot of stack, and these are thrown at the deepest point.
    private static final String TOO_MANY_PARTS = "the expression has more than "
        + String.format(Locale.UK, "%,d", MAX_NODES) + " parts";
    private static final String TOO_DEEP = "the expression has more than "
        + String.format(Locale.UK, "%,d", MAX_NODE_DEPTH) + " operations inside each other";

    private final Scope scope;
    private final AggregateHook hook;
    private int depth;
    private int nodes;

    Compiler(Scope scope, AggregateHook hook) {
        this.scope = scope;
        this.hook = hook;
    }

    Ev compile(Node n) throws ExprException {
        if (++nodes > MAX_NODES) {
            throw new ExprException(TOO_MANY_PARTS, n.pos(), "split it over several columns");
        }
        if (++depth > MAX_NODE_DEPTH) {
            throw new ExprException(TOO_DEEP, n.pos(), "split it over several columns");
        }
        try {
            return switch (n) {
                case Node.Literal l -> new Ev.Const(l.type(), l.value(), l.pos());
                case Node.Column c -> column(c);
                case Node.Unary u -> unary(u);
                case Node.Binary b -> binary(b);
                case Node.IsNull i -> new Ev.IsNull(compile(i.operand()), i.negated(), i.pos());
                case Node.In i -> in(i);
                case Node.Between b -> between(b);
                case Node.Like l -> like(l);
                case Node.Case c -> caseOf(c);
                case Node.Cast c -> cast(c);
                case Node.Call c -> call(c);
            };
        } finally {
            depth--;
        }
    }

    private Ev column(Node.Column c) throws ExprException {
        Type t = scope.types.get(c.name());
        if (t == null) {
            String unusable = scope.unusable.get(c.name());
            if (unusable != null) {
                throw new ExprException("the column '" + c.name() + "' holds " + unusable
                    + " values, which expressions cannot use", c.pos());
            }
            List<String> all = new ArrayList<>(scope.types.keySet());
            all.addAll(scope.unusable.keySet());
            String near = suggest(c.name(), all);
            String hint = near != null ? "did you mean " + near + "?" : null;
            if (hint == null && Lexer.KEYWORDS.contains(c.name().toLowerCase(Locale.ROOT))) {
                hint = "quote it if it is a column: \"" + c.name() + "\"";
            }
            throw new ExprException("unknown column '" + c.name() + "'", c.pos(), hint);
        }
        if (hook != null) {
            String why = hook.outside(c.name());
            if (why != null) {
                throw new ExprException(why, c.pos());
            }
        }
        return new Ev.Column(c.name(), t, c.pos());
    }

    private Ev unary(Node.Unary u) throws ExprException {
        Ev x = compile(u.operand());
        if (u.op().equals("not")) {
            requireBoolean(x, "'not'");
            return new Ev.Not(Ev.widen(x, Type.BOOLEAN), u.pos());
        }
        if (x.type.isNull()) {
            return new Ev.Const(Type.NULL, null, u.pos());
        }
        if (!x.type.isNumeric()) {
            throw new ExprException("'-' needs a number, not " + article(x.type), u.pos());
        }
        return new Ev.Negate(x, u.pos());
    }

    private Ev binary(Node.Binary b) throws ExprException {
        Ev l = compile(b.left());
        Ev r = compile(b.right());
        String op = b.op();
        switch (op) {
            case "and", "or" -> {
                requireBoolean(l, "'" + op + "'");
                requireBoolean(r, "'" + op + "'");
                return new Ev.Logic(op.equals("and"), Ev.widen(l, Type.BOOLEAN), Ev.widen(r, Type.BOOLEAN), b.pos());
            }
            case "||" -> {
                for (Ev side : List.of(l, r)) {
                    if (side.type.kind() != Kind.UTF8 && !side.type.isNull()) {
                        throw new ExprException("'||' joins text, but one side is " + article(side.type), b.pos(),
                            "use concat(...), which takes any type, or cast(x as utf8)");
                    }
                }
                return new Ev.Concat(Ev.widen(l, Type.UTF8), Ev.widen(r, Type.UTF8), b.pos());
            }
            case "=", "<>", "<", "<=", ">", ">=" -> {
                Type t = Type.common(l.type, r.type);
                if (t == null) {
                    throw new ExprException("cannot compare " + article(l.type) + " with " + article(r.type), b.pos(),
                        "cast one side, like cast(x as " + r.type.family() + ")");
                }
                return new Ev.Compare(op, Ev.widen(l, t), Ev.widen(r, t), b.pos());
            }
            default -> {
                return arithmetic(op, l, r, b.pos());
            }
        }
    }

    private Ev arithmetic(String op, Ev l, Ev r, int pos) throws ExprException {
        Kind lk = l.type.kind();
        Kind rk = r.type.kind();
        // dates: date ± days, days + date, date - date
        if (op.equals("+") || op.equals("-")) {
            if (lk == Kind.DATE && (rk == Kind.INT64 || rk == Kind.NULL)) {
                return new Ev.DateArith(op, l, Ev.widen(r, Type.INT64), Type.DATE, pos);
            }
            if (op.equals("+") && lk == Kind.INT64 && rk == Kind.DATE) {
                return new Ev.DateArith(op, r, l, Type.DATE, pos);
            }
            if (op.equals("-") && lk == Kind.DATE && rk == Kind.DATE) {
                return new Ev.DateArith(op, l, r, Type.INT64, pos);
            }
        }
        for (Ev side : List.of(l, r)) {
            if (!side.type.isNumeric() && !side.type.isNull()) {
                String hint = side.type.kind() == Kind.UTF8 && op.equals("+") ? "join text with || or concat(...)"
                    : side.type.kind() == Kind.UTF8 ? "cast text to a number first, like cast(x as decimal(10,2))" : null;
                throw new ExprException("'" + op + "' needs numbers, but one side is " + article(side.type), pos, hint);
            }
        }
        if (l.type.isNull() && r.type.isNull()) {
            return new Ev.Const(Type.NULL, null, pos);
        }
        Type t = Type.common(l.type, r.type);
        if (op.equals("/") && t.kind() == Kind.INT64) {
            t = Type.FLOAT64; // 7 / 2 is 3.5, as most people expect
        }
        if (t.kind() != Kind.DECIMAL) {
            return new Ev.Arith(op, Ev.widen(l, t), Ev.widen(r, t), t, pos);
        }
        Type ld = asDecimal(l.type);
        Type rd = asDecimal(r.type);
        Type result = switch (op) {
            case "*" -> Type.decimal(ld.scale() + rd.scale());
            case "/" -> Type.decimal(Math.max(6, Math.max(ld.scale(), rd.scale())));
            default -> Type.decimal(Math.max(ld.scale(), rd.scale()));
        };
        return new Ev.Arith(op, Ev.widen(l, ld), Ev.widen(r, rd), result, pos);
    }

    private static Type asDecimal(Type t) {
        return switch (t.kind()) {
            case DECIMAL -> t;
            case NULL -> Type.decimal(0);
            default -> Type.decimal(19, 0);
        };
    }

    private Ev in(Node.In in) throws ExprException {
        Ev x = compile(in.operand());
        Type t = x.type;
        List<Ev> items = new ArrayList<>();
        for (Node item : in.items()) {
            Ev e = compile(item);
            Type c = Type.common(t, e.type);
            if (c == null) {
                throw new ExprException("cannot compare " + article(x.type) + " with " + article(e.type) + " in 'in'",
                    item.pos());
            }
            t = c;
            items.add(e);
        }
        List<Ev> widened = new ArrayList<>();
        for (Ev e : items) {
            widened.add(constantWiden(e, t));
        }
        return new Ev.In(Ev.widen(x, t), widened, in.negated(), in.pos());
    }

    /** Widens a constant now, so 'in' can look it up in a set; other values are widened as they are worked out. */
    private static Ev constantWiden(Ev e, Type t) throws ExprException {
        if (e instanceof Ev.Const c && !c.type.equals(t)) {
            if (c.value() == null) {
                return new Ev.Const(t, null, c.pos);
            }
            Col one = Col.blank(c.type, 1);
            one.set(0, c.value());
            return new Ev.Const(t, Casts.convert(one, t, c.pos).get(0), c.pos);
        }
        return Ev.widen(e, t);
    }

    private Ev between(Node.Between b) throws ExprException {
        Ev x = compile(b.operand()); // once: compiling it twice per between would grow without bound
        Ev low = compile(b.low());
        Ev high = compile(b.high());
        Type t = Type.common(x.type, low.type);
        t = t == null ? null : Type.common(t, high.type);
        if (t == null) {
            throw new ExprException("cannot compare " + article(x.type) + " with " + article(low.type) + " and "
                + article(high.type) + " in 'between'", b.pos());
        }
        return new Ev.Between(Ev.widen(x, t), Ev.widen(low, t), Ev.widen(high, t), b.negated(), b.pos());
    }

    @SuppressWarnings("ReferenceEquality") // which of the two sides, even when both are the same expression
    private Ev like(Node.Like l) throws ExprException {
        Ev x = compile(l.operand());
        Ev p = compile(l.pattern());
        for (Ev side : List.of(x, p)) {
            if (side.type.kind() != Kind.UTF8 && !side.type.isNull()) {
                throw new ExprException("'like' works on text, not " + article(side.type), l.pos(),
                    side == x ? "cast it first, like cast(x as utf8)" : null);
            }
        }
        return new Ev.LikeEv(Ev.widen(x, Type.UTF8), Ev.widen(p, Type.UTF8), l.negated(), l.pos());
    }

    private Ev caseOf(Node.Case c) throws ExprException {
        List<Ev> whens = new ArrayList<>();
        Ev operand = c.operand() == null ? null : compile(c.operand());
        // the operand is worked out once per batch and read by every 'when' through a slot
        Col[] slot = new Col[1];
        for (Node w : c.whens()) {
            if (operand != null) {
                Ev v = compile(w);
                Type t = Type.common(operand.type, v.type);
                if (t == null) {
                    throw new ExprException("cannot compare " + article(operand.type) + " with " + article(v.type)
                        + " in 'case'", w.pos());
                }
                Ev read = new Ev.Slot(slot, operand.type, operand.pos);
                whens.add(new Ev.Compare("=", Ev.widen(read, t), Ev.widen(v, t), w.pos()));
            } else {
                Ev cond = compile(w);
                if (cond.type.kind() != Kind.BOOLEAN && !cond.type.isNull()) {
                    throw new ExprException("a 'when' condition must be true or false, not " + article(cond.type),
                        w.pos(), "compare it, like when status = 'open' then ...");
                }
                whens.add(Ev.widen(cond, Type.BOOLEAN));
            }
        }
        List<Ev> thens = new ArrayList<>();
        Type t = Type.NULL;
        List<Node> results = new ArrayList<>(c.thens());
        if (c.otherwise() != null) {
            results.add(c.otherwise());
        }
        List<Ev> compiled = new ArrayList<>();
        for (Node r : results) {
            Ev e = compile(r);
            Type next = Type.common(t, e.type);
            if (next == null) {
                throw new ExprException("the results of 'case' must have one type, but one is " + article(t)
                    + " and another " + article(e.type), r.pos(), "cast them to one type");
            }
            t = next;
            compiled.add(e);
        }
        for (int i = 0; i < c.thens().size(); i++) {
            thens.add(Ev.widen(compiled.get(i), t));
        }
        Ev otherwise = c.otherwise() == null ? null : Ev.widen(compiled.getLast(), t);
        return new Ev.Case(operand, slot, whens, thens, otherwise, t, c.pos());
    }

    private Ev cast(Node.Cast c) throws ExprException {
        Ev x = compile(c.operand());
        if (!Casts.canCast(x.type, c.target())) {
            throw new ExprException("cannot cast " + article(x.type) + " to " + c.target().family(), c.pos());
        }
        if (x.type.equals(c.target())) {
            return x;
        }
        if (x instanceof Ev.Const k && k.value() == null) {
            return new Ev.Const(c.target(), null, c.pos());
        }
        return new Ev.Convert(new Positioned(x, c.pos()), c.target());
    }

    /** Reports errors at the position of the cast rather than its operand. */
    private static final class Positioned extends Ev {
        private final Ev inner;

        Positioned(Ev inner, int pos) {
            super(inner.type, pos);
            this.inner = inner;
        }

        @Override
        Col eval(Batch b, boolean[] mask) throws ExprException {
            return inner.eval(b, mask);
        }
    }

    // ---- functions ----

    private Ev call(Node.Call c) throws ExprException {
        String fn = c.name();
        if (AGGREGATES.contains(fn)) {
            if (hook == null) {
                throw new ExprException(fn + "(...) summarises many rows, so it can only be used in an aggregate node's "
                    + "values", c.pos());
            }
            return hook.aggregate(c);
        }
        if (Windowing.OWN.contains(fn)) {
            throw new ExprException(fn + "(...) looks at other rows, so it can only be used in a window node's columns",
                c.pos());
        }
        if (c.star()) {
            throw new ExprException("only count(*) takes a star", c.pos());
        }
        if (!FUNCTIONS.contains(fn)) {
            List<String> all = new ArrayList<>(FUNCTIONS);
            all.addAll(AGGREGATES);
            String near = suggest(fn, all);
            throw new ExprException("unknown function '" + fn + "'", c.pos(), near != null ? "did you mean " + near + "?"
                : null);
        }
        if (fn.equals("if")) {
            return ifOf(c);
        }
        List<Ev> args = new ArrayList<>();
        for (Node a : c.args()) {
            args.add(compile(a));
        }
        int pos = c.pos();
        return switch (fn) {
            case "trim", "lower", "upper" -> {
                arity(c, 1, 1);
                Ev s = text(c, args, 0);
                yield new Ev.Call(List.of(s), Type.UTF8, true, switch (fn) {
                    case "trim" -> a -> ((String) a[0]).strip();
                    case "lower" -> a -> ((String) a[0]).toLowerCase(Locale.ROOT);
                    default -> a -> ((String) a[0]).toUpperCase(Locale.ROOT);
                }, pos);
            }
            case "length" -> {
                arity(c, 1, 1);
                yield new Ev.Call(List.of(text(c, args, 0)), Type.INT64, true,
                    a -> (long) ((String) a[0]).codePointCount(0, ((String) a[0]).length()), pos);
            }
            case "substring" -> {
                arity(c, 2, 3);
                List<Ev> in = new ArrayList<>(List.of(text(c, args, 0), whole(c, args, 1)));
                if (args.size() == 3) {
                    in.add(whole(c, args, 2));
                }
                yield new Ev.Call(in, Type.UTF8, true, a -> substring((String) a[0], (Long) a[1],
                    a.length > 2 ? (Long) a[2] : null, pos), pos);
            }
            case "replace" -> {
                arity(c, 3, 3);
                TextBudget budget = new TextBudget();
                yield new Ev.Call(List.of(text(c, args, 0), text(c, args, 1), text(c, args, 2)), Type.UTF8, true,
                    a -> Casts.replace((String) a[0], (String) a[1], (String) a[2], pos, budget), pos, budget::newBatch);
            }
            case "coalesce" -> {
                arity(c, 1, Integer.MAX_VALUE);
                Type t = commonOf(c, args);
                List<Ev> w = new ArrayList<>();
                for (Ev e : args) {
                    w.add(Ev.widen(e, t));
                }
                yield new Ev.Coalesce(w, t, pos);
            }
            case "nullif" -> {
                arity(c, 2, 2);
                Type t = commonOf(c, args);
                Ev a0 = Ev.widen(args.get(0), t);
                Ev a1 = Ev.widen(args.get(1), t);
                Col probe = Col.blank(t, 2);
                yield new Ev.Call(List.of(a0, a1), t, false, a -> {
                    if (a[0] == null || a[1] == null) {
                        return a[0];
                    }
                    probe.set(0, a[0]);
                    probe.set(1, a[1]);
                    return Ev.compare(probe, 0, probe, 1) == 0 ? null : a[0];
                }, pos);
            }
            case "abs" -> {
                arity(c, 1, 1);
                Ev x = number(c, args, 0);
                yield new Ev.Call(List.of(x), x.type, true, a -> switch (a[0]) {
                    case Long v -> {
                        if (v == Long.MIN_VALUE) {
                            throw Ev.overflow(pos);
                        }
                        yield Math.abs(v);
                    }
                    case Double v -> Math.abs(v);
                    default -> ((BigDecimal) a[0]).abs();
                }, pos);
            }
            case "round" -> round(c, args);
            case "floor", "ceil" -> {
                arity(c, 1, 1);
                Ev x = number(c, args, 0);
                boolean floor = fn.equals("floor");
                Type t = x.type.kind() == Kind.DECIMAL ? Type.decimal(0) : x.type;
                RoundingMode mode = floor ? RoundingMode.FLOOR : RoundingMode.CEILING;
                yield new Ev.Call(List.of(x), t, true, a -> switch (a[0]) {
                    case Long v -> v;
                    case Double v -> floor ? Math.floor(v) : Math.ceil(v);
                    default -> ((BigDecimal) a[0]).setScale(0, mode);
                }, pos);
            }
            case "concat" -> {
                arity(c, 1, Integer.MAX_VALUE);
                List<Ev> in = List.copyOf(args);
                Col[] holder = new Col[1];
                TextBudget budget = new TextBudget();
                yield new Ev.Call(in, Type.UTF8, false, a -> {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < a.length; i++) {
                        if (a[i] != null) {
                            Type t = in.get(i).type;
                            if (holder[0] == null || !holder[0].type.equals(t)) {
                                holder[0] = Col.blank(t, 1);
                            }
                            holder[0].set(0, a[i]);
                            sb.append(Casts.text(holder[0], 0));
                            if (sb.length() > Casts.MAX_TEXT) {
                                throw Casts.tooLong(pos);
                            }
                        }
                    }
                    budget.spend(sb.length(), pos);
                    return sb.toString();
                }, pos, budget::newBatch);
            }
            case "regex_match" -> {
                arity(c, 2, 2);
                Ev s = text(c, args, 0);
                Ev p = text(c, args, 1);
                Regexes.Rx fixed = literalText(c, 1) != null ? Regexes.compile(literalText(c, 1), pos) : null;
                Regexes rx = new Regexes();
                yield new Ev.Call(List.of(s, p), Type.BOOLEAN, true, a -> rx.find(
                    fixed != null ? fixed : rx.pattern((String) a[1], pos), (String) a[0], pos), pos, rx::newBatch);
            }
            case "regex_replace" -> {
                arity(c, 3, 3);
                Ev s = text(c, args, 0);
                Ev p = text(c, args, 1);
                Ev r = text(c, args, 2);
                Regexes.Rx fixed = literalText(c, 1) != null ? Regexes.compile(literalText(c, 1), pos) : null;
                if (fixed != null && literalText(c, 2) != null) {
                    Regexes.checkReplacement(fixed.pattern(), literalText(c, 2), pos);
                }
                Regexes rx = new Regexes();
                yield new Ev.Call(List.of(s, p, r), Type.UTF8, true, a -> rx.replace(
                    fixed != null ? fixed : rx.pattern((String) a[1], pos), (String) a[0], (String) a[2], pos), pos,
                    rx::newBatch);
            }
            case "now" -> {
                arity(c, 0, 0);
                yield new Ev.Now(Type.TIMESTAMP, pos);
            }
            case "today" -> {
                arity(c, 0, 0);
                yield new Ev.Now(Type.DATE, pos);
            }
            case "date_trunc", "date_part" -> {
                arity(c, 2, 2);
                String unit = literalText(c, 0);
                Set<String> units = fn.equals("date_trunc") ? Temporal.TRUNC_UNITS : Temporal.PART_UNITS;
                if (unit == null || !units.contains(unit.toLowerCase(Locale.ROOT))) {
                    throw new ExprException(fn + " needs a unit in quotes as its first argument: one of "
                        + String.join(", ", units.stream().sorted().toList()), c.args().getFirst().pos());
                }
                String u = unit.toLowerCase(Locale.ROOT);
                Ev x = temporal(c, args, 1);
                boolean isDate = x.type.kind() == Kind.DATE;
                if (isDate && Temporal.TIME_UNITS.contains(u)) {
                    throw new ExprException(fn + ": a date has no " + u + "s", c.args().getFirst().pos(),
                        "cast it to a timestamp first");
                }
                if (fn.equals("date_trunc")) {
                    yield new Ev.Call(List.of(x), x.type, true, a -> isDate ? Temporal.truncDate(u, (Long) a[0])
                        : Temporal.truncTimestamp(u, (Long) a[0]), pos);
                }
                yield new Ev.Call(List.of(x), Type.INT64, true, a -> isDate ? Temporal.partDate(u, (Long) a[0])
                    : Temporal.partTimestamp(u, (Long) a[0]), pos);
            }
            case "parse_date", "parse_timestamp" -> {
                arity(c, 1, 2);
                Ev s = text(c, args, 0);
                DateTimeFormatter f = args.size() > 1 ? datePattern(c, 1) : null;
                if (fn.equals("parse_date")) {
                    yield new Ev.Call(List.of(s), Type.DATE, true, a -> Temporal.parseDate((String) a[0], f), pos);
                }
                yield new Ev.Call(List.of(s), Type.TIMESTAMP, true, a -> Temporal.parseTimestamp((String) a[0], f), pos);
            }
            case "format_date" -> {
                arity(c, 2, 2);
                Ev x = temporal(c, args, 0);
                DateTimeFormatter f = datePattern(c, 1);
                Kind k = x.type.kind();
                yield new Ev.Call(List.of(x), Type.UTF8, true, a -> {
                    try {
                        return Temporal.format(k, (Long) a[0], f);
                    } catch (ExprException e) {
                        throw new ExprException(e.problem(), pos);
                    }
                }, pos);
            }
            case "lpad", "rpad" -> {
                arity(c, 2, 3);
                List<Ev> in = new ArrayList<>(List.of(text(c, args, 0), whole(c, args, 1)));
                if (args.size() == 3) {
                    in.add(text(c, args, 2));
                }
                boolean left = fn.equals("lpad");
                TextBudget budget = new TextBudget();
                yield new Ev.Call(in, Type.UTF8, true, a -> Texts.pad((String) a[0], (Long) a[1],
                    a.length > 2 ? (String) a[2] : " ", left, pos, budget), pos, budget::newBatch);
            }
            case "left", "right" -> {
                arity(c, 2, 2);
                boolean left = fn.equals("left");
                yield new Ev.Call(List.of(text(c, args, 0), whole(c, args, 1)), Type.UTF8, true,
                    a -> Texts.side((String) a[0], (Long) a[1], left), pos);
            }
            case "split_part" -> {
                arity(c, 3, 3);
                if (c.args().get(2) instanceof Node.Literal l && Long.valueOf(0).equals(l.value())) {
                    throw new ExprException("split_part's field number cannot be 0", c.args().get(2).pos(),
                        "count fields from 1, or from -1 for the last");
                }
                yield new Ev.Call(List.of(text(c, args, 0), text(c, args, 1), whole(c, args, 2)), Type.UTF8, true,
                    a -> Texts.splitPart((String) a[0], (String) a[1], (Long) a[2], pos), pos);
            }
            case "starts_with", "ends_with", "contains" -> {
                arity(c, 2, 2);
                yield new Ev.Call(List.of(text(c, args, 0), text(c, args, 1)), Type.BOOLEAN, true, switch (fn) {
                    case "starts_with" -> a -> ((String) a[0]).startsWith((String) a[1]);
                    case "ends_with" -> a -> ((String) a[0]).endsWith((String) a[1]);
                    default -> a -> Texts.contains((String) a[0], (String) a[1]);
                }, pos);
            }
            case "greatest", "least" -> {
                arity(c, 1, Integer.MAX_VALUE);
                Type t = commonOf(c, args);
                List<Ev> w = new ArrayList<>();
                for (Ev e : args) {
                    w.add(Ev.widen(e, t));
                }
                boolean greatest = fn.equals("greatest");
                Col probe = Col.blank(t, 2);
                yield new Ev.Call(w, t, false, a -> {
                    Object best = null;
                    for (Object v : a) {
                        if (v == null) {
                            continue;
                        }
                        if (best == null) {
                            best = v;
                            continue;
                        }
                        probe.set(0, v);
                        probe.set(1, best);
                        int cmp = Ev.compare(probe, 0, probe, 1);
                        if (greatest ? cmp > 0 : cmp < 0) {
                            best = v;
                        }
                    }
                    return best;
                }, pos);
            }
            case "date_add" -> {
                arity(c, 3, 3);
                Ev x = temporal(c, args, 0);
                Ev n = whole(c, args, 1);
                String u = stepUnit(c, 2);
                boolean isDate = x.type.kind() == Kind.DATE;
                if (isDate && Temporal.TIME_UNITS.contains(u)) {
                    throw new ExprException("date_add: a date has no " + u + "s", c.args().get(2).pos(),
                        "cast it to a timestamp first");
                }
                yield new Ev.Call(List.of(x, n), x.type, true, a -> isDate
                    ? Temporal.addDate(u, (Long) a[0], (Long) a[1], pos)
                    : Temporal.addTimestamp(u, (Long) a[0], (Long) a[1], pos), pos);
            }
            case "date_diff" -> {
                arity(c, 3, 3);
                String u = stepUnit(c, 0);
                Ev a0 = temporal(c, args, 1);
                Ev b0 = temporal(c, args, 2);
                boolean dates = a0.type.kind() == Kind.DATE && b0.type.kind() == Kind.DATE
                    && !Temporal.TIME_UNITS.contains(u);
                Type t = dates ? Type.DATE : Type.TIMESTAMP;
                yield new Ev.Call(List.of(Ev.widen(a0, t), Ev.widen(b0, t)), Type.INT64, true, a -> dates
                    ? Temporal.diffDate(u, (Long) a[0], (Long) a[1])
                    : Temporal.diffTimestamp(u, (Long) a[0], (Long) a[1]), pos);
            }
            case "sha256" -> {
                arity(c, 1, 1);
                java.security.MessageDigest digest = Texts.sha256();
                yield new Ev.Call(List.of(text(c, args, 0)), Type.UTF8, true, a -> Texts.sha256(digest, (String) a[0]),
                    pos);
            }
            default -> throw new IllegalStateException("function without a body: " + fn);
        };
    }

    /**
     * {@code if(cond, a, b)}: {@code a} where {@code cond} is true, {@code b} where it is false or
     * empty, as {@code case when cond then a else b end}; only the branch a row takes is worked out.
     */
    private Ev ifOf(Node.Call c) throws ExprException {
        arity(c, 3, 3);
        Ev cond = compile(c.args().get(0));
        if (cond.type.kind() != Kind.BOOLEAN && !cond.type.isNull()) {
            throw new ExprException("if needs a condition that is true or false as its first argument, not "
                + article(cond.type), c.args().get(0).pos(), "compare it, like if(status = 'open', ...)");
        }
        Ev a = compile(c.args().get(1));
        Ev b = compile(c.args().get(2));
        Type t = Type.common(a.type, b.type);
        if (t == null) {
            throw new ExprException("if needs its two results to have one type, but one is " + article(a.type)
                + " and the other " + article(b.type), c.args().get(2).pos(), "cast them to one type");
        }
        return new Ev.Case(null, new Col[1], List.of(Ev.widen(cond, Type.BOOLEAN)), List.of(Ev.widen(a, t)),
            Ev.widen(b, t), t, c.pos());
    }

    /** The unit {@code date_add} or {@code date_diff} names, written out in quotes. */
    private static String stepUnit(Node.Call c, int i) throws ExprException {
        String unit = literalText(c, i);
        if (unit == null || !Temporal.STEP_UNITS.contains(unit.toLowerCase(Locale.ROOT))) {
            throw new ExprException(c.name() + " needs a unit in quotes as argument " + (i + 1) + ": one of "
                + String.join(", ", Temporal.STEP_UNITS.stream().sorted().toList()), c.args().get(i).pos());
        }
        return unit.toLowerCase(Locale.ROOT);
    }

    private Ev round(Node.Call c, List<Ev> args) throws ExprException {
        arity(c, 1, 2);
        Ev x = number(c, args, 0);
        int digits = 0;
        if (args.size() == 2) {
            if (!(c.args().get(1) instanceof Node.Literal l) || !(l.value() instanceof Long d) || d < -18 || d > 18) {
                throw new ExprException("round's second argument must be a whole number from -18 to 18, written out",
                    c.args().get(1).pos());
            }
            digits = (int) (long) d;
        }
        int d = digits;
        Type t = x.type.kind() == Kind.DECIMAL ? Type.decimal(Math.max(0, d)) : x.type;
        return new Ev.Call(List.of(x), t, true, a -> switch (a[0]) {
            case Long v -> d >= 0 ? v : roundLong(v, d, c.pos());
            case Double v -> Double.isNaN(v) || Double.isInfinite(v) ? v
                : BigDecimal.valueOf(v).setScale(d, RoundingMode.HALF_UP).doubleValue();
            default -> ((BigDecimal) a[0]).setScale(d, RoundingMode.HALF_UP).setScale(Math.max(0, d), RoundingMode.UNNECESSARY);
        }, c.pos());
    }

    private static long roundLong(long v, int digits, int pos) throws ExprException {
        try {
            return BigDecimal.valueOf(v).setScale(digits, RoundingMode.HALF_UP).longValueExact();
        } catch (ArithmeticException e) {
            throw Ev.overflow(pos);
        }
    }

    private static String substring(String s, long start, Long length, int pos) throws ExprException {
        if (length != null && length < 0) {
            throw new ExprException("substring's length cannot be negative", pos);
        }
        int n = s.codePointCount(0, s.length());
        long from = Math.max(start, 1);
        long end = length == null || start > Long.MAX_VALUE - length ? Long.MAX_VALUE : start + length;
        long to = Math.min((long) n + 1, end);
        if (from > n || to <= from) {
            return "";
        }
        int a = s.offsetByCodePoints(0, (int) (from - 1));
        int b = s.offsetByCodePoints(0, (int) (to - 1));
        return s.substring(a, b);
    }

    // ---- argument helpers ----

    private static void arity(Node.Call c, int min, int max) throws ExprException {
        int n = c.args().size();
        if (n < min || n > max) {
            String want = min == max ? min + " argument" + (min == 1 ? "" : "s")
                : max == Integer.MAX_VALUE ? "at least " + min + " argument" + (min == 1 ? "" : "s")
                : min + " to " + max + " arguments";
            throw new ExprException(c.name() + " takes " + want + ", not " + n, c.pos());
        }
    }

    private static Ev text(Node.Call c, List<Ev> args, int i) throws ExprException {
        Ev e = args.get(i);
        if (e.type.kind() != Kind.UTF8 && !e.type.isNull()) {
            throw new ExprException(c.name() + " needs text as argument " + (i + 1) + ", not " + article(e.type),
                c.args().get(i).pos(), "cast it first, like cast(x as utf8)");
        }
        return Ev.widen(e, Type.UTF8);
    }

    private static Ev whole(Node.Call c, List<Ev> args, int i) throws ExprException {
        Ev e = args.get(i);
        if (e.type.kind() != Kind.INT64 && !e.type.isNull()) {
            throw new ExprException(c.name() + " needs a whole number as argument " + (i + 1) + ", not "
                + article(e.type), c.args().get(i).pos());
        }
        return Ev.widen(e, Type.INT64);
    }

    private static Ev number(Node.Call c, List<Ev> args, int i) throws ExprException {
        Ev e = args.get(i);
        if (e.type.isNull()) {
            return Ev.widen(e, Type.INT64);
        }
        if (!e.type.isNumeric()) {
            throw new ExprException(c.name() + " needs a number, not " + article(e.type), c.args().get(i).pos());
        }
        return e;
    }

    private static Ev temporal(Node.Call c, List<Ev> args, int i) throws ExprException {
        Ev e = args.get(i);
        if (e.type.isNull()) {
            return Ev.widen(e, Type.TIMESTAMP);
        }
        if (e.type.kind() != Kind.DATE && e.type.kind() != Kind.TIMESTAMP) {
            throw new ExprException(c.name() + " needs a date or timestamp, not " + article(e.type),
                c.args().get(i).pos(), e.type.kind() == Kind.UTF8 ? "read text with parse_date or parse_timestamp first"
                    : null);
        }
        return e;
    }

    private static Type commonOf(Node.Call c, List<Ev> args) throws ExprException {
        Type t = Type.NULL;
        for (int i = 0; i < args.size(); i++) {
            Type next = Type.common(t, args.get(i).type);
            if (next == null) {
                throw new ExprException(c.name() + " needs arguments of one type, but has " + article(t) + " and "
                    + article(args.get(i).type), c.args().get(i).pos(), "cast them to one type");
            }
            t = next;
        }
        return t;
    }

    /** The text of a literal argument, or null if the argument is not literal text. */
    private static String literalText(Node.Call c, int i) {
        return c.args().get(i) instanceof Node.Literal l && l.value() instanceof String s ? s : null;
    }

    private static DateTimeFormatter datePattern(Node.Call c, int i) throws ExprException {
        String p = literalText(c, i);
        if (p == null) {
            throw new ExprException(c.name() + " needs its pattern written out in quotes, like 'dd/MM/yyyy'",
                c.args().get(i).pos());
        }
        try {
            return Temporal.pattern(p);
        } catch (IllegalArgumentException e) {
            throw new ExprException(c.name() + ": the pattern '" + p + "' is not valid: " + e.getMessage(),
                c.args().get(i).pos(), "use letters such as yyyy, MM, dd, HH, mm, ss; put other words in single quotes");
        }
    }

    private static void requireBoolean(Ev e, String what) throws ExprException {
        if (e.type.kind() != Kind.BOOLEAN && !e.type.isNull()) {
            throw new ExprException(what + " needs true or false values, not " + article(e.type), e.pos,
                e.type.kind() == Kind.UTF8 ? "compare it, like x = 'yes'" : null);
        }
    }

    static String article(Type t) {
        return switch (t.kind()) {
            case NULL -> "null";
            case INT64 -> "an int64";
            case UTF8 -> "utf8 (text)";
            default -> "a " + t.family();
        };
    }

    static String suggest(String bad, Collection<String> names) {
        return Names.suggest(bad, names);
    }
}

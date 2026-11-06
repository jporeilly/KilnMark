package io.kilnmark.kiln.nodes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.nodes.expr.ExprException;
import io.kilnmark.kiln.nodes.expr.Expression;

/**
 * Reads a node's settings as written in the pipeline YAML (maps, lists, strings, numbers,
 * booleans) and collects problems in plain words, each starting with the setting's key, as
 * {@link io.kilnmark.kiln.NodeFactory#check} reports them. Settings given as flat strings (the
 * linear runner's) work too: a list may be written {@code a, b, c}.
 */
final class Config {

    private final Map<String, Object> raw;
    private final List<String> problems = new ArrayList<>();

    Config(Map<String, Object> raw) {
        this.raw = raw == null ? Map.of() : raw;
    }

    List<String> problems() {
        return problems;
    }

    void problem(String key, String message) {
        problems.add(key + ": " + message);
    }

    boolean has(String key) {
        Object v = raw.get(key);
        return v != null && !(v instanceof String s && s.isBlank());
    }

    /** Throws the problems found so far, if any, as one exception. */
    void orThrow(String type) throws NodeException {
        if (!problems.isEmpty()) {
            throw new NodeException("The " + type + " node's settings have problems: " + String.join("; ", problems));
        }
    }

    String text(String key, boolean required) {
        Object v = raw.get(key);
        if (v == null || (v instanceof String s && s.isBlank())) {
            if (required) {
                problem(key, "is required");
            }
            return null;
        }
        if (v instanceof Map<?, ?> || v instanceof List<?>) {
            problem(key, "must be a single value, not a " + (v instanceof Map<?, ?> ? "map" : "list"));
            return null;
        }
        return String.valueOf(v);
    }

    Expression expression(String key, boolean required) {
        String text = text(key, required);
        if (text == null) {
            return null;
        }
        try {
            return Expression.parse(text);
        } catch (ExprException e) {
            problem(key, e.getMessage());
            return null;
        }
    }

    /** A list of column names; null when the setting is absent. */
    List<String> columns(String key, boolean required) {
        Object v = raw.get(key);
        if (v == null || (v instanceof String s && s.isBlank())) {
            if (required) {
                problem(key, "is required");
            }
            return null;
        }
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o == null || o instanceof Map<?, ?> || o instanceof List<?>) {
                    problem(key, "must be a list of column names");
                    return null;
                }
                out.add(String.valueOf(o));
            }
        } else if (v instanceof Map<?, ?>) {
            problem(key, "must be a list of column names, not a map");
            return null;
        } else {
            Arrays.stream(String.valueOf(v).split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(out::add);
        }
        List<String> seen = new ArrayList<>();
        for (String c : out) {
            if (c.isBlank()) {
                problem(key, "has an empty column name");
                return null;
            }
            if (seen.contains(c)) {
                problem(key, "names the column '" + c + "' twice");
                return null;
            }
            seen.add(c);
        }
        if (required && out.isEmpty()) {
            problem(key, "needs at least one column");
        }
        return out;
    }

    /** One column to order rows by: its name, its direction, and where its nulls go. */
    record OrderTerm(String column, boolean descending, boolean nullsFirst) {

        /** As written in a pipeline: {@code amount desc nulls first}, leaving out what is the default. */
        @Override
        public String toString() {
            return column + (descending ? " desc" : "") + (nullsFirst ? " nulls first" : "");
        }
    }

    private static final java.util.regex.Pattern ORDER_TERM = java.util.regex.Pattern.compile(
        "(?i)^(.*?)(?:\\s+(asc|desc))?(?:\\s+nulls\\s+(first|last))?$", java.util.regex.Pattern.DOTALL);

    /**
     * Columns to order by, each written as in SQL's {@code order by}: a column, then perhaps
     * {@code asc} or {@code desc}, then perhaps {@code nulls first} or {@code nulls last}. Ascending
     * with nulls last unless the entry says otherwise. Null when the setting is absent.
     */
    List<OrderTerm> order(String key, boolean required) {
        List<String> entries = columns(key, required);
        if (entries == null) {
            return null;
        }
        List<OrderTerm> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (String entry : entries) {
            java.util.regex.Matcher m = ORDER_TERM.matcher(entry.strip());
            if (!m.matches() || m.group(1).isBlank()) {
                problem(key, "'" + entry + "' names no column; write a column, then perhaps desc or nulls first");
                return null;
            }
            String column = m.group(1).strip();
            if (seen.contains(column)) {
                problem(key, "names the column '" + column + "' twice");
                return null;
            }
            seen.add(column);
            boolean desc = "desc".equalsIgnoreCase(m.group(2));
            boolean nullsFirst = "first".equalsIgnoreCase(m.group(3));
            out.add(new OrderTerm(column, desc, nullsFirst));
        }
        return out;
    }

    /** A map of names to text, in the order written; null when the setting is absent. */
    LinkedHashMap<String, String> map(String key, boolean required) {
        Object v = raw.get(key);
        if (v == null || (v instanceof String s && s.isBlank())) {
            if (required) {
                problem(key, "is required");
            }
            return null;
        }
        if (!(v instanceof Map<?, ?> m)) {
            problem(key, "must be a map of names to values, like { a: b }");
            return null;
        }
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String name = String.valueOf(e.getKey());
            Object value = e.getValue();
            if (name.isBlank()) {
                problem(key, "has an empty name");
                return null;
            }
            if (value == null || value instanceof Map<?, ?> || value instanceof List<?>
                || String.valueOf(value).isBlank()) {
                problem(key + "." + name, "needs a value");
                return null;
            }
            out.put(name, String.valueOf(value));
        }
        if (required && out.isEmpty()) {
            problem(key, "needs at least one entry");
        }
        return out;
    }

    /** A map of names to expressions, each parsed; null when absent or when one does not parse. */
    LinkedHashMap<String, Expression> expressions(String key, boolean required) {
        LinkedHashMap<String, String> texts = map(key, required);
        if (texts == null) {
            return null;
        }
        LinkedHashMap<String, Expression> out = new LinkedHashMap<>();
        boolean ok = true;
        for (Map.Entry<String, String> e : texts.entrySet()) {
            try {
                out.put(e.getKey(), Expression.parse(e.getValue()));
            } catch (ExprException ex) {
                problem(key + "." + e.getKey(), ex.getMessage());
                ok = false;
            }
        }
        return ok ? out : null;
    }

    long number(String key, long fallback, long min, long max) {
        Object v = raw.get(key);
        if (v == null || (v instanceof String s && s.isBlank())) {
            return fallback;
        }
        long n;
        if (v instanceof Number num) {
            // YAML numbers arrive as Integer, Long, BigInteger, Double or BigDecimal
            try {
                n = new java.math.BigDecimal(num.toString()).longValueExact();
            } catch (ArithmeticException | NumberFormatException e) {
                problem(key, "must be a whole number");
                return fallback;
            }
        } else {
            try {
                n = Long.parseLong(String.valueOf(v).trim());
            } catch (NumberFormatException e) {
                problem(key, "must be a whole number, not '" + v + "'");
                return fallback;
            }
        }
        if (n < min || n > max) {
            problem(key, "must be from " + min + " to " + max);
            return fallback;
        }
        return n;
    }

    boolean bool(String key, boolean fallback) {
        Object v = raw.get(key);
        if (v == null || (v instanceof String s && s.isBlank())) {
            return fallback;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(v).trim();
        if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(s);
        }
        problem(key, "must be true or false");
        return fallback;
    }

    String choice(String key, List<String> choices, String fallback) {
        String v = text(key, false);
        if (v == null) {
            return fallback;
        }
        if (!choices.contains(v)) {
            problem(key, "must be one of " + String.join(", ", choices) + ", not '" + v + "'");
            return fallback;
        }
        return v;
    }
}

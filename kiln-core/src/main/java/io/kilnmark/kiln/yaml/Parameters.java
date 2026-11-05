package io.kilnmark.kiln.yaml;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parameters of pipelines and flows (docs/design/pipelines.md, "Parameters"): how they are declared
 * ({@code parameters: { region: { type: text, default: EMEA } }}), what values a run starts with, and
 * how {@code ${name}} puts a value into a setting.
 *
 * <p>A value only ever fills a value. In an expression it goes in as a literal ({@code 'EMEA'},
 * {@code 0.02}, {@code true}), never as text the expression reads as code; in a text setting it may
 * hold only letters, digits, spaces, {@code _}, {@code -} and {@code .} (not {@code ..}), so it can
 * name part of a path or table but never step outside it. <code>$${</code> writes a plain <code>${</code>.
 */
public final class Parameters {

    /** A parameter name: a lower-case letter, then up to 63 lower-case letters, digits and underscores. */
    public static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    /** Words a condition reads as part of its language, so never parameter names. */
    public static final Set<String> RESERVED = Set.of("and", "or", "not", "true", "false", "good", "fair", "poor");
    /** Most parameters one pipeline or flow declares. */
    public static final int MAX = 50;
    /** Longest text value. */
    public static final int MAX_TEXT = 1_000;
    /** Most values in a list parameter (an expression is at most 10,000 characters, lists included). */
    public static final int MAX_LIST = 200;
    /** Largest number a parameter holds, so it is written in full and read back the same. */
    static final double MAX_NUMBER = 1e15;
    static final Set<String> KEYS = Set.of("type", "default", "description");

    /** {@code ${name}}, or <code>$${</code> for a plain <code>${</code>. */
    private static final Pattern REF = Pattern.compile("\\$\\$\\{|\\$\\{([^}]*)}");
    private static final Pattern PLAIN_TEXT = Pattern.compile("[A-Za-z0-9 _.-]{1,200}");

    private Parameters() {
    }

    /** How a server variable is named in ${...}: {@code ${vars.target_schema}}. */
    public static final String VARS = "vars.";

    /**
     * A parameter: its name, kind, whether it holds a list of values of that kind ({@code text list},
     * {@code number list}), its default (null for none, a {@link VarRef} for a server variable's value)
     * and description.
     */
    public record Parameter(String name, FlowCondition.Type type, boolean list, Object defaultValue,
                            String description, Position position) {

        /** The kind in words, as written in {@code type:}. */
        public String kind() {
            String t = type.name().toLowerCase(Locale.ROOT);
            return list ? t + " list" : t;
        }
    }

    /** A default that is a server variable's value when the run starts: {@code default: ${vars.region}}. */
    public record VarRef(String variable) {
    }

    // ---- declaring ----

    /**
     * Reads a {@code parameters:} mapping. A pipeline's parameters need a default, so a pipeline still
     * runs on its own; a flow's may leave it out, and a run must then give the value.
     */
    static List<Parameter> read(YamlReader.Entry e, String what, boolean defaultNeeded, List<Problem> problems) {
        List<Parameter> out = new ArrayList<>();
        if (e == null) {
            return out;
        }
        if (!(e.value() instanceof YamlReader.Mapping m)) {
            problems.add(Problem.at(e.value().position(), null,
                "parameters is a mapping of names, such as parameters: { region: { type: text, default: EMEA } }"));
            return out;
        }
        if (m.entries().size() > MAX) {
            problems.add(Problem.at(m.position(), null, "A " + what + " has at most " + MAX + " parameters"));
            return out;
        }
        for (YamlReader.Entry p : m.entries().values()) {
            if (RESERVED.contains(p.key())) {
                problems.add(Problem.at(p.keyPosition(), null, "'" + p.key() + "' cannot be a parameter name: a condition "
                    + "reads it as a word of the language (and, or, not, true, false, good, fair, poor)"));
                continue;
            }
            if (!NAME.matcher(p.key()).matches()) {
                problems.add(Problem.at(p.keyPosition(), null, "The parameter name '" + Suggest.clip(p.key())
                    + "' must start with a lower-case letter and use only a-z, 0-9 and _, up to 64 characters"));
                continue;
            }
            if (!(p.value() instanceof YamlReader.Mapping spec)) {
                problems.add(Problem.at(p.value().position(), null, "Parameter " + p.key()
                    + " is a mapping with a type and " + (defaultNeeded ? "a default" : "if you like, a default")));
                continue;
            }
            for (YamlReader.Entry k : spec.entries().values()) {
                if (!KEYS.contains(k.key())) {
                    problems.add(Problem.at(k.keyPosition(), null, "Parameter " + p.key() + " has no setting '"
                        + Suggest.clip(k.key()) + "'" + Suggest.hint(k.key(), KEYS)
                        + ": it takes type, default and description"));
                }
            }
            YamlReader.Entry t = spec.entries().get("type");
            FlowCondition.Type type = null;
            boolean list = false;
            if (t != null && t.value().value() instanceof String ts) {
                String words = ts.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
                list = words.endsWith(" list");
                type = switch (list ? words.substring(0, words.length() - 5) : words) {
                    case "text" -> FlowCondition.Type.TEXT;
                    case "number" -> FlowCondition.Type.NUMBER;
                    case "boolean" -> list ? null : FlowCondition.Type.BOOLEAN;
                    default -> null;
                };
            }
            if (type == null) {
                problems.add(Problem.at(t == null ? spec.position() : t.value().position(), null, "Parameter " + p.key()
                    + " needs a type: text, number, boolean, text list or number list"));
                continue;
            }
            Object def = null;
            YamlReader.Entry d = spec.entries().get("default");
            String fromVar = d != null && d.value() instanceof YamlReader.Scalar sc && sc.value() instanceof String w
                ? whole(w) : null;
            if (fromVar != null && fromVar.startsWith(VARS) && NAME.matcher(fromVar.substring(VARS.length())).matches()) {
                def = new VarRef(fromVar.substring(VARS.length())); // checked against the server's variables at the run
            } else if (d != null && d.value().value() != null) {
                try {
                    def = typed(p.key(), type, list, written(d.value(), type));
                } catch (IllegalArgumentException wrong) {
                    problems.add(Problem.at(d.value().position(), null, "The default of " + wrong.getMessage()));
                }
            } else if (defaultNeeded) {
                problems.add(Problem.at(p.keyPosition(), null, "Parameter " + p.key() + " needs a default, so the "
                    + what + " also runs on its own"));
            }
            YamlReader.Entry desc = spec.entries().get("description");
            String description = desc != null && desc.value() instanceof YamlReader.Scalar sc && sc.value() != null
                ? sc.written() : null;
            out.add(new Parameter(p.key(), type, list, def, description, p.keyPosition()));
        }
        return out;
    }

    // ---- values ----

    /**
     * The values a run starts with: each parameter as given, or its default.
     *
     * @throws IllegalArgumentException naming the parameter, when a value is unknown, missing or of the wrong kind
     */
    public static Map<String, Object> resolve(String owner, List<Parameter> declared, Map<String, Object> given) {
        return resolve(owner, declared, given, Map.of());
    }

    /**
     * As {@link #resolve(String, List, Map)}, with the server's variables for defaults written as
     * {@code ${vars.name}}.
     */
    public static Map<String, Object> resolve(String owner, List<Parameter> declared, Map<String, Object> given,
                                              Map<String, Object> variables) {
        Map<String, Object> in = given == null ? Map.of() : given;
        for (String key : in.keySet()) {
            if (declared.stream().noneMatch(p -> p.name().equals(key))) {
                throw new IllegalArgumentException(owner + " has no parameter '" + Suggest.clip(key) + "'"
                    + (declared.isEmpty() ? "" : "; it takes "
                    + String.join(", ", declared.stream().map(Parameter::name).toList())));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Parameter p : declared) {
            Object v = in.containsKey(p.name()) ? in.get(p.name()) : p.defaultValue();
            if (v instanceof VarRef ref) {
                v = variables == null ? null : variables.get(ref.variable());
                if (v == null) {
                    throw new IllegalArgumentException(p.name() + " takes its default from the server variable "
                        + ref.variable() + ", which is not set on this server");
                }
            }
            if (v == null) {
                throw new IllegalArgumentException(p.name() + " needs a value (it has no default)");
            }
            out.put(p.name(), typed(p.name(), p.type(), p.list(), v));
        }
        return out;
    }

    /** A default as written: text keeps its text (007 stays 007), a list each of its values. */
    private static Object written(YamlReader.Node n, FlowCondition.Type type) {
        if (n instanceof YamlReader.Scalar sc) {
            return type == FlowCondition.Type.TEXT && sc.value() != null ? sc.written() : sc.value();
        }
        if (n instanceof YamlReader.Sequence seq) {
            List<Object> out = new ArrayList<>();
            seq.items().forEach(i -> out.add(written(i, type)));
            return out;
        }
        return n.value();
    }

    /**
     * A value as a parameter of this kind holds it: as {@link #typed(String, FlowCondition.Type, Object)},
     * or for a list, a list of 1 to {@value #MAX_LIST} such values.
     *
     * @throws IllegalArgumentException saying what it must be
     */
    public static Object typed(String name, FlowCondition.Type type, boolean list, Object v) {
        if (!list) {
            return typed(name, type, v);
        }
        if (!(v instanceof List<?> l) || l.isEmpty() || l.size() > MAX_LIST) {
            throw new IllegalArgumentException(name + " must be a list of 1 to " + String.format("%,d", MAX_LIST) + " "
                + (type == FlowCondition.Type.TEXT ? "texts" : "numbers") + ", such as [delivered, shipped]");
        }
        List<Object> out = new ArrayList<>();
        for (Object item : l) {
            out.add(typed(name + "'s values", type, item));
        }
        return List.copyOf(out);
    }

    /**
     * A value as a parameter of this kind holds it: text, a number (as a Double) or true/false.
     *
     * @throws IllegalArgumentException saying what it must be
     */
    public static Object typed(String name, FlowCondition.Type type, Object v) {
        boolean fits = switch (type) {
            case TEXT -> v instanceof String s && s.length() <= MAX_TEXT && s.chars().noneMatch(Character::isISOControl);
            case NUMBER -> v instanceof Number n && !(v instanceof java.math.BigInteger) && Double.isFinite(n.doubleValue())
                && Math.abs(n.doubleValue()) <= MAX_NUMBER && numberText(n.doubleValue()).length() <= 30;
            case BOOLEAN -> v instanceof Boolean;
        };
        if (!fits) {
            throw new IllegalArgumentException(name + " must be " + switch (type) {
                case TEXT -> "text of at most " + String.format("%,d", MAX_TEXT) + " characters, without control characters";
                case NUMBER -> "a number of at most 15 digits before the point and 30 in all";
                case BOOLEAN -> "true or false";
            });
        }
        return type == FlowCondition.Type.NUMBER ? (Object) ((Number) v).doubleValue() : v;
    }

    /** A number as written: whole numbers without a point, others in full (no exponent). */
    public static String numberText(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return Long.toString((long) d);
        }
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    // ---- ${name} ----

    /** How {@code ${name}} goes into text. */
    public enum Use {
        /** As its text, whatever it holds: a notification's words. */
        WORDS,
        /** As its text, which may hold only letters, digits, spaces, _, - and .: a delimiter, a label. */
        NAME_PART,
        /**
         * As part of the last name of where a node writes, beside text the pipeline fixes, holding only
         * letters, digits, _ and -: never the whole name, a folder or a schema.
         */
        TARGET_PART,
        /** As a literal of the expression language, outside quotes: an expression. */
        EXPRESSION
    }

    /** Text with its parameters put in, or why they could not be. */
    public record Filled(String text, String problem) {
        static Filled ok(String text) {
            return new Filled(text, null);
        }

        static Filled wrong(String problem) {
            return new Filled(null, problem);
        }
    }

    /** Whether {@code text} names a parameter ({@code ${...}}; <code>$${</code> does not count). */
    public static boolean refers(String text) {
        if (text == null) {
            return false;
        }
        Matcher m = REF.matcher(text);
        while (m.find()) {
            if (m.group(1) != null) {
                return true;
            }
        }
        return false;
    }

    /** Every name {@code text} puts in with {@code ${name}}, in the order written. */
    public static List<String> names(String text) {
        List<String> out = new ArrayList<>();
        if (text != null) {
            Matcher m = REF.matcher(text);
            while (m.find()) {
                if (m.group(1) != null) {
                    out.add(m.group(1).strip());
                }
            }
        }
        return out;
    }

    /**
     * What is wrong with the names {@code text} puts in, given the parameters it may read and whether
     * server variables may be named; null when nothing is. Whether a variable is set is for the server.
     */
    public static String unknownName(String text, Set<String> parameters) {
        return unknownName(text, parameters, false);
    }

    /**
     * As {@link #unknownName(String, Set)}; with {@code nodeValues}, a name such as {@code clean.status}
     * (a flow node's value) is let through for the caller to check.
     */
    public static String unknownName(String text, Set<String> parameters, boolean nodeValues) {
        for (String name : names(text)) {
            if (nodeValues && !name.startsWith(VARS) && name.indexOf('.') > 0) {
                continue;
            }
            if (name.startsWith(VARS)) {
                if (!NAME.matcher(name.substring(VARS.length())).matches()) {
                    return "names '" + Suggest.clip(name) + "', which is not a server variable's name";
                }
            } else if (!parameters.contains(name)) {
                return unknown(name, parameters);
            }
        }
        return null;
    }

    /** The parameter {@code text} is, when it is exactly one {@code ${name}} and nothing else; else null. */
    public static String whole(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = REF.matcher(text.strip());
        return m.matches() && m.group(1) != null ? m.group(1).strip() : null;
    }

    /** Puts the values into {@code text}, as {@code use} says. */
    public static Filled fill(String text, Map<String, ?> values, Use use) {
        if (use == Use.TARGET_PART) {
            String wrong = targetShape(text);
            if (wrong != null) {
                return Filled.wrong(wrong);
            }
        }
        StringBuilder out = new StringBuilder();
        Matcher m = REF.matcher(text);
        int at = 0;
        while (m.find()) {
            out.append(text, at, m.start());
            at = m.end();
            if (m.group(1) == null) {
                out.append("${");
                continue;
            }
            String name = m.group(1).strip();
            if (!values.containsKey(name)) {
                return Filled.wrong(unknown(name, values.keySet()));
            }
            Object v = values.get(name);
            if (v instanceof List<?> && use == Use.NAME_PART) {
                return Filled.wrong("names " + name + ", which is a list: in this setting a parameter is one value");
            }
            if (v instanceof List<?> && use == Use.TARGET_PART) {
                return Filled.wrong("names " + name + ", which is a list: here a parameter is one value");
            }
            if (use == Use.EXPRESSION) {
                String touching = touching(text, m.start(), m.end());
                if (touching != null) {
                    return Filled.wrong("has ${" + name + "} right next to " + touching + ": put a space or an "
                        + "operator between them, as a value never joins a name");
                }
                char quote = openQuote(text, m.start());
                if (quote == '\'') {
                    return Filled.wrong("has ${" + name + "} inside quotes: write it without them, as it goes in as a "
                        + "value already");
                }
                if (quote == '"') {
                    return Filled.wrong("has ${" + name + "} inside a quoted column name: a parameter is a value, "
                        + "never a column");
                }
                out.append(literal(v));
            } else {
                String t = v instanceof List<?> l ? String.join(", ", l.stream().map(Parameters::plain).toList())
                    : plain(v);
                if (use == Use.NAME_PART && (!PLAIN_TEXT.matcher(t).matches() || t.contains(".."))) {
                    return Filled.wrong("would get '" + Suggest.clip(t) + "' from " + name + ": in this setting a "
                        + "parameter may hold only letters, digits, spaces, _, - and . (not ..)");
                }
                if (use == Use.TARGET_PART && !TARGET_TEXT.matcher(t).matches()) {
                    return Filled.wrong("would get '" + Suggest.clip(t) + "' from " + name + ": in where a node writes, "
                        + "a parameter may hold only letters, digits, _ and -, so it never names another folder, "
                        + "schema or table");
                }
                out.append(t);
            }
        }
        out.append(text, at, text.length());
        return Filled.ok(out.toString());
    }

    /** Why ${name} has no value: an undeclared parameter, or a server variable not set. */
    public static String unknown(String name, Collection<String> known) {
        if (name.startsWith(VARS)) {
            return "names the server variable '" + Suggest.clip(name.substring(VARS.length()))
                + "', which is not set on this server" + Suggest.hint(name, known);
        }
        List<String> parameters = known.stream().filter(k -> !k.startsWith(VARS)).toList();
        return "names the parameter '" + Suggest.clip(name) + "', which is not declared" + Suggest.hint(name, parameters)
            + (parameters.isEmpty() ? "" : ": the parameters are " + String.join(", ", parameters));
    }

    /** Whether {@code text} names a server variable ({@code ${vars.name}}). */
    public static boolean refersToVariable(String text) {
        if (text == null) {
            return false;
        }
        Matcher m = REF.matcher(text);
        while (m.find()) {
            if (m.group(1) != null && m.group(1).strip().startsWith(VARS)) {
                return true;
            }
        }
        return false;
    }

    /** The values ${...} reads: the parameters by name and each server variable as vars.name. */
    public static Map<String, Object> scope(Map<String, Object> parameters, Map<String, Object> variables) {
        Map<String, Object> out = new LinkedHashMap<>(parameters);
        if (variables != null) {
            variables.forEach((k, v) -> out.put(VARS + k, v));
        }
        return out;
    }

    private static final Pattern TARGET_TEXT = Pattern.compile("[A-Za-z0-9_-]{1,100}");

    /**
     * Why ${...} cannot go where it is written in a write target, or null: only in the last name (after
     * the last / or \), and beside text the pipeline wrote there, so the pipeline fixes the folder or
     * schema and part of the name.
     */
    private static String targetShape(String text) {
        int last = Math.max(text.lastIndexOf('/'), text.lastIndexOf('\\'));
        Matcher m = REF.matcher(text);
        StringBuilder fixed = new StringBuilder();
        int at = Math.max(last + 1, 0);
        while (m.find()) {
            if (m.group(1) == null) {
                continue;
            }
            if (m.start() <= last) {
                return "names a parameter in a folder: in where a node writes, a parameter may only be part of the "
                    + "last name, such as orders/${label}.csv";
            }
            fixed.append(text, at, m.start());
            at = m.end();
        }
        fixed.append(text, Math.min(at, text.length()), text.length());
        if (!fixed.toString().matches(".*[A-Za-z0-9].*")) {
            return "is only parameters: in where a node writes, a parameter may only be part of a name the pipeline "
                + "also writes, such as orders_${region} or ${label}.csv";
        }
        return null;
    }

    /** What touches a placeholder in an expression so that the value would join it, such as amount_ or ".". */
    private static String touching(String text, int start, int end) {
        char before = start > 0 ? text.charAt(start - 1) : ' ';
        char after = end < text.length() ? text.charAt(end) : ' ';
        if (Character.isLetterOrDigit(before) || before == '_' || before == '.' || before == '"') {
            return "'" + before + "'";
        }
        if (Character.isLetterOrDigit(after) || after == '_' || after == '.' || after == '"' || after == '(') {
            return "'" + after + "'";
        }
        return null;
    }

    /** A value as plain text: numbers without needless digits. */
    private static String plain(Object v) {
        return v instanceof Double d ? numberText(d) : String.valueOf(v);
    }

    /** A value as the expression language writes it; a list as ('a', 'b'), for in. */
    static String literal(Object v) {
        if (v instanceof List<?> l) {
            return "(" + String.join(", ", l.stream().map(Parameters::literal).toList()) + ")";
        }
        if (v instanceof Boolean b) {
            return b.toString();
        }
        if (v instanceof Double d) {
            // Always a decimal, so what an expression works out has one type whatever the value.
            String n = numberText(d);
            n = n.contains(".") ? n : n + ".0";
            return d < 0 ? "(" + n + ")" : n;
        }
        return "'" + String.valueOf(v).replace("'", "''") + "'";
    }

    /** The quote open at {@code index} in an expression: ' for text, " for a column name, or 0. */
    private static char openQuote(String text, int index) {
        char open = 0;
        for (int i = 0; i < index; i++) {
            char c = text.charAt(i);
            if (open == 0 && (c == '\'' || c == '"')) {
                open = c;
            } else if (c == open) {
                open = 0; // a doubled quote closes and opens again, which comes to the same
            }
        }
        return open;
    }
}

package io.kilnmark.kiln.steps.expr;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Regular expressions for {@code regex_match} and {@code regex_replace}, made safe to run on any
 * data with any pattern. Java's matcher backtracks, so a pattern such as {@code (a+)+$} can take
 * longer than the age of the universe on a short string. Three guards stop that:
 *
 * <ol>
 *   <li><b>Patterns are checked</b> before use: a quantified group that itself contains a
 *   quantifier ({@code (a+)+}, {@code (a*)*}, {@code (\w+\s?)*}) and back-references ({@code \1},
 *   {@code \k<name>}) are rejected, because they are what makes backtracking explode.</li>
 *   <li><b>Values are capped</b>: text longer than {@value #MAX_INPUT} characters is an error.</li>
 *   <li><b>Work is budgeted</b>: the matcher reads the text through a wrapper that counts
 *   character reads, and stops with an error after {@value #BUDGET} for one value (about tens of
 *   milliseconds). This catches what the check cannot see, such as overlapping alternatives
 *   ({@code (a|a)*}).</li>
 * </ol>
 *
 * <p>Compiled patterns are cached ({@value #CACHE_SIZE} per expression), so a pattern taken from a
 * column is compiled once per distinct value.
 */
final class Regexes {

    /** The longest text a regular expression is run on. */
    static final int MAX_INPUT = 100_000;
    /** Character reads allowed for one value. */
    static final long BUDGET = 10_000_000L;
    static final int CACHE_SIZE = 128;

    private final Map<String, Object> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    /** A compiled pattern from the cache, or the error it gave. */
    Pattern pattern(String regex, int pos) throws ExprException {
        Object cached = cache.get(regex);
        if (cached == null) {
            try {
                cached = compile(regex, pos);
            } catch (ExprException e) {
                cached = e;
            }
            cache.put(regex, cached);
        }
        if (cached instanceof ExprException e) {
            throw new ExprException(e.problem(), pos, e.hint());
        }
        return (Pattern) cached;
    }

    /** Checks and compiles a pattern. */
    static Pattern compile(String regex, int pos) throws ExprException {
        if (regex.length() > 1_000) {
            throw new ExprException("the regular expression is longer than 1,000 characters", pos);
        }
        String problem = unsafe(regex);
        if (problem != null) {
            throw new ExprException("the regular expression " + problem, pos,
                "simplify it, for example (a+)+ to a+");
        }
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new ExprException("the regular expression is not valid: " + e.getDescription()
                + (e.getIndex() >= 0 ? " (character " + (e.getIndex() + 1) + " of the pattern)" : ""), pos);
        }
    }

    /**
     * Why a pattern could backtrack catastrophically, or null if it looks safe. A rough scan, not a
     * full parser: it tracks groups and whether each holds a repeating quantifier.
     */
    static String unsafe(String regex) {
        Deque<boolean[]> groups = new ArrayDeque<>(); // per open group: {holds a repeat}
        groups.push(new boolean[1]);
        boolean lastWasGroupWithRepeat = false;
        int n = regex.length();
        for (int i = 0; i < n; i++) {
            char c = regex.charAt(i);
            boolean closedRepeatGroup = false;
            switch (c) {
                case '\\' -> {
                    if (i + 1 < n) {
                        char e = regex.charAt(i + 1);
                        if (e >= '1' && e <= '9' || e == 'k') {
                            return "uses a back-reference, which is not allowed";
                        }
                        if (e == 'Q') {
                            int end = regex.indexOf("\\E", i + 2);
                            i = end < 0 ? n : end + 1;
                        } else {
                            i++;
                        }
                    }
                }
                case '[' -> i = endOfClass(regex, i);
                case '(' -> {
                    groups.push(new boolean[1]);
                    if (i + 1 < n && regex.charAt(i + 1) == '?') {
                        i++; // a group's modifiers, such as (?: (?i) (?<name>, are not quantifiers
                        if (i + 1 < n && regex.charAt(i + 1) == '<' && i + 2 < n && regex.charAt(i + 2) != '='
                            && regex.charAt(i + 2) != '!') {
                            int close = regex.indexOf('>', i);
                            i = close < 0 ? i : close;
                        }
                    }
                }
                case ')' -> {
                    if (groups.size() > 1) {
                        boolean repeats = groups.pop()[0];
                        closedRepeatGroup = repeats;
                        if (repeats) {
                            groups.peek()[0] = true;
                        }
                    }
                }
                case '*', '+', '?', '{' -> {
                    int end = i;
                    boolean repeat = c != '?';
                    if (c == '{') {
                        end = regex.indexOf('}', i);
                        if (end < 0) {
                            break;
                        }
                        String body = regex.substring(i + 1, end);
                        repeat = repeatsMoreThanOnce(body);
                    }
                    if (repeat && lastWasGroupWithRepeat) {
                        return "repeats a group that itself repeats, which can take forever on some text";
                    }
                    if (repeat) {
                        groups.peek()[0] = true;
                    }
                    i = end;
                    // a following ? or + makes the quantifier lazy or possessive; it is not another quantifier
                    if (i + 1 < n && (regex.charAt(i + 1) == '?' || regex.charAt(i + 1) == '+')) {
                        i++;
                    }
                }
                default -> {
                    // an ordinary character
                }
            }
            lastWasGroupWithRepeat = closedRepeatGroup;
        }
        return null;
    }

    private static boolean repeatsMoreThanOnce(String body) {
        String[] parts = body.split(",", -1);
        try {
            if (parts.length == 1) {
                return Integer.parseInt(parts[0].trim()) > 1;
            }
            return parts[1].isBlank() || Integer.parseInt(parts[1].trim()) > 1;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static int endOfClass(String regex, int start) {
        int depth = 0;
        int n = regex.length();
        for (int i = start; i < n; i++) {
            char c = regex.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '[') {
                depth++;
                if (i + 1 < n && regex.charAt(i + 1) == ']' && depth == 1 && i == start) {
                    i++; // []...] : a leading ] is a character
                }
            } else if (c == ']') {
                if (--depth == 0) {
                    return i;
                }
            }
        }
        return n;
    }

    /** Whether the pattern is found anywhere in the text. */
    static boolean find(Pattern p, String text, int pos) throws ExprException {
        Matcher m = p.matcher(guard(text, pos));
        try {
            return m.find();
        } catch (BudgetExceeded e) {
            throw tooSlow(pos);
        }
    }

    /** The text with every match replaced; {@code $1} in the replacement is the first group. */
    static String replace(Pattern p, String text, String replacement, int pos) throws ExprException {
        Matcher m = p.matcher(guard(text, pos));
        try {
            return m.replaceAll(replacement);
        } catch (BudgetExceeded e) {
            throw tooSlow(pos);
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            throw new ExprException("regex_replace: the replacement text is not valid: "
                + String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT), pos,
                "refer to groups as $1, $2 ...; write \\$ for a dollar sign");
        }
    }

    /** Checks a replacement against a pattern without data, so a fixed replacement fails when it is checked. */
    static void checkReplacement(Pattern p, String replacement, int pos) throws ExprException {
        for (int i = 0; i < replacement.length(); i++) {
            char c = replacement.charAt(i);
            if (c == '\\') {
                if (i + 1 >= replacement.length()) {
                    throw new ExprException("regex_replace: the replacement text ends with a lone \\", pos,
                        "write \\\\ for a backslash");
                }
                i++;
            } else if (c == '$') {
                if (i + 1 >= replacement.length()) {
                    throw new ExprException("regex_replace: the replacement text ends with a lone $", pos,
                        "write \\$ for a dollar sign");
                }
                char d = replacement.charAt(i + 1);
                if (Character.isDigit(d)) {
                    int group = d - '0';
                    if (group > p.matcher("").groupCount()) {
                        throw new ExprException("regex_replace: the replacement refers to group " + group
                            + " but the pattern has " + p.matcher("").groupCount(), pos);
                    }
                } else if (d != '{') {
                    throw new ExprException("regex_replace: '$' must be followed by a group number", pos,
                        "write \\$ for a dollar sign");
                }
            }
        }
    }

    private static CharSequence guard(String text, int pos) throws ExprException {
        if (text.length() > MAX_INPUT) {
            throw new ExprException("a value is longer than " + String.format(Locale.UK, "%,d", MAX_INPUT)
                + " characters, the most a regular expression is run on", pos);
        }
        return new Budgeted(text, new long[] {BUDGET});
    }

    private static ExprException tooSlow(int pos) {
        return new ExprException("the regular expression took too long on a value", pos,
            "simplify the pattern, or anchor it with ^ and $");
    }

    /** Thrown inside the matcher when a value's budget is spent. */
    private static final class BudgetExceeded extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        BudgetExceeded() {
            super(null, null, false, false);
        }
    }

    /** Text that counts how often the matcher reads it. */
    private record Budgeted(String text, long[] left) implements CharSequence {

        @Override
        public char charAt(int index) {
            if (--left[0] < 0) {
                throw new BudgetExceeded();
            }
            return text.charAt(index);
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new Budgeted(text.substring(start, end), left);
        }

        @Override
        public String toString() {
            return text;
        }
    }
}

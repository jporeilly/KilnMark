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
 * longer than the age of the universe on a short string, and it recurses, so a repeated group can
 * overflow the stack on a long one. These guards stop that:
 *
 * <ol>
 *   <li><b>Patterns are checked</b> before use: a repeated group that itself contains a repeat
 *   ({@code (a+)+}, {@code (\w+\s?)*}) is refused, unless each repetition starts with a character
 *   nothing inside the group can match, repeated with {@code +} or {@code *} ({@code (\.[\w-]+)+}),
 *   which leaves the matcher one way to split the text; see {@link #startsWithItsOwnSeparator}.
 *   Back-references ({@code \1}, {@code \k<name>}) and comment mode ({@code (?x)}) are refused.</li>
 *   <li><b>Values are capped</b>: text longer than {@value #MAX_INPUT} characters is an error, and
 *   {@value #MAX_INPUT_REPEATED_GROUP} for patterns that repeat a group. A stack overflow inside
 *   the matcher is caught and reported as an error too.</li>
 *   <li><b>Work is budgeted</b>: the matcher reads the text through a wrapper that counts
 *   character reads: {@value #BUDGET} for one value (roughly a fifth of a second) and
 *   {@value #BATCH_BUDGET} for one batch of rows per function call. This catches what the check
 *   cannot see, such as {@code .*,.*,.*,.*,.*,.*,.*,.*,P}.</li>
 *   <li><b>Results are capped</b> at {@value Casts#MAX_TEXT} characters per value and
 *   {@value TextBudget#PER_BATCH} per batch.</li>
 * </ol>
 *
 * <p>Compiled patterns are cached ({@value #CACHE_SIZE} per function call), so a pattern taken from
 * a column is compiled once per distinct value. One instance serves one function call in one
 * expression; it is not safe for use by two threads at once.
 */
final class Regexes {

    /** The longest text a regular expression is run on. */
    static final int MAX_INPUT = 100_000;
    /** The longest text a pattern that repeats a group is run on, to stay clear of stack overflows. */
    static final int MAX_INPUT_REPEATED_GROUP = 2_000;
    /** Character reads allowed for one value. */
    static final long BUDGET = 10_000_000L;
    /** Character reads allowed for one batch of rows. */
    static final long BATCH_BUDGET = 250_000_000L;
    static final int CACHE_SIZE = 128;

    /** A checked, compiled pattern and the longest text it may be run on. */
    record Rx(Pattern pattern, int maxInput) {
    }

    /** Why a pattern is refused (or null), and whether it repeats a group. */
    record Scan(String problem, boolean repeatsGroup) {
    }

    private final Map<String, Object> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
            return size() > CACHE_SIZE;
        }
    };
    private long batchLeft = BATCH_BUDGET;
    private final TextBudget text = new TextBudget();

    /** Starts a new batch of rows: the batch budgets are full again. */
    void newBatch() {
        batchLeft = BATCH_BUDGET;
        text.newBatch();
    }

    /** A compiled pattern from the cache, or the error it gave. */
    Rx pattern(String regex, int pos) throws ExprException {
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
        return (Rx) cached;
    }

    /** Checks and compiles a pattern. */
    static Rx compile(String regex, int pos) throws ExprException {
        if (regex.length() > 1_000) {
            throw new ExprException("the regular expression is longer than 1,000 characters", pos);
        }
        Scan scan = scan(regex);
        if (scan.problem() != null) {
            throw new ExprException("the regular expression " + scan.problem(), pos,
                "simplify it, for example (a+)+ to a+, or (a|b)* to [ab]*");
        }
        try {
            return new Rx(Pattern.compile(regex), scan.repeatsGroup() ? MAX_INPUT_REPEATED_GROUP : MAX_INPUT);
        } catch (PatternSyntaxException e) {
            throw new ExprException("the regular expression is not valid: " + e.getDescription()
                + (e.getIndex() >= 0 ? " (character " + (e.getIndex() + 1) + " of the pattern)" : ""), pos);
        }
    }

    /** Why a pattern could backtrack catastrophically, or null if it looks safe. */
    static String unsafe(String regex) {
        return scan(regex).problem();
    }

    /**
     * A rough scan, not a full parser: it tracks groups, whether each holds a repeating quantifier,
     * and what follows each group.
     */
    static Scan scan(String regex) {
        Deque<int[]> groups = new ArrayDeque<>(); // per open group: {holds a repeat (0/1), index where its body starts}
        groups.push(new int[] {0, 0});
        boolean repeatsGroup = false;
        int closedGroupRepeats = -1; // after ')': 1 if that group held a repeat, 0 if not; -1 otherwise
        int closedBodyStart = 0;
        int closedBodyEnd = 0;
        int n = regex.length();
        for (int i = 0; i < n; i++) {
            char c = regex.charAt(i);
            int justClosed = -1;
            switch (c) {
                case '\\' -> {
                    if (i + 1 < n) {
                        char e = regex.charAt(i + 1);
                        if (e >= '1' && e <= '9' || e == 'k') {
                            return new Scan("uses a back-reference, which is not allowed", false);
                        }
                        if (e == 'Q') {
                            int end = regex.indexOf("\\E", i + 2);
                            i = end < 0 ? n : end + 1;
                        } else if ("NpPxk".indexOf(e) >= 0 && i + 2 < n && regex.charAt(i + 2) == '{') {
                            int end = regex.indexOf('}', i + 2); // \N{NAME}, \p{L}, \x{2e}: one character or class
                            i = end < 0 ? n : end;
                        } else {
                            i++;
                        }
                    }
                }
                case '[' -> i = endOfClass(regex, i);
                case '(' -> {
                    int body = i + 1;
                    if (i + 1 < n && regex.charAt(i + 1) == '?') {
                        int j = i + 2;
                        if (j < n && regex.charAt(j) == '<' && j + 1 < n && regex.charAt(j + 1) != '='
                            && regex.charAt(j + 1) != '!') {
                            int close = regex.indexOf('>', j);
                            body = close < 0 ? n : close + 1;
                        } else if (j < n && (regex.charAt(j) == ':' || regex.charAt(j) == '=' || regex.charAt(j) == '!'
                            || regex.charAt(j) == '>')) {
                            body = j + 1;
                        } else if (j < n && regex.charAt(j) == '<') {
                            body = j + 2; // lookbehind (?<= or (?<!
                        } else {
                            // inline flags: (?i) or (?i:...)
                            int k = j;
                            while (k < n && (Character.isLetter(regex.charAt(k)) || regex.charAt(k) == '-')) {
                                if (regex.charAt(k) == 'x') {
                                    return new Scan("uses comment mode (?x), which is not supported", false);
                                }
                                k++;
                            }
                            if (k < n && regex.charAt(k) == ')') {
                                i = k; // flags alone, such as (?i): not a group
                                closedGroupRepeats = -1;
                                continue;
                            }
                            body = k + 1;
                        }
                        i = body - 1;
                    }
                    groups.push(new int[] {0, body});
                }
                case ')' -> {
                    if (groups.size() > 1) {
                        int[] g = groups.pop();
                        justClosed = g[0];
                        closedBodyStart = g[1];
                        closedBodyEnd = i;
                        if (g[0] == 1) {
                            groups.peek()[0] = 1;
                        }
                    }
                }
                case '*', '+', '?', '{' -> {
                    int end = i;
                    boolean repeat = c != '?';
                    if (c == '{') {
                        end = regex.indexOf('}', i);
                        if (end < 0 || !regex.substring(i + 1, end).matches("\\d+(,\\d*)?")) {
                            break; // not a counted repeat: plain characters
                        }
                        repeat = repeatsMoreThanOnce(regex.substring(i + 1, end));
                    }
                    if (repeat && closedGroupRepeats >= 0) {
                        repeatsGroup = true;
                        // the separator exception holds only for + and *: a counted repeat such as {30}
                        // defeats the matcher's own caching, so it is refused whatever the group holds
                        if (closedGroupRepeats == 1 && (c == '{'
                            || !startsWithItsOwnSeparator(regex.substring(Math.min(closedBodyStart, closedBodyEnd),
                                closedBodyEnd)))) {
                            return new Scan("repeats a group that itself repeats, which can take forever on some text",
                                true);
                        }
                    }
                    if (repeat) {
                        groups.peek()[0] = 1;
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
            closedGroupRepeats = justClosed;
        }
        return new Scan(null, repeatsGroup);
    }

    /**
     * Whether a group's body starts with a separator nothing else in the body can match, and has no
     * alternatives: then each repetition must begin at that character, so the matcher has only one
     * way to split the text and cannot backtrack badly. Deliberately strict:
     *
     * <ul>
     *   <li>the separator is ASCII punctuation other than {@code _} (so {@code \w}, {@code \d} and
     *   {@code \s} never match it, in any mode), written plainly or escaped;</li>
     *   <li>the rest of the body may not contain it, an unescaped {@code .}, a negated or nested
     *   class, a class range with any non-letter or non-digit between its ends, or an escape that can
     *   spell or stand for many characters ({@code \x}, {@code \0}, {@code \c}, {@code \N}, a Unicode escape, {@code \p}, {@code \P}, {@code \W}, {@code \S}, {@code \D}, {@code \X}, {@code \R}, {@code \Q}).</li>
     * </ul>
     */
    static boolean startsWithItsOwnSeparator(String body) {
        if (body.isEmpty() || body.indexOf('|') >= 0) {
            return false;
        }
        char sep;
        int i;
        if (body.charAt(0) == '\\') {
            if (body.length() < 2) {
                return false;
            }
            sep = body.charAt(1);
            i = 2;
        } else {
            sep = body.charAt(0);
            if ("[(.^$*+?{|)".indexOf(sep) >= 0) {
                return false;
            }
            i = 1;
        }
        if (sep > 127 || sep == '_' || Character.isLetterOrDigit(sep) || Character.isWhitespace(sep)
            || Character.isISOControl(sep)) {
            return false;
        }
        if (i < body.length() && "*+?{".indexOf(body.charAt(i)) >= 0) {
            return false;
        }
        boolean inClass = false;
        for (int k = i; k < body.length(); k++) {
            char c = body.charAt(k);
            boolean range = inClass && c == '-' && k + 1 < body.length() && body.charAt(k + 1) != ']'
                && body.charAt(k - 1) != '[';
            if (c == sep && !range) { // a range's dash, as in [a-z], is not the character -
                return false;
            }
            if (c == '\\') {
                if (k + 1 >= body.length()) {
                    return false;
                }
                char e = body.charAt(k + 1);
                if (e == sep || "xu0cNWSDpPXRQ".indexOf(e) >= 0) {
                    return false; // could spell or match the separator
                }
                k++;
            } else if (!inClass && c == '[') {
                inClass = true;
                if (k + 1 < body.length() && body.charAt(k + 1) == '^') {
                    return false;
                }
            } else if (inClass && c == '[') {
                return false; // a nested class, such as [a[^b]] or [a-z&&[^b]]
            } else if (inClass && c == ']') {
                inClass = false;
            } else if (range) {
                char lo = body.charAt(k - 1);
                char hi = body.charAt(k + 1);
                if (hi == '\\') {
                    return false;
                }
                for (int cp = Math.min(lo, hi); cp <= Math.max(lo, hi); cp++) {
                    if (!Character.isLetterOrDigit(cp)) {
                        return false; // [0-z] holds : ; < = > ? @ [ \ ] ^ _ and `
                    }
                }
            } else if (!inClass && c == '.') {
                return false;
            }
        }
        return true;
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
            } else if (c == ']') {
                if (--depth == 0) {
                    return i;
                }
            }
        }
        return n;
    }

    /** Whether the pattern is found anywhere in the text. */
    boolean find(Rx rx, String text, int pos) throws ExprException {
        Budgeted b = guard(rx, text, pos);
        try {
            return rx.pattern().matcher(b).find();
        } catch (BudgetExceeded e) {
            throw tooSlow(pos);
        } catch (StackOverflowError e) {
            throw tooDeep(pos);
        } finally {
            spend(b);
        }
    }

    /** The text with every match replaced; {@code $1} in the replacement is the first group. */
    String replace(Rx rx, String text, String replacement, int pos) throws ExprException {
        Budgeted b = guard(rx, text, pos);
        Matcher m = rx.pattern().matcher(b);
        StringBuilder sb = new StringBuilder();
        try {
            while (m.find()) {
                m.appendReplacement(sb, replacement);
                if (sb.length() > Casts.MAX_TEXT) {
                    throw Casts.tooLong(pos);
                }
            }
            m.appendTail(sb);
            if (sb.length() > Casts.MAX_TEXT) {
                throw Casts.tooLong(pos);
            }
            this.text.spend(sb.length(), pos);
            return sb.toString();
        } catch (BudgetExceeded e) {
            throw tooSlow(pos);
        } catch (StackOverflowError e) {
            throw tooDeep(pos);
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            throw new ExprException("regex_replace: the replacement text is not valid: "
                + String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT), pos,
                "refer to groups as $1, $2 ...; write \\$ for a dollar sign");
        } finally {
            spend(b);
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

    private Budgeted guard(Rx rx, String text, int pos) throws ExprException {
        if (text.length() > rx.maxInput()) {
            throw new ExprException("a value is longer than " + String.format(Locale.UK, "%,d", rx.maxInput())
                + " characters, the most this regular expression is run on", pos,
                rx.maxInput() < MAX_INPUT ? "patterns that repeat a group are limited further; a character class, "
                    + "like [ab]* for (a|b)*, avoids the limit" : null);
        }
        if (batchLeft <= 0) {
            throw new ExprException("regular expressions took too long on this batch of rows", pos,
                "simplify the pattern, or anchor it with ^ and $");
        }
        long allowed = Math.min(BUDGET, batchLeft);
        return new Budgeted(text, new long[] {allowed, allowed});
    }

    private void spend(Budgeted b) {
        batchLeft -= b.left()[1] - Math.max(0, b.left()[0]);
    }

    private static ExprException tooSlow(int pos) {
        return new ExprException("the regular expression took too long on a value", pos,
            "simplify the pattern, or anchor it with ^ and $");
    }

    private static ExprException tooDeep(int pos) {
        return new ExprException("the regular expression needs too much memory on a value", pos,
            "use a character class, like [ab]* instead of (a|b)*");
    }

    /** Thrown inside the matcher when a value's budget is spent. */
    private static final class BudgetExceeded extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        BudgetExceeded() {
            super(null, null, false, false);
        }
    }

    /** Text that counts how often the matcher reads it; {@code left} is {reads left, reads allowed}. */
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

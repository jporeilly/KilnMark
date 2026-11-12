package io.kilnmark.server.quality;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A tokenizer for PostgreSQL's SQL, enough to check custom SQL rules: identifiers (where {@code $}
 * may follow the first character), quoted identifiers ({@code "..."}, {@code U&"..."}), string
 * constants ({@code '...'}, {@code E'...'} with backslash escapes, {@code U&'...'}, {@code B'...'},
 * {@code X'...'}), dollar quotes (only where a token starts), positional parameters, numbers,
 * line comments and nested block comments.
 *
 * <p>{@link #checkCustomSql} accepts one {@code SELECT} or {@code WITH} statement and refuses
 * anything that writes, calls out of the database (dblink, postgres_fdw) or reaches the server's
 * files. It is a first line of defence: the query also runs in a read-only transaction with a
 * statement timeout, and not at all under a login that is a superuser, may read files or signal
 * other sessions, or may reach other databases.
 */
public final class SqlTokens {

    /** What a token is. Identifiers carry their name: lower-cased if unquoted, as written if quoted. */
    public enum Kind { WORD, QUOTED_WORD, STRING, NUMBER, PARAMETER, SYMBOL }

    public record Token(Kind kind, String text, int at) {
    }

    /** Statements that change something, or are not a query. Unquoted words only. */
    static final Set<String> FORBIDDEN_WORDS = Set.of("insert", "update", "delete", "merge", "truncate", "drop",
        "alter", "create", "grant", "revoke", "copy", "call", "do", "execute", "vacuum", "set", "reset", "listen",
        "notify", "unlisten", "prepare", "deallocate", "discard", "into", "checkpoint");
    /** Functions refused, by prefix (any identifier, quoted or not, schema-qualified or not). */
    static final List<String> FORBIDDEN_FUNCTIONS = List.of("pg_read_", "pg_ls_", "pg_stat_file", "pg_file_",
        "lo_", "dblink", "postgres_fdw", "pg_terminate", "pg_cancel", "pg_reload", "pg_rotate", "pg_sleep", "set_config",
        "pg_advisory", "pg_try_advisory", "pg_notify", "query_to_xml", "table_to_xml", "cursor_to_xml",
        "schema_to_xml", "database_to_xml", "pg_switch_wal", "pg_create_", "pg_drop_", "pg_promote",
        "pg_backup", "pg_start_backup", "pg_stop_backup", "pg_logdir", "pg_import", "pg_replication",
        "pg_logical", "txid_", "pg_current_xact", "nextval", "setval", "currval", "lastval", "pg_log_",
        // run a query held in a string, out of this check's sight
        "ts_stat", "ts_rewrite",
        // its rules call set_config: a session's settings, standard_conforming_strings among them
        "pg_settings");

    private SqlTokens() {
    }

    /**
     * The tokens of {@code sql}; throws if a string, quoted name or comment is not closed, or if it
     * holds a control character other than PostgreSQL's whitespace. Whitespace and the end of a line
     * comment are PostgreSQL's exactly ({@code \r} ends a comment as {@code \n} does), so nothing the
     * database reads as SQL can hide in what this reads as a comment.
     */
    public static List<Token> tokens(String sql) {
        List<Token> out = new ArrayList<>();
        int n = sql.length();
        for (int k = 0; k < n; k++) {
            char c = sql.charAt(k);
            if ((c < 0x20 || c == 0x7f) && !isSpace(c)) {
                throw new IllegalArgumentException("a control character (code " + (int) c + ") is not allowed");
            }
            // A surrogate not in a pair cannot be sent as UTF-8: the driver would send ? in its place,
            // a character this check never saw (the jsonb ? operator, say).
            if (Character.isHighSurrogate(c) && k + 1 < n && Character.isLowSurrogate(sql.charAt(k + 1))) {
                k++;
            } else if (Character.isSurrogate(c)) {
                throw new IllegalArgumentException("a character that is not valid text (code " + (int) c + ") is "
                    + "not allowed");
            }
        }
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            if (isSpace(c)) {
                i++;
            } else if (c == '-' && at(sql, i + 1, '-')) {
                while (i < n && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
                    i++;
                }
            } else if (c == '/' && at(sql, i + 1, '*')) {
                int depth = 1;
                i += 2;
                while (depth > 0) {
                    if (i >= n) {
                        throw new IllegalArgumentException("a comment is not closed");
                    }
                    if (sql.charAt(i) == '/' && at(sql, i + 1, '*')) {
                        depth++;
                        i += 2;
                    } else if (sql.charAt(i) == '*' && at(sql, i + 1, '/')) {
                        depth--;
                        i += 2;
                    } else {
                        i++;
                    }
                }
            } else if ((c == 'E' || c == 'e') && at(sql, i + 1, '\'')) {
                int end = escapeString(sql, i + 2);
                out.add(new Token(Kind.STRING, sql.substring(i, end), i));
                i = end;
            } else if ((c == 'U' || c == 'u') && at(sql, i + 1, '&') && (at(sql, i + 2, '\'') || at(sql, i + 2, '"'))) {
                char q = sql.charAt(i + 2);
                int end = quoted(sql, i + 3, q);
                out.add(new Token(q == '\'' ? Kind.STRING : Kind.QUOTED_WORD, sql.substring(i, end), i));
                i = end;
            } else if ((c == 'B' || c == 'b' || c == 'X' || c == 'x' || c == 'N' || c == 'n') && at(sql, i + 1, '\'')) {
                int end = quoted(sql, i + 2, '\'');
                out.add(new Token(Kind.STRING, sql.substring(i, end), i));
                i = end;
            } else if (c == '\'') {
                int end = quoted(sql, i + 1, '\'');
                out.add(new Token(Kind.STRING, sql.substring(i, end), i));
                i = end;
            } else if (c == '"') {
                int end = quoted(sql, i + 1, '"');
                String name = sql.substring(i + 1, end - 1).replace("\"\"", "\"");
                out.add(new Token(Kind.QUOTED_WORD, name, i));
                i = end;
            } else if (c == '$') {
                int j = i + 1;
                if (j < n && isDigit(sql.charAt(j))) {
                    while (j < n && isDigit(sql.charAt(j))) {
                        j++;
                    }
                    out.add(new Token(Kind.PARAMETER, sql.substring(i, j), i));
                    i = j;
                    continue;
                }
                // A tag is read as PostgreSQL reads it: an identifier's characters, so any non-ASCII one
                while (j < n && isWordPart(sql.charAt(j)) && sql.charAt(j) != '$') {
                    j++;
                }
                if (j < n && sql.charAt(j) == '$') {
                    String tag = sql.substring(i, j + 1);
                    int end = sql.indexOf(tag, j + 1);
                    if (end < 0) {
                        throw new IllegalArgumentException("a $-quoted string is not closed");
                    }
                    out.add(new Token(Kind.STRING, sql.substring(i, end + tag.length()), i));
                    i = end + tag.length();
                } else {
                    out.add(new Token(Kind.SYMBOL, "$", i));
                    i++;
                }
            } else if (isWordStart(c)) {
                int j = i + 1;
                while (j < n && isWordPart(sql.charAt(j))) {
                    j++;
                }
                out.add(new Token(Kind.WORD, sql.substring(i, j).toLowerCase(Locale.ROOT), i));
                i = j;
            } else if (isDigit(c) || (c == '.' && i + 1 < n && isDigit(sql.charAt(i + 1)))) {
                int j = i + 1;
                // Digits, a point and an exponent only: letters after a number start a word, so a word
                // such as RETURNING written straight after one is still seen.
                while (j < n && (isDigit(sql.charAt(j)) || sql.charAt(j) == '.' || sql.charAt(j) == '_'
                    || sql.charAt(j) == 'e' || sql.charAt(j) == 'E'
                    || ((sql.charAt(j) == '+' || sql.charAt(j) == '-') && (sql.charAt(j - 1) == 'e'
                    || sql.charAt(j - 1) == 'E')))) {
                    j++;
                }
                out.add(new Token(Kind.NUMBER, sql.substring(i, j), i));
                i = j;
            } else {
                out.add(new Token(Kind.SYMBOL, String.valueOf(c), i));
                i++;
            }
        }
        return out;
    }

    /** An ASCII digit: PostgreSQL reads no other digit as one. */
    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** How PostgreSQL starts an identifier: an ASCII letter, {@code _} or any non-ASCII character. */
    private static boolean isWordStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c > 127;
    }

    /** What may follow in an identifier: those, digits and {@code $}. */
    private static boolean isWordPart(char c) {
        return isWordStart(c) || isDigit(c) || c == '$';
    }

    /**
     * Why the PostgreSQL driver would not send {@code sql} as one statement, or null when it would:
     * the driver's own parser decides where statements end, so a text this check reads as one
     * statement is refused if the driver would split it.
     */
    static String driverSplits(String sql) {
        try {
            int n = org.postgresql.core.Parser.parseJdbcSql(sql, true, false, true, false, false).size();
            return n <= 1 ? null : "one statement only";
        } catch (java.sql.SQLException e) {
            return "the database driver cannot read it as one statement";
        }
    }

    /** What a refused brace is told: the same words wherever it is found. */
    static final String NO_BRACES = "'{' and '}' are not allowed outside quotes: the database driver reads {...} as "
        + "an instruction to rewrite the SQL (a JDBC escape), so what ran would not be what was checked. Write "
        + "dates, functions and joins as PostgreSQL does, and an array as '{1,2}' in quotes";

    /**
     * Why the braces in {@code tokens} are refused, or null when there are none to refuse. The
     * PostgreSQL driver rewrites JDBC escapes after any check has read the text ({@code {fn ...}},
     * {@code {oj ...}}, {@code {d ...}}, {@code {t ...}}, {@code {ts ...}}, {@code {call ...}},
     * {@code {escape ...}}), and one form of it removes a character, so {@code CREATE {fn xROLE r}}
     * read as the harmless word {@code xrole} and was sent as {@code CREATE ROLE r}. PostgreSQL's own
     * SQL has no brace outside a string, a quoted name, a dollar-quoted string or a comment, none of
     * which is a symbol here, so every brace that is a symbol is refused. With {@code placeholders}
     * (custom SQL rules) the two a rule may write, {@code ${table}} and {@code ${column}}, are let
     * through, exactly as written: Kilnmark replaces them with quoted names before anything is sent.
     */
    static String braces(List<Token> tokens, boolean placeholders) {
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.kind() != Kind.SYMBOL || !(t.text().equals("{") || t.text().equals("}"))) {
                continue;
            }
            if (placeholders && t.text().equals("{") && placeholder(tokens, i)) {
                i += 2; // the name and the closing brace
                continue;
            }
            return NO_BRACES;
        }
        return null;
    }

    /** Whether the opening brace at {@code i} is the one of {@code ${table}} or {@code ${column}}, with nothing between. */
    private static boolean placeholder(List<Token> tokens, int i) {
        if (i == 0 || i + 2 >= tokens.size()) {
            return false;
        }
        Token dollar = tokens.get(i - 1);
        Token open = tokens.get(i);
        Token name = tokens.get(i + 1);
        Token close = tokens.get(i + 2);
        return dollar.kind() == Kind.SYMBOL && dollar.text().equals("$") && dollar.at() + 1 == open.at()
            && name.kind() == Kind.WORD && (name.text().equals("table") || name.text().equals("column"))
            && name.at() == open.at() + 1
            && close.kind() == Kind.SYMBOL && close.text().equals("}") && close.at() == name.at() + name.text().length();
    }

    /**
     * Why {@code sql} would not reach the database as written, or null when it would: the driver's
     * own escape processing is run over it, as the driver runs it when a statement is prepared, and
     * must change nothing. The second line behind {@link #braces}: it asks the driver itself, so it
     * holds whatever escape forms the driver reads now or learns later. Public for the places that
     * send SQL a person wrote after filling it in (a custom rule's {@code ${table}}).
     */
    public static String sentAsWritten(String sql) {
        try {
            return org.postgresql.core.Parser.replaceProcessing(sql, true, true).equals(sql) ? null : NO_BRACES;
        } catch (java.sql.SQLException | RuntimeException e) {
            return "the database driver cannot read it as one statement";
        }
    }

    /**
     * Why a string may not be used, or null: an {@code E'...'} string, whose backslash escapes can
     * carry on into a following string in a way that hides where it ends.
     */
    private static String escapeString(Token t) {
        return t.kind() == Kind.STRING && (t.text().startsWith("E'") || t.text().startsWith("e'"))
            ? "E'...' strings are not allowed; write the value in plain quotes" : null;
    }

    /** PostgreSQL's whitespace: space, tab, newline, carriage return, form feed and vertical tab. */
    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0b;
    }

    /**
     * Why a token may not be used whatever the statement, or null: a {@code U&"..."} name, whose
     * escapes could spell a refused function this check would not recognise.
     */
    private static String unicodeName(Token t) {
        return t.kind() == Kind.QUOTED_WORD && (t.text().startsWith("U&") || t.text().startsWith("u&"))
            ? "names written as U&\"...\" are not allowed; write the name plainly" : null;
    }

    private static boolean at(String s, int i, char c) {
        return i < s.length() && s.charAt(i) == c;
    }

    /** The end (exclusive) of a quoted run whose opening quote is just before {@code from}; doubled quotes escape. */
    private static int quoted(String sql, int from, char q) {
        int i = from;
        while (i < sql.length()) {
            if (sql.charAt(i) == q) {
                if (at(sql, i + 1, q)) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        throw new IllegalArgumentException(q == '"' ? "a quoted name is not closed" : "a string is not closed");
    }

    /** As {@link #quoted}, for {@code E'...'} strings, where a backslash escapes the next character. */
    private static int escapeString(String sql, int from) {
        int i = from;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == '\'') {
                if (at(sql, i + 1, '\'')) {
                    i += 2;
                    continue;
                }
                return i + 1;
            } else {
                i++;
            }
        }
        throw new IllegalArgumentException("a string is not closed");
    }

    /**
     * Checks custom SQL and returns it without trailing semicolons. Throws
     * {@link IllegalArgumentException} with a reason starting {@code params.sql:}.
     */
    public static String checkCustomSql(String sql, int maxLength) {
        String s = sql == null ? "" : sql.strip();
        List<Token> tokens;
        try {
            tokens = new ArrayList<>(tokens(s));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("params.sql: " + e.getMessage());
        }
        while (!tokens.isEmpty() && tokens.getLast().kind() == Kind.SYMBOL && tokens.getLast().text().equals(";")) {
            s = s.substring(0, tokens.getLast().at()).strip();
            tokens.removeLast();
        }
        if (tokens.isEmpty() || s.length() > maxLength) {
            throw new IllegalArgumentException("params.sql: a SELECT listing the failing rows, at most " + maxLength
                + " characters");
        }
        Token first = tokens.getFirst();
        if (first.kind() != Kind.WORD || !(first.text().equals("select") || first.text().equals("with"))) {
            throw new IllegalArgumentException("params.sql: must start with SELECT or WITH");
        }
        String problem = braces(tokens, true);
        if (problem == null) {
            problem = singleStatement(tokens);
        }
        if (problem == null) {
            problem = driverSplits(s);
        }
        if (problem == null) {
            problem = sentAsWritten(s);
        }
        if (problem != null) {
            throw new IllegalArgumentException("params.sql: " + problem);
        }
        for (Token t : tokens) {
            if (t.kind() == Kind.PARAMETER) {
                throw new IllegalArgumentException("params.sql: parameters such as " + t.text() + " are not allowed");
            }
            if (unicodeName(t) != null) {
                throw new IllegalArgumentException("params.sql: " + unicodeName(t));
            }
            if (escapeString(t) != null) {
                throw new IllegalArgumentException("params.sql: " + escapeString(t));
            }
            if (t.kind() == Kind.WORD && FORBIDDEN_WORDS.contains(t.text())) {
                throw new IllegalArgumentException("params.sql: '" + t.text() + "' is not allowed; custom rules only "
                    + "read (quote it, \"" + t.text() + "\", if it is a column name)");
            }
            if (t.kind() == Kind.WORD || t.kind() == Kind.QUOTED_WORD) {
                String name = t.text().toLowerCase(Locale.ROOT);
                for (String f : FORBIDDEN_FUNCTIONS) {
                    if (name.startsWith(f)) {
                        throw new IllegalArgumentException("params.sql: '" + t.text() + "' is not allowed: it acts "
                            + "outside the query (files, other sessions or databases, settings, locks or sequences)");
                    }
                }
            }
        }
        return s;
    }

    /**
     * How a flow's sql node may start (ADR 0028): statements that change data or tables, call a procedure,
     * or keep a table in order. Never a query (it would return rows), transaction control (the node is
     * one transaction), {@code SET} (a role or settings), {@code COPY}, {@code DO}, {@code GRANT} or
     * {@code REVOKE}.
     */
    static final Set<String> ACTION_FIRST_WORDS = Set.of("truncate", "insert", "update", "delete", "merge", "create",
        "drop", "alter", "call", "analyze", "refresh", "comment", "reindex", "cluster", "lock");
    /**
     * Words a sql node's statement never holds, unquoted: they create, change or drop logins, rights,
     * code that runs later, or ways out of the database, or they return rows. One exception:
     * {@code group} before {@code BY} is the clause, not the account (see {@code groupBy}).
     */
    static final Set<String> ACTION_FORBIDDEN_WORDS = Set.of("role", "user", "group", "extension", "server", "foreign",
        "mapping", "subscription", "publication", "database", "tablespace", "system", "language", "event",
        "function", "procedure", "trigger", "rule", "policy", "owner", "authorization", "security", "privileges",
        "operator", "cast", "aggregate", "conversion", "wrapper", "returning", "copy",
        // a view runs later with its owner's rights, for whoever reads it; a routine's settings are code
        "view", "routine",
        // rights, also where CREATE SCHEMA holds them
        "grant", "revoke",
        // a schema named as the login comes first on its search path ("$user"), and changes what every
        // unqualified name later means
        "schema");
    /**
     * Words that, straight before {@code GROUP}, make it an account: {@code CREATE}, {@code ALTER} and
     * {@code DROP GROUP}, and the places a statement names who is given a right or whose members an
     * account joins ({@code TO GROUP}, {@code FROM GROUP}, {@code IN GROUP}), or what it is about
     * ({@code ON GROUP}; PostgreSQL has no such form today). No query has
     * {@code GROUP BY} straight after one of them, nor after a comma (a list of accounts).
     */
    static final Set<String> BEFORE_A_GROUP_ACCOUNT = Set.of("create", "alter", "drop", "to", "from", "in", "on");

    /**
     * Whether the {@code GROUP} at {@code i} starts a {@code GROUP BY} clause, the one use of the word
     * a sql node takes: an unquoted {@code BY} is the next token (comments and whitespace are not
     * tokens, so they may stand between the two as PostgreSQL allows), and what stands before it does
     * not make it an account. PostgreSQL lets an account be called {@code by}, so the word after is
     * not enough: {@code CREATE GROUP by} and {@code DROP GROUP by} are refused by the word before.
     * Every other use stays refused, the ordered-set form ({@code WITHIN GROUP}) among them.
     */
    private static boolean groupBy(List<Token> tokens, int i) {
        if (i == 0 || i + 1 >= tokens.size()) {
            return false;
        }
        Token next = tokens.get(i + 1);
        if (next.kind() != Kind.WORD || !next.text().equals("by")) {
            return false;
        }
        Token before = tokens.get(i - 1);
        if (before.kind() == Kind.WORD) {
            return !BEFORE_A_GROUP_ACCOUNT.contains(before.text());
        }
        return !(before.kind() == Kind.SYMBOL && before.text().equals(","));
    }

    /** {@link #FORBIDDEN_FUNCTIONS} less those a statement that writes may need: sequences. */
    static final List<String> ACTION_FORBIDDEN_FUNCTIONS = FORBIDDEN_FUNCTIONS.stream()
        .filter(f -> !Set.of("nextval", "setval", "currval", "lastval").contains(f)).toList();

    /**
     * Checks one statement of a flow's sql node and returns it without trailing semicolons. Throws
     * {@link IllegalArgumentException} saying why it is refused. A first line of defence, as for
     * custom rules: the node also runs only on a source that allows SQL and writing, in one
     * transaction with a timeout, and not under a login that is a superuser or may reach files or
     * other databases.
     */
    public static String checkAction(String sql, int maxLength) {
        String s = sql == null ? "" : sql.strip();
        List<Token> tokens;
        try {
            tokens = new ArrayList<>(tokens(s));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        while (!tokens.isEmpty() && tokens.getLast().kind() == Kind.SYMBOL && tokens.getLast().text().equals(";")) {
            s = s.substring(0, tokens.getLast().at()).strip();
            tokens.removeLast();
        }
        if (tokens.isEmpty() || s.length() > maxLength) {
            throw new IllegalArgumentException("one SQL statement, at most " + maxLength + " characters");
        }
        // Before the first word: a statement that starts with an escape is told about the braces
        String escape = braces(tokens, false);
        if (escape != null) {
            throw new IllegalArgumentException(escape);
        }
        Token first = tokens.getFirst();
        if (first.kind() != Kind.WORD || !ACTION_FIRST_WORDS.contains(first.text())) {
            throw new IllegalArgumentException("a statement starts with " + String.join(", ",
                ACTION_FIRST_WORDS.stream().sorted().map(w -> w.toUpperCase(Locale.ROOT)).toList()) + ": a sql node "
                + "changes data or tables and never returns rows; read with a pipeline");
        }
        String problem = singleStatement(tokens);
        if (problem == null) {
            problem = driverSplits(s);
        }
        if (problem != null) {
            throw new IllegalArgumentException(problem + "; give each statement as its own item");
        }
        String rewritten = sentAsWritten(s);
        if (rewritten != null) {
            throw new IllegalArgumentException(rewritten);
        }
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.kind() == Kind.PARAMETER) {
                throw new IllegalArgumentException("parameters such as " + t.text() + " are not allowed");
            }
            if (unicodeName(t) != null) {
                throw new IllegalArgumentException(unicodeName(t));
            }
            if (escapeString(t) != null) {
                throw new IllegalArgumentException(escapeString(t));
            }
            // REFRESH MATERIALIZED VIEW only fills a view that is there
            boolean refresh = first.text().equals("refresh") && t.text().equals("view");
            // GROUP BY is a clause, not the account CREATE GROUP makes
            boolean clause = t.kind() == Kind.WORD && t.text().equals("group") && groupBy(tokens, i);
            if (t.kind() == Kind.WORD && ACTION_FORBIDDEN_WORDS.contains(t.text()) && !refresh && !clause) {
                throw new IllegalArgumentException("'" + t.text() + "' is not allowed: a sql node does not create or "
                    + "change logins, rights, code that runs later or ways out of the database, and never returns "
                    + "rows (quote it, \"" + t.text() + "\", if it is a column or table name)");
            }
            if (t.kind() == Kind.WORD || t.kind() == Kind.QUOTED_WORD) {
                String name = t.text().toLowerCase(Locale.ROOT);
                for (String f : ACTION_FORBIDDEN_FUNCTIONS) {
                    if (name.startsWith(f)) {
                        throw new IllegalArgumentException("'" + t.text() + "' is not allowed: it acts outside the "
                            + "statement (files, other sessions or databases, settings or locks)");
                    }
                }
            }
        }
        return s;
    }

    /**
     * Null when the tokens are one statement: no {@code ;} and parentheses balanced (never closing
     * more than were opened), so the SQL cannot close the query it is placed in. Otherwise why not.
     */
    public static String singleStatement(List<Token> tokens) {
        int depth = 0;
        for (Token t : tokens) {
            if (t.kind() != Kind.SYMBOL) {
                continue;
            }
            switch (t.text()) {
                case ";" -> {
                    return "one statement only";
                }
                case "(" -> depth++;
                case ")" -> {
                    if (--depth < 0) {
                        return "a ')' closes more than was opened";
                    }
                }
                default -> { }
            }
        }
        return depth == 0 ? null : "a '(' is not closed";
    }

    /** True when {@code sql} tokenizes as one statement with balanced parentheses. */
    public static boolean isSingleStatement(String sql) {
        try {
            return singleStatement(tokens(sql)) == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}

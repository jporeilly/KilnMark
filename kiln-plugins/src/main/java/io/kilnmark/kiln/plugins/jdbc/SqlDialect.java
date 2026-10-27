package io.kilnmark.kiln.plugins.jdbc;

import java.util.List;
import java.util.Locale;

/**
 * What differs between databases when Kilnmark writes SQL for them, such as data quality checks run
 * in the database. Every name goes through {@link #quote} and every value through {@link #literal},
 * so no identifier or value from a catalogue or a rule is ever pasted into SQL as it is.
 */
public interface SqlDialect {

    /** An identifier, quoted so any name works: {@code "a ""b"""} or {@code [a ]]b]}. */
    String quote(String identifier);

    /** {@code schema.table}, both quoted. */
    default String table(String schema, String table) {
        return quote(schema) + "." + quote(table);
    }

    /** A string literal, with quotes doubled. */
    default String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /** A number literal; refuses anything that is not a plain number. */
    default String number(String value) {
        if (!value.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")) {
            throw new IllegalArgumentException("Not a number: " + value);
        }
        return value;
    }

    /** {@code expression} as text. */
    String text(String expression);

    /** At most {@code n} rows of {@code select}. */
    String limit(String select, int n);

    /** A condition true when {@code expression} matches the regular expression, or null when the database has none. */
    String regex(String expression, String pattern);

    /** The current time, in UTC where the database can say so. */
    String now();

    /** Statements that make the current transaction read-only, run first; empty if the database cannot. */
    List<String> readOnly();

    /** Whether {@link #readOnly} is enforced by the database rather than only advised. */
    boolean enforcesReadOnly();

    /** Statements that stop any statement of the current transaction after {@code millis}; empty if none. */
    default List<String> timeout(long millis) {
        return List.of();
    }

    /** PostgreSQL. */
    SqlDialect POSTGRESQL = new SqlDialect() {
        @Override
        public String quote(String identifier) {
            return "\"" + identifier.replace("\"", "\"\"") + "\"";
        }

        @Override
        public String literal(String value) {
            if (value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Text holding a zero character cannot be sent to PostgreSQL");
            }
            // Kilnmark turns standard_conforming_strings on in each transaction (readOnly); E'' strings are never used.
            return "'" + value.replace("'", "''") + "'";
        }

        @Override
        public String text(String expression) {
            return "CAST(" + expression + " AS text)";
        }

        @Override
        public String limit(String select, int n) {
            return "SELECT * FROM (" + select + ") limited LIMIT " + n;
        }

        @Override
        public String regex(String expression, String pattern) {
            return text(expression) + " ~ " + literal(pattern);
        }

        @Override
        public String now() {
            return "now()";
        }

        @Override
        public List<String> readOnly() {
            // Literals are written with quotes doubled, which is only safe with standard strings.
            return List.of("SET TRANSACTION READ ONLY", "SET LOCAL standard_conforming_strings = on");
        }

        @Override
        public List<String> timeout(long millis) {
            return List.of("SET LOCAL statement_timeout = " + Math.max(1, millis));
        }

        @Override
        public boolean enforcesReadOnly() {
            return true;
        }
    };

    /** SQL Server: no regular expressions before 2025, and no read-only transactions. */
    SqlDialect SQL_SERVER = new SqlDialect() {
        @Override
        public String quote(String identifier) {
            return "[" + identifier.replace("]", "]]") + "]";
        }

        @Override
        public String literal(String value) {
            return "N'" + value.replace("'", "''") + "'";
        }

        @Override
        public String text(String expression) {
            return "CAST(" + expression + " AS nvarchar(max))";
        }

        @Override
        public String limit(String select, int n) {
            return "SELECT TOP (" + n + ") * FROM (" + select + ") limited";
        }

        @Override
        public String regex(String expression, String pattern) {
            return null; // evaluated by Kiln instead, while streaming the table
        }

        @Override
        public String now() {
            return "SYSUTCDATETIME()";
        }

        @Override
        public List<String> readOnly() {
            return List.of();
        }

        @Override
        public boolean enforcesReadOnly() {
            return false;
        }
    };

    /** MySQL 8.0.19 and later. */
    SqlDialect MYSQL = new MySql(false);

    /** MariaDB 10.6 and later: MySQL's dialect, with its own statement time limit. */
    SqlDialect MARIADB = new MySql(true);

    /** The dialect for a source kind, or the standard one. */
    static SqlDialect forKind(String kind) {
        return switch (kind == null ? "" : kind.toLowerCase(Locale.ROOT)) {
            case "sqlserver" -> SQL_SERVER;
            case "mysql" -> MYSQL;
            case "mariadb" -> MARIADB;
            default -> POSTGRESQL;
        };
    }

    /**
     * MySQL and MariaDB. Names are quoted {@code `like this`}, a backtick doubled. String literals
     * are written as UTF-8 bytes in hex with a character set introducer ({@code _utf8mb4 X'616263'}),
     * which means the same whatever the session's {@code sql_mode} (with or without
     * {@code NO_BACKSLASH_ESCAPES} or {@code ANSI_QUOTES}), so no value can end a literal early.
     * Regular expressions are left to Kiln: the two databases' regex libraries differ (ICU and
     * PCRE), and rules are written in PostgreSQL's syntax.
     */
    final class MySql implements SqlDialect {

        private static final java.util.HexFormat HEX = java.util.HexFormat.of().withUpperCase();

        private final boolean mariadb;

        private MySql(boolean mariadb) {
            this.mariadb = mariadb;
        }

        @Override
        public String quote(String identifier) {
            return "`" + identifier.replace("`", "``") + "`";
        }

        @Override
        public String literal(String value) {
            return "_utf8mb4 X'" + HEX.formatHex(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "'";
        }

        @Override
        public String text(String expression) {
            return "CAST(" + expression + " AS CHAR)";
        }

        @Override
        public String limit(String select, int n) {
            return "SELECT * FROM (" + select + ") limited LIMIT " + n;
        }

        @Override
        public String regex(String expression, String pattern) {
            return null; // evaluated by Kiln instead, while streaming the table
        }

        @Override
        public String now() {
            return "UTC_TIMESTAMP(6)";
        }

        /** Applies to the next transaction, which the check's first query starts. */
        @Override
        public List<String> readOnly() {
            return List.of("SET TRANSACTION READ ONLY");
        }

        @Override
        public boolean enforcesReadOnly() {
            return true;
        }

        /** MySQL's {@code max_execution_time} (milliseconds, SELECT only); MariaDB's {@code max_statement_time} (seconds). */
        @Override
        public List<String> timeout(long millis) {
            long ms = Math.max(1, millis);
            return List.of(mariadb
                ? "SET SESSION max_statement_time = " + java.math.BigDecimal.valueOf(ms, 3).toPlainString()
                : "SET SESSION max_execution_time = " + ms);
        }
    }
}

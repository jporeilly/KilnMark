package io.kilnmark.kiln.plugins.jdbc;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.kiln.plugins.jdbc.JdbcColumn.Kind;
import io.kilnmark.kiln.plugins.write.WriteColumn;
import io.kilnmark.kiln.plugins.write.WriteSettings;

/**
 * The SQL a MySQL or MariaDB writer runs. Their CREATE, RENAME and DROP commit by themselves, so a
 * created or replacing table is filled under a temporary name ({@code kiln_new_…}) and given its
 * name at the end with {@code RENAME TABLE}, which renames several tables in one atomic step; a
 * failure drops it again. Values are bound as parameters (dates and times as text, so the driver
 * never applies Kilnmark's time zone) or, for tables the step created, streamed to
 * {@code LOAD DATA LOCAL INFILE}. Sessions run in strict mode with time zone {@code +00:00}.
 *
 * <p>Table grants in MySQL belong to the table's name ({@code mysql.tables_priv}), not to the
 * table: RENAME and DROP leave them where they are, so after a replace the new table has the old
 * one's grants without any being repeated. MySQL has no table owners and no row-level security.
 */
final class MySqlWriteSql extends WriteSql {

    static final MySqlWriteSql MYSQL = new MySqlWriteSql(false);
    static final MySqlWriteSql MARIADB = new MySqlWriteSql(true);

    /** Characters of row data per {@code LOAD DATA} at most, well below the 16 MiB packets MariaDB allows by default. */
    static final int LOAD_CHARS = 4 << 20;

    /** Permission errors: database, table, column and other access denied. */
    private static final Set<Integer> DENIED = Set.of(1044, 1142, 1143, 1227);

    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    /**
     * The login as {@code information_schema}'s privilege views name it: {@code 'user'@'host'}.
     * Host names never hold {@code @}; user names hardly ever do.
     */
    private static final String ME = "CONCAT('''', SUBSTRING_INDEX(CURRENT_USER(), '@', 1), '''@''', "
        + "SUBSTRING_INDEX(CURRENT_USER(), '@', -1), '''')";

    private final boolean mariadb;

    private MySqlWriteSql(boolean mariadb) {
        this.mariadb = mariadb;
    }

    @Override
    SqlDialect dialect() {
        return mariadb ? SqlDialect.MARIADB : SqlDialect.MYSQL;
    }

    /** 64 characters; no trailing space, zero character or character beyond the Basic Multilingual Plane. */
    @Override
    String identifierProblem(String name) {
        int chars = name.codePointCount(0, name.length());
        if (chars > 64) {
            return "the name " + name + " is " + chars + " characters long; MySQL allows 64, so shorten it";
        }
        if (name.endsWith(" ")) {
            return "the name '" + name + "' ends with a space, which MySQL does not allow";
        }
        if (name.indexOf('\0') >= 0) {
            return "a name holds a zero character";
        }
        if (name.codePoints().anyMatch(c -> c > 0xFFFF)) {
            return "the name " + name + " holds a character MySQL names cannot hold (an emoji, say)";
        }
        return null;
    }

    /** Column names ignore case in MySQL. */
    @Override
    boolean sameName(String a, String b) {
        return a.equalsIgnoreCase(b);
    }

    /**
     * Text is LONGTEXT unless {@code text_length} asks for VARCHAR; timestamps with a zone are
     * DATETIME(6) holding UTC, since TIMESTAMP ends in 2038 (they read back as timestamps without
     * a zone).
     */
    @Override
    String columnType(WriteColumn c, int textLength) {
        return switch (c.kind()) {
            case BOOLEAN -> "tinyint(1)";
            case INT16 -> "smallint";
            case INT32 -> "int";
            case INT64 -> "bigint";
            case FLOAT32 -> "float";
            case FLOAT64 -> "double";
            case DECIMAL -> "decimal(" + Math.max(1, c.precision()) + "," + c.scale() + ")";
            case TEXT -> textLength > 0 ? "varchar(" + textLength + ")" : "longtext";
            case BINARY -> "longblob";
            case DATE -> "date";
            case TIME -> "time(6)";
            case TIMESTAMP, TIMESTAMP_TZ -> "datetime(6)";
        };
    }

    /** UTF-8 throughout (utf8mb4), whatever the server's default character set. */
    @Override
    String createTable(TableName t, List<WriteColumn> columns, int textLength) {
        return super.createTable(t, columns, textLength) + " DEFAULT CHARACTER SET utf8mb4";
    }

    /**
     * {@code INSERT … ON DUPLICATE KEY UPDATE}: MySQL names the new row ({@code AS kiln_new}, 8.0.19
     * and later); MariaDB reads it with {@code VALUES(column)}.
     */
    @Override
    String upsert(TableName t, List<String> columns, List<String> key) {
        List<String> rest = columns.stream().filter(c -> !key.contains(c)).toList();
        // with nothing else to update, a key column set to itself keeps the row as it is
        List<String> set = rest.isEmpty() ? List.of(key.get(0)) : rest;
        String row = quote("kiln_new");
        return insert(t, columns) + (mariadb ? "" : " AS " + row) + " ON DUPLICATE KEY UPDATE " + set.stream()
            .map(c -> quote(c) + " = " + (rest.isEmpty() ? quote(c) : mariadb ? "VALUES(" + quote(c) + ")"
                : row + "." + quote(c)))
            .collect(Collectors.joining(", "));
    }

    @Override
    Sql exists(TableName t) {
        return new Sql("SELECT COUNT(*) > 0 FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?",
            List.of(t.schema(), t.table()));
    }

    /** NOT NULL, no default, not AUTO_INCREMENT and not generated. */
    @Override
    Sql requiredColumns(TableName t) {
        return new Sql("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? "
            + "AND IS_NULLABLE = 'NO' AND COLUMN_DEFAULT IS NULL AND EXTRA NOT LIKE '%auto_increment%' "
            + "AND EXTRA NOT LIKE '%GENERATED%'", List.of(t.schema(), t.table()));
    }

    /**
     * Unique indexes by name and column, leaving out any index with a part that is a prefix of
     * a column or an expression: those do not make the whole column unique.
     */
    @Override
    Sql uniqueKeys(TableName t) {
        return new Sql("SELECT s.INDEX_NAME, s.COLUMN_NAME FROM information_schema.STATISTICS s WHERE s.TABLE_SCHEMA = ? "
            + "AND s.TABLE_NAME = ? AND s.NON_UNIQUE = 0 AND NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS p "
            + "WHERE p.TABLE_SCHEMA = s.TABLE_SCHEMA AND p.TABLE_NAME = s.TABLE_NAME AND p.INDEX_NAME = s.INDEX_NAME "
            + "AND (p.SUB_PART IS NOT NULL OR p.COLUMN_NAME IS NULL))", List.of(t.schema(), t.table()));
    }

    @Override
    String keyIndexAdvice(TableName t, List<String> key) {
        return "MySQL and MariaDB find the existing row through a unique index or primary key, and the table has none "
            + "on exactly those columns. Ask a database administrator to add one, for example ALTER TABLE " + table(t)
            + " ADD UNIQUE (" + columnList(key) + "). Note that any other unique index on the table also makes a row "
            + "update instead of insert";
    }

    /**
     * Not a table (a view, or a MariaDB sequence); system-versioned (its history would be lost);
     * partitioned (the new table would not be); foreign keys from other tables, which the rename
     * would point at the old table and the drop then refuse; triggers, which go with the old table.
     */
    @Override
    Sql blockers(TableName t) {
        String s = t.schema();
        String n = t.table();
        return new Sql("SELECT 'it is a view, not a table' FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? "
            + "AND TABLE_NAME = ? AND TABLE_TYPE = 'VIEW' "
            + "UNION ALL SELECT 'it is a sequence, not a table' FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? "
            + "AND TABLE_NAME = ? AND TABLE_TYPE = 'SEQUENCE' "
            + "UNION ALL SELECT 'it is system-versioned, and its history would be lost' FROM information_schema.TABLES "
            + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND TABLE_TYPE = 'SYSTEM VERSIONED' "
            + "UNION ALL SELECT DISTINCT 'it is partitioned, and the new table would not be' FROM "
            + "information_schema.PARTITIONS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND PARTITION_NAME IS NOT NULL "
            + "UNION ALL SELECT DISTINCT CONCAT('the foreign key ', CONSTRAINT_NAME, ' on ', CONSTRAINT_SCHEMA, "
            + "'.', TABLE_NAME, ' refers to it') FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE "
            + "UNIQUE_CONSTRAINT_SCHEMA = ? AND REFERENCED_TABLE_NAME = ? AND NOT (CONSTRAINT_SCHEMA = ? "
            + "AND TABLE_NAME = ?) "
            + "UNION ALL SELECT CONCAT('the trigger ', TRIGGER_NAME, ' is on it, and would be dropped with it') FROM "
            + "information_schema.TRIGGERS WHERE EVENT_OBJECT_SCHEMA = ? AND EVENT_OBJECT_TABLE = ? "
            + "UNION ALL SELECT 'the login cannot see whether triggers are on it (that needs the TRIGGER privilege)' "
            + "FROM DUAL WHERE " + notAllowed("TRIGGER", true),
            List.of(s, n, s, n, s, n, s, n, s, n, s, n, s, n, s, s, n, s, n));
    }

    /** No row-level security in MySQL. */
    @Override
    Sql rowSecurity(TableName t) {
        return new Sql("SELECT 0");
    }

    /** No table owners in MySQL: never a row. */
    @Override
    Sql owner(TableName t) {
        return new Sql("SELECT NULL, 0 FROM DUAL WHERE 1 = 0");
    }

    @Override
    String setOwner(TableName t, String owner) {
        throw new IllegalStateException("MySQL tables have no owner");
    }

    @Override
    String grantOwnerRights(TableName t, String owner) {
        throw new IllegalStateException("MySQL tables have no owner");
    }

    /** Waits for metadata locks (RENAME, DROP) and row locks, in whole seconds. */
    @Override
    List<String> lockTimeout(long millis) {
        long seconds = Math.max(1, (millis + 999) / 1000);
        return List.of("SET SESSION lock_wait_timeout = " + seconds, "SET SESSION innodb_lock_wait_timeout = " + seconds);
    }

    /** 1205: lock wait timeout exceeded, for metadata and row locks alike. */
    @Override
    boolean lockTimedOut(SQLException e) {
        return e.getErrorCode() == 1205;
    }

    /** Grants stay with the table's name (see the class comment): none to repeat. */
    @Override
    Sql grants(TableName t) {
        return new Sql("SELECT NULL FROM DUAL WHERE 1 = 0");
    }

    @Override
    String grant(TableName to, ResultSet row) {
        return null;
    }

    @Override
    Sql rename(TableName from, String name) {
        return new Sql("RENAME TABLE " + table(from) + " TO " + table(new TableName(from.schema(), name)));
    }

    /** One RENAME TABLE swaps both names atomically; then the old table goes. */
    @Override
    List<Sql> swap(TableName target, String newName, String oldName) {
        TableName old = new TableName(target.schema(), oldName);
        return List.of(new Sql("RENAME TABLE " + table(target) + " TO " + table(old) + ", "
            + table(new TableName(target.schema(), newName)) + " TO " + table(target)), new Sql(drop(old)));
    }

    /**
     * Rights from {@code information_schema}'s privilege views, which list what is granted to the
     * login itself: globally, on the database (patterns included), on the table or on some of its
     * columns. They do not show what roles give, so with a role active a missing privilege counts as
     * unknown and the database decides when the rows go in.
     */
    @Override
    List<Check> privileges(String mode, TableName t, boolean exists) {
        List<Check> out = new ArrayList<>();
        switch (mode) {
            case "create", "replace" -> {
                out.add(new Check(new Sql("SELECT CASE WHEN EXISTS (SELECT 1 FROM information_schema.SCHEMATA WHERE "
                    + "SCHEMA_NAME = ?) THEN 1 END", List.of(t.schema())), "the database " + t.schema() + " does not "
                    + "exist, or the login may not use it: ask a database administrator to create it or grant rights "
                    + "in it, or write to another database"));
                for (String p : List.of("CREATE", "INSERT", "ALTER", "DROP")) {
                    out.add(check(p, false, t));
                }
                if (exists) {
                    out.add(check("ALTER", true, t));
                    out.add(check("DROP", true, t));
                }
            }
            case "append" -> out.add(check("INSERT", true, t));
            case "upsert" -> {
                out.add(check("INSERT", true, t));
                out.add(check("UPDATE", true, t));
            }
            default -> { }
        }
        return out;
    }

    /** One true/false/unknown check of a privilege, in the database or (with {@code onTable}) on the table. */
    private Check check(String privilege, boolean onTable, TableName t) {
        List<String> params = new ArrayList<>(List.of(t.schema()));
        if (onTable) {
            params.addAll(List.of(t.schema(), t.table(), t.schema(), t.table()));
        }
        return new Check(new Sql("SELECT CASE WHEN " + granted(privilege, onTable) + " THEN 1 WHEN " + rolesActive()
            + " THEN NULL ELSE 0 END", params), null);
    }

    /** Whether {@code privilege} is granted globally, on the database (one parameter) and, with {@code onTable}, on the table or a column of it (four more). */
    private static String granted(String privilege, boolean onTable) {
        String p = "'" + privilege + "'";
        return "(EXISTS (SELECT 1 FROM information_schema.USER_PRIVILEGES WHERE GRANTEE = " + ME
            + " AND PRIVILEGE_TYPE = " + p + ") OR EXISTS (SELECT 1 FROM information_schema.SCHEMA_PRIVILEGES WHERE "
            + "GRANTEE = " + ME + " AND PRIVILEGE_TYPE = " + p + " AND ? LIKE TABLE_SCHEMA)"
            + (onTable ? " OR EXISTS (SELECT 1 FROM information_schema.TABLE_PRIVILEGES WHERE GRANTEE = " + ME
            + " AND PRIVILEGE_TYPE = " + p + " AND TABLE_SCHEMA = ? AND TABLE_NAME = ?) OR EXISTS (SELECT 1 FROM "
            + "information_schema.COLUMN_PRIVILEGES WHERE GRANTEE = " + ME + " AND PRIVILEGE_TYPE = " + p
            + " AND TABLE_SCHEMA = ? AND TABLE_NAME = ?)" : "") + ")";
    }

    /**
     * A condition true when the login certainly lacks {@code privilege} on the table: not granted
     * to it, and no role active that might grant it. Takes five parameters: schema, then schema
     * and table twice.
     */
    private String notAllowed(String privilege, boolean onTable) {
        return "NOT " + granted(privilege, onTable) + " AND NOT " + rolesActive();
    }

    private String rolesActive() {
        return mariadb ? "CURRENT_ROLE() IS NOT NULL" : "CURRENT_ROLE() <> 'NONE'";
    }

    @Override
    String needs(String mode, TableName t, boolean exists) {
        return switch (mode) {
            case "create" -> "CREATE, INSERT, ALTER and DROP in the database " + t.schema() + " (the table is filled "
                + "under a temporary name and renamed at the end)";
            case "replace" -> "CREATE, INSERT, ALTER and DROP in the database " + t.schema()
                + (exists ? ", and ALTER and DROP on " + t + ", since replace renames and drops the old table" : "");
            case "upsert" -> "INSERT and UPDATE on " + t;
            default -> "INSERT on " + t;
        };
    }

    @Override
    boolean permissionDenied(SQLException e) {
        return DENIED.contains(e.getErrorCode());
    }

    @Override
    int nullType(Kind kind) {
        return switch (kind) {
            case BOOLEAN -> Types.BOOLEAN;
            case INT16 -> Types.SMALLINT;
            case INT32 -> Types.INTEGER;
            case INT64 -> Types.BIGINT;
            case FLOAT32 -> Types.REAL;
            case FLOAT64 -> Types.DOUBLE;
            case DECIMAL -> Types.DECIMAL;
            case TEXT, DATE, TIME, TIMESTAMP, TIMESTAMP_TZ -> Types.VARCHAR;
            case BINARY -> Types.VARBINARY;
        };
    }

    /** VARCHAR lengths count characters. */
    @Override
    int length(String s) {
        return s.codePointCount(0, s.length());
    }

    @Override
    void bindText(PreparedStatement ps, int i, String s) throws SQLException {
        ps.setString(i, s);
    }

    /** Dates and times as MySQL's own text ({@link #temporal}), timestamps with a zone in UTC. */
    @Override
    void bind(PreparedStatement ps, int i, Kind kind, Object v) throws SQLException {
        switch (kind) {
            case DATE, TIME, TIMESTAMP, TIMESTAMP_TZ -> {
                if (v == null) {
                    ps.setNull(i, Types.VARCHAR);
                } else {
                    ps.setString(i, temporal(v));
                }
            }
            default -> super.bind(ps, i, kind, v);
        }
    }

    /** NaN and infinity, which MySQL cannot store; years outside 0 to 9999, which it cannot write. */
    @Override
    String valueProblem(Kind kind, Object v) {
        return switch (v) {
            case Double d when d.isNaN() || d.isInfinite() -> "MySQL cannot store " + d + " in a number column";
            case Float f when f.isNaN() || f.isInfinite() -> "MySQL cannot store " + f + " in a number column";
            case LocalDate d when d.getYear() < 0 || d.getYear() > 9999 -> yearProblem(d.getYear());
            case LocalDateTime t when t.getYear() < 0 || t.getYear() > 9999 -> yearProblem(t.getYear());
            case Instant t when year(t) < 0 || year(t) > 9999 -> yearProblem(year(t));
            case null, default -> null;
        };
    }

    private static int year(Instant t) {
        return LocalDateTime.ofInstant(t, ZoneOffset.UTC).getYear();
    }

    private static String yearProblem(int year) {
        return "the year " + year + " is outside what MySQL dates hold (0 to 9999)";
    }

    /**
     * {@code 2025-01-31}, {@code 09:30:00.123456}, {@code 2025-01-31 09:30:00.123456} (a timestamp
     * with a zone in UTC): what MySQL reads for DATE, TIME, DATETIME and TIMESTAMP columns.
     */
    static String temporal(Object v) {
        return switch (v) {
            case LocalDate d -> date(d);
            case LocalTime t -> time(t);
            case LocalDateTime t -> date(t.toLocalDate()) + " " + time(t.toLocalTime());
            case Instant i -> temporal(LocalDateTime.ofInstant(i, ZoneOffset.UTC));
            default -> v.toString();
        };
    }

    private static String date(LocalDate d) {
        return String.format(Locale.ROOT, "%04d-%02d-%02d", d.getYear(), d.getMonthValue(), d.getDayOfMonth());
    }

    private static String time(LocalTime t) {
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%06d", t.getHour(), t.getMinute(), t.getSecond(),
            t.getNano() / 1_000);
    }

    @Override
    int defaultBatchSize(String mode) {
        return mode.equals("create") || mode.equals("replace") ? 5000 : 1000;
    }

    /** VARCHAR holds at most 65,535 bytes a row: 16,383 four-byte characters. */
    @Override
    long maxTextLength() {
        return 16_383;
    }

    /** {@code LOAD DATA LOCAL INFILE} for tables the step creates, unless {@code bulk_load} is false. */
    @Override
    boolean bulk(String mode, WriteSettings settings) throws ConnectorException {
        return (mode.equals("create") || mode.equals("replace")) && settings.bool("bulk_load", true);
    }

    @Override
    boolean transactionalDdl() {
        return false;
    }

    /**
     * Strict mode ({@code STRICT_ALL_TABLES}), so a value that does not fit fails the write rather
     * than being cut or zeroed with a warning; and without {@code NO_BACKSLASH_ESCAPES}, so the
     * privilege checks' patterns read as MySQL's grants write them. The time zone ({@code +00:00})
     * is set when connecting.
     */
    @Override
    void prepareSession(Connection connection) throws SQLException {
        String mode;
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery("SELECT @@SESSION.sql_mode")) {
            mode = rs.next() && rs.getString(1) != null ? rs.getString(1) : "";
        }
        Set<String> modes = new LinkedHashSet<>();
        for (String m : mode.split(",")) {
            if (!m.isBlank() && !m.strip().equalsIgnoreCase("NO_BACKSLASH_ESCAPES")) {
                modes.add(m.strip().toUpperCase(Locale.ROOT));
            }
        }
        modes.add("STRICT_ALL_TABLES");
        try (PreparedStatement ps = connection.prepareStatement("SET SESSION sql_mode = ?")) {
            ps.setString(1, String.join(",", modes));
            ps.execute();
        }
    }

    /** Whether the server accepts {@code LOAD DATA LOCAL} ({@code local_infile}, off by default on MySQL 8). */
    Sql localInfileAllowed() {
        return new Sql("SELECT @@GLOBAL.local_infile");
    }

    /**
     * {@code LOAD DATA LOCAL INFILE} into {@code t}'s {@code columns}: comma-separated fields,
     * text quoted with quotes doubled (no escape character, so backslashes are plain characters),
     * an unquoted {@code NULL} for null, bytes as hex through {@code UNHEX}. The file name is a
     * placeholder: the driver sends the stream it is given, and refuses a server asking for any
     * other file.
     */
    String loadData(TableName t, List<String> columns, List<Kind> kinds) {
        List<String> targets = new ArrayList<>();
        List<String> sets = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            if (kinds.get(i) == Kind.BINARY) {
                String var = "@kiln_" + (i + 1);
                targets.add(var);
                sets.add(quote(columns.get(i)) + " = UNHEX(" + var + ")");
            } else {
                targets.add(quote(columns.get(i)));
            }
        }
        return "LOAD DATA LOCAL INFILE 'kilnmark-rows' INTO TABLE " + table(t) + " CHARACTER SET utf8mb4 "
            + "FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '\"' ESCAPED BY '' LINES TERMINATED BY '\n' ("
            + String.join(", ", targets) + ")" + (sets.isEmpty() ? "" : " SET " + String.join(", ", sets));
    }

    /** Appends {@code v} of {@code kind} as one field of {@link #loadData}'s format. */
    void loadField(StringBuilder b, Kind kind, Object v) {
        if (v == null) {
            b.append("NULL");
            return;
        }
        switch (kind) {
            case TEXT -> {
                String s = (String) v;
                b.append('"');
                for (int i = 0; i < s.length(); i++) {
                    char c = s.charAt(i);
                    if (c == '"') {
                        b.append('"');
                    }
                    b.append(c);
                }
                b.append('"');
            }
            case BINARY -> b.append(HEX.formatHex((byte[]) v));
            case BOOLEAN -> b.append((Boolean) v ? '1' : '0');
            case DECIMAL -> b.append(((BigDecimal) v).toPlainString());
            case DATE, TIME, TIMESTAMP, TIMESTAMP_TZ -> b.append(temporal(v));
            default -> b.append(v); // integers and floats
        }
    }

    /** What {@link #loadField} writes for one value. */
    String loadField(Kind kind, Object v) {
        StringBuilder b = new StringBuilder();
        loadField(b, kind, v);
        return b.toString();
    }
}

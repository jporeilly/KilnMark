package io.kilnmark.kiln.plugins.jdbc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.kilnmark.kiln.plugins.jdbc.JdbcColumn.Kind;
import io.kilnmark.kiln.plugins.write.TextForm;
import io.kilnmark.kiln.plugins.write.WriteColumn;

/**
 * The SQL an Oracle writer runs. Oracle's CREATE, ALTER and DROP commit by themselves, so a created
 * or replacing table is filled under a temporary name ({@code kiln_new_…}) and given its name at
 * the end; a failure drops it again. Replace renames the old table away and the new one into
 * place in one PL/SQL block that puts the old name back if the second rename fails, then drops the
 * old table. Oracle has no rename of two tables at once, so for that instant a reader may find no
 * table by that name. Upsert is one {@code MERGE} a row. Values are bound as parameters.
 *
 * <p>Nothing is changed silently: a decimal is never rounded (also beyond Oracle's 38 significant
 * digits, which it would round, or a {@code FLOAT(b)} column's digits) nor taken outside a NUMBER's
 * range; a timestamp keeps its digits of a second or is refused; empty text and empty bytes, which
 * Oracle stores as null, are refused unless the node allows it ({@code empty_text_as_null}), and in
 * an upsert key always. Text must fit the column's character set, character by character: on a
 * database whose character set is not Unicode ({@code WE8MSWIN1252}, say) a character it lacks
 * would be stored as {@code ?}, so the row is refused, naming it. Columns sized in bytes are checked
 * in bytes of their character set. NVARCHAR2, NCHAR and NCLOB columns take their values as national
 * character data ({@code setNString}), in the national character set.
 *
 * <p>Grants belong to the table, so replace repeats the old table's grants on the new one (unless
 * {@code keep_grants} is false). A table belongs to its schema's user: there is no owner to hand
 * over. Virtual private database policies count as row-level security.
 */
final class OracleWriteSql extends WriteSql {

    /** Permission errors: insufficient privileges, no rights on a tablespace, table or view not visible. */
    private static final Set<Integer> DENIED = Set.of(1031, 1950, 942, 41900);

    private static final Set<String> PRIVILEGES = Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "ALTER", "REFERENCES",
        "INDEX", "READ", "DEBUG", "FLASHBACK", "ON COMMIT REFRESH", "QUERY REWRITE");

    /** A name Oracle would store in upper case if written without quotes. */
    private static final Pattern PLAIN = Pattern.compile("[a-z][a-z0-9_$#]*");

    /** Oracle keeps at most 38 significant digits in a NUMBER. */
    static final int MAX_DIGITS = 38;

    /** A NUMBER's adjusted exponent ranges from -130 to 125: 1E-130 up to, not including, 1E126. */
    static final int MIN_EXPONENT = -130;
    static final int MAX_EXPONENT = 125;

    private final boolean booleans;
    private final boolean emptyAsNull;
    private final OracleCharset charset;
    private final OracleCharset nationalCharset;

    /** On a Unicode database ({@code AL32UTF8}, national {@code AL16UTF16}). */
    OracleWriteSql(boolean booleans, boolean emptyAsNull) {
        this(booleans, emptyAsNull, "AL32UTF8", "AL16UTF16");
    }

    /**
     * @param booleans        whether the server has a BOOLEAN type (23ai and later); before it,
     *                        true or false columns are created as NUMBER(1)
     * @param emptyAsNull     whether empty text may be written, as the null Oracle makes of it
     * @param charset         the database's character set ({@code NLS_CHARACTERSET})
     * @param nationalCharset its national character set ({@code NLS_NCHAR_CHARACTERSET})
     */
    OracleWriteSql(boolean booleans, boolean emptyAsNull, String charset, String nationalCharset) {
        this.booleans = booleans;
        this.emptyAsNull = emptyAsNull;
        this.charset = OracleCharset.of(charset);
        this.nationalCharset = OracleCharset.of(nationalCharset);
    }

    /** NVARCHAR2, NCHAR and NCLOB: national character data, in the national character set. */
    static boolean national(String typeName) {
        return typeName.equals("NVARCHAR2") || typeName.equals("NCHAR") || typeName.equals("NCLOB");
    }

    /** Columns that keep text in a character set (not JSON, intervals or other types that parse it). */
    private static boolean characterColumn(String typeName) {
        return national(typeName) || Set.of("VARCHAR2", "CHAR", "CLOB", "LONG", "VARCHAR").contains(typeName);
    }

    /** {@code orders} as {@code ORDERS}, as Oracle stores a name written without quotes; any other name as written. */
    static String stored(String name) {
        return name != null && PLAIN.matcher(name).matches() ? name.toUpperCase(java.util.Locale.ROOT) : name;
    }

    @Override
    SqlDialect dialect() {
        return SqlDialect.ORACLE;
    }

    @Override
    String storedName(String name) {
        return stored(name);
    }

    /** At most 128 bytes, no double quote and no zero character. */
    @Override
    String identifierProblem(String name) {
        int bytes = name.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > 128) {
            return "the name " + name + " is " + bytes + " bytes long; Oracle allows 128, so shorten it";
        }
        if (name.indexOf('"') >= 0 || name.indexOf('\0') >= 0) {
            return "the name " + name + " holds a double quote or a zero character, which Oracle names cannot";
        }
        return null;
    }

    /** Ignoring case: input columns named in lower case go to the upper-case columns Oracle usually has. */
    @Override
    boolean sameName(String a, String b) {
        return a.equalsIgnoreCase(b);
    }

    @Override
    String columnType(WriteColumn c, int textLength) {
        return switch (c.kind()) {
            case BOOLEAN -> booleans ? "BOOLEAN" : "NUMBER(1)";
            case INT16 -> "NUMBER(5)";
            case INT32 -> "NUMBER(10)";
            case INT64 -> "NUMBER(19)";
            case FLOAT32 -> "BINARY_FLOAT";
            case FLOAT64 -> "BINARY_DOUBLE";
            case DECIMAL -> c.precision() >= 1 && c.precision() <= MAX_DIGITS && c.scale() <= c.precision()
                ? "NUMBER(" + c.precision() + "," + c.scale() + ")" : "NUMBER";
            case TEXT -> textLength > 0 ? "VARCHAR2(" + textLength + " CHAR)" : "CLOB";
            case BINARY -> "BLOB";
            case DATE -> "DATE";
            case TIME -> "VARCHAR2(15)";
            case TIMESTAMP -> "TIMESTAMP(6)";
            case TIMESTAMP_TZ -> "TIMESTAMP(6) WITH TIME ZONE";
        };
    }

    /**
     * One {@code MERGE} a row, the row given as {@code SELECT ? AS c … FROM DUAL}. The key must be
     * a unique key of the table ({@link #uniqueKeys}), so two pipelines upserting at once cannot
     * both insert a key: the second fails rather than adding a duplicate.
     */
    @Override
    String upsert(TableName t, List<String> columns, List<String> key) {
        List<String> rest = columns.stream().filter(c -> !key.contains(c)).toList();
        String source = quote("kiln_source");
        String target = quote("kiln_target");
        return "MERGE INTO " + table(t) + " " + target + " USING (SELECT "
            + columns.stream().map(c -> "? AS " + quote(c)).collect(Collectors.joining(", ")) + " FROM DUAL) " + source
            + " ON (" + key.stream().map(k -> target + "." + quote(k) + " = " + source + "." + quote(k))
            .collect(Collectors.joining(" AND ")) + ")"
            + (rest.isEmpty() ? "" : " WHEN MATCHED THEN UPDATE SET " + rest.stream()
            .map(c -> target + "." + quote(c) + " = " + source + "." + quote(c)).collect(Collectors.joining(", ")))
            + " WHEN NOT MATCHED THEN INSERT (" + columns.stream().map(c -> target + "." + quote(c))
            .collect(Collectors.joining(", ")) + ") VALUES (" + columns.stream().map(c -> source + "." + quote(c))
            .collect(Collectors.joining(", ")) + ")";
    }

    @Override
    Sql exists(TableName t) {
        return new Sql("SELECT CASE WHEN EXISTS (SELECT 1 FROM ALL_OBJECTS WHERE OWNER = ? AND OBJECT_NAME = ? "
            + "AND OBJECT_TYPE IN ('TABLE', 'VIEW')) THEN 1 ELSE 0 END FROM DUAL", List.of(t.schema(), t.table()));
    }

    /** NOT NULL, no default, not an identity and not virtual. */
    @Override
    Sql requiredColumns(TableName t) {
        return new Sql("SELECT COLUMN_NAME FROM ALL_TAB_COLS WHERE OWNER = ? AND TABLE_NAME = ? AND NULLABLE = 'N' "
            + "AND HIDDEN_COLUMN = 'NO' AND VIRTUAL_COLUMN = 'NO' AND IDENTITY_COLUMN = 'NO' AND DEFAULT_LENGTH IS NULL",
            List.of(t.schema(), t.table()));
    }

    /** Unique indexes on plain columns (function-based ones do not make a column unique). */
    @Override
    Sql uniqueKeys(TableName t) {
        return new Sql("SELECT ic.INDEX_NAME, ic.COLUMN_NAME FROM ALL_INDEXES i JOIN ALL_IND_COLUMNS ic "
            + "ON ic.INDEX_OWNER = i.OWNER AND ic.INDEX_NAME = i.INDEX_NAME WHERE i.TABLE_OWNER = ? AND i.TABLE_NAME = ? "
            + "AND i.UNIQUENESS = 'UNIQUE' AND NOT EXISTS (SELECT 1 FROM ALL_IND_EXPRESSIONS e "
            + "WHERE e.INDEX_OWNER = i.OWNER AND e.INDEX_NAME = i.INDEX_NAME)", List.of(t.schema(), t.table()));
    }

    @Override
    String keyIndexAdvice(TableName t, List<String> key) {
        return "Oracle's MERGE keeps keys unique only with a unique index or primary key on exactly those columns, and "
            + "the table has none. Ask a database administrator to add one, for example ALTER TABLE " + table(t)
            + " ADD UNIQUE (" + columnList(key) + ")";
    }

    /**
     * Not a table (a view, a materialized view); partitioned or temporary (the new table would not
     * be); foreign keys from other tables, which would point at the old table and stop the drop;
     * triggers, which would be dropped with it; materialized view logs and materialized views built
     * on it.
     */
    @Override
    Sql blockers(TableName t) {
        String s = t.schema();
        String n = t.table();
        return new Sql("SELECT 'it is a view, not a table' FROM ALL_VIEWS WHERE OWNER = ? AND VIEW_NAME = ? "
            + "UNION ALL SELECT 'it is a materialized view' FROM ALL_MVIEWS WHERE OWNER = ? AND MVIEW_NAME = ? "
            + "UNION ALL SELECT 'it is partitioned, and the new table would not be' FROM ALL_TABLES WHERE OWNER = ? "
            + "AND TABLE_NAME = ? AND PARTITIONED = 'YES' "
            + "UNION ALL SELECT 'it is a temporary table' FROM ALL_TABLES WHERE OWNER = ? AND TABLE_NAME = ? "
            + "AND TEMPORARY = 'Y' "
            + "UNION ALL SELECT 'the foreign key ' || c.CONSTRAINT_NAME || ' on ' || c.OWNER || '.' || c.TABLE_NAME "
            + "|| ' refers to it' FROM ALL_CONSTRAINTS c JOIN ALL_CONSTRAINTS r ON r.OWNER = c.R_OWNER "
            + "AND r.CONSTRAINT_NAME = c.R_CONSTRAINT_NAME WHERE c.CONSTRAINT_TYPE = 'R' AND r.OWNER = ? "
            + "AND r.TABLE_NAME = ? AND NOT (c.OWNER = ? AND c.TABLE_NAME = ?) "
            + "UNION ALL SELECT 'the trigger ' || TRIGGER_NAME || ' is on it, and would be dropped with it' "
            + "FROM ALL_TRIGGERS WHERE TABLE_OWNER = ? AND TABLE_NAME = ? AND BASE_OBJECT_TYPE = 'TABLE' "
            + "UNION ALL SELECT 'a materialized view log is on it' FROM ALL_MVIEW_LOGS WHERE LOG_OWNER = ? "
            + "AND MASTER = ? "
            + "UNION ALL SELECT 'the materialized view ' || OWNER || '.' || NAME || ' is built on it' "
            + "FROM ALL_DEPENDENCIES WHERE REFERENCED_OWNER = ? AND REFERENCED_NAME = ? AND REFERENCED_TYPE = 'TABLE' "
            + "AND TYPE = 'MATERIALIZED VIEW'",
            List.of(s, n, s, n, s, n, s, n, s, n, s, n, s, n, s, n, s, n));
    }

    /** Virtual private database policies on the table. */
    @Override
    Sql rowSecurity(TableName t) {
        return new Sql("SELECT CASE WHEN EXISTS (SELECT 1 FROM ALL_POLICIES WHERE OBJECT_OWNER = ? AND OBJECT_NAME = ?) "
            + "THEN 1 ELSE 0 END FROM DUAL", List.of(t.schema(), t.table()));
    }

    /** A table belongs to its schema's user: never a row. */
    @Override
    Sql owner(TableName t) {
        return new Sql("SELECT NULL, 0 FROM DUAL WHERE 1 = 0");
    }

    @Override
    String setOwner(TableName t, String owner) {
        throw new IllegalStateException("Oracle tables belong to their schema");
    }

    @Override
    String grantOwnerRights(TableName t, String owner) {
        throw new IllegalStateException("Oracle tables belong to their schema");
    }

    /** How long DDL waits for others' locks, in whole seconds. */
    @Override
    List<String> lockTimeout(long millis) {
        return List.of("ALTER SESSION SET DDL_LOCK_TIMEOUT = " + Math.min(1_000_000, Math.max(1, millis / 1000)));
    }

    /** ORA-00054 (resource busy) and ORA-04021 (timeout waiting to lock an object). */
    @Override
    boolean lockTimedOut(SQLException e) {
        return e.getErrorCode() == 54 || e.getErrorCode() == 4021;
    }

    /** Grants others have on the table, as the login can see them (all of them when it owns the table). */
    @Override
    Sql grants(TableName t) {
        return new Sql("SELECT PRIVILEGE, GRANTEE, GRANTABLE FROM ALL_TAB_PRIVS WHERE TABLE_SCHEMA = ? "
            + "AND TABLE_NAME = ? AND GRANTEE <> ? AND TYPE = 'TABLE'", List.of(t.schema(), t.table(), t.schema()));
    }

    @Override
    String grant(TableName to, java.sql.ResultSet row) throws SQLException {
        String privilege = row.getString(1);
        String grantee = row.getString(2);
        if (privilege == null || grantee == null || !PRIVILEGES.contains(privilege)) {
            return null;
        }
        return "GRANT " + privilege + " ON " + table(to) + " TO " + (grantee.equals("PUBLIC") ? "PUBLIC" : quote(grantee))
            + ("YES".equals(row.getString(3)) ? " WITH GRANT OPTION" : "");
    }

    /**
     * One block: the old table renamed away, the new one into place, the old one dropped. If a
     * rename or the drop fails (a foreign key from a table the login cannot see refers to the old
     * table, say), the names are put back as they were and the error raised, so the target is
     * never left without its table or with a table nothing can drop.
     */
    @Override
    List<Sql> swap(TableName target, String newName, String oldName) {
        TableName fresh = new TableName(target.schema(), newName);
        TableName old = new TableName(target.schema(), oldName);
        String toOld = run(rename(target, oldName).text());
        String toTarget = run(rename(fresh, target.table()).text());
        String oldBack = run(rename(old, target.table()).text());
        String newBack = run(rename(target, newName).text());
        String block = "BEGIN " + toOld
            + " BEGIN " + toTarget + " EXCEPTION WHEN OTHERS THEN " + oldBack + " RAISE; END;"
            + " BEGIN " + run(drop(old)) + " EXCEPTION WHEN OTHERS THEN " + newBack + " " + oldBack + " RAISE; END;"
            + " END;";
        return List.of(new Sql(block));
    }

    private String run(String statement) {
        return "EXECUTE IMMEDIATE " + dialect().literal(statement) + ";";
    }

    /** Dropped for good, not kept in the recycle bin under a BIN$ name. */
    @Override
    String drop(TableName t) {
        return "DROP TABLE " + table(t) + " PURGE";
    }

    @Override
    Sql rename(TableName from, String name) {
        return new Sql("ALTER TABLE " + table(from) + " RENAME TO " + quote(name));
    }

    @Override
    List<Check> privileges(String mode, TableName t, boolean exists) {
        List<Check> out = new ArrayList<>();
        String schema = t.schema();
        switch (mode) {
            case "create", "replace" -> {
                out.add(new Check(new Sql("SELECT CASE WHEN EXISTS (SELECT 1 FROM ALL_USERS WHERE USERNAME = ?) THEN 1 "
                    + "END FROM DUAL", List.of(schema)), "the schema " + schema + " does not exist, or the login cannot "
                    + "see it: ask a database administrator to create it, or write to another schema"));
                // in its own schema the login needs CREATE TABLE; in another, the ANY privileges that
                // creating, filling, renaming and dropping a table there take
                out.add(new Check(new Sql("SELECT CASE WHEN (? = USER AND EXISTS (SELECT 1 FROM SESSION_PRIVS "
                    + "WHERE PRIVILEGE = 'CREATE TABLE')) OR (SELECT COUNT(*) FROM SESSION_PRIVS WHERE PRIVILEGE IN "
                    + "('CREATE ANY TABLE', 'INSERT ANY TABLE', 'ALTER ANY TABLE', 'DROP ANY TABLE')) = 4 THEN 1 ELSE 0 "
                    + "END FROM DUAL", List.of(schema)), null));
            }
            case "append" -> out.add(tablePrivilege(t, "INSERT"));
            case "upsert" -> {
                out.add(tablePrivilege(t, "INSERT"));
                out.add(tablePrivilege(t, "UPDATE"));
                out.add(tablePrivilege(t, "SELECT"));
            }
            default -> { }
        }
        return out;
    }

    /**
     * The login's own table, a grant to it or to PUBLIC (on the table or a column of it), a grant
     * to an enabled role, or the ANY privilege.
     */
    private static Check tablePrivilege(TableName t, String privilege) {
        String p = "'" + privilege + "'";
        return new Check(new Sql("SELECT CASE WHEN ? = USER "
            + "OR EXISTS (SELECT 1 FROM SESSION_PRIVS WHERE PRIVILEGE = '" + privilege + " ANY TABLE') "
            + "OR EXISTS (SELECT 1 FROM ALL_TAB_PRIVS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND PRIVILEGE = " + p
            + " AND GRANTEE IN (USER, 'PUBLIC')) "
            + "OR EXISTS (SELECT 1 FROM ALL_COL_PRIVS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND PRIVILEGE = " + p
            + " AND GRANTEE IN (USER, 'PUBLIC')) "
            + "OR EXISTS (SELECT 1 FROM ROLE_TAB_PRIVS WHERE OWNER = ? AND TABLE_NAME = ? AND PRIVILEGE = " + p + ") "
            + "THEN 1 ELSE 0 END FROM DUAL",
            List.of(t.schema(), t.schema(), t.table(), t.schema(), t.table(), t.schema(), t.table())), null);
    }

    @Override
    String needs(String mode, TableName t, boolean exists) {
        return switch (mode) {
            case "create", "replace" -> "CREATE TABLE, if " + t.schema() + " is the login's own schema; to write to "
                + "another schema, CREATE ANY TABLE, INSERT ANY TABLE, ALTER ANY TABLE and DROP ANY TABLE. Also a quota "
                + "on the schema's tablespace";
            case "upsert" -> "INSERT, UPDATE and SELECT on " + t;
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
            case BOOLEAN, INT16, INT32, INT64, DECIMAL -> Types.NUMERIC;
            case FLOAT32 -> Types.REAL;
            case FLOAT64 -> Types.DOUBLE;
            case TEXT, TIME -> Types.VARCHAR;
            case BINARY -> Types.VARBINARY;
            case DATE -> Types.DATE;
            case TIMESTAMP -> Types.TIMESTAMP;
            case TIMESTAMP_TZ -> Types.TIMESTAMP_WITH_TIMEZONE;
        };
    }

    /** CHAR-length columns count characters. */
    @Override
    int length(String s) {
        return s.codePointCount(0, s.length());
    }

    /**
     * Text of up to 4,000 bytes as a string; longer text, which only a CLOB holds, as a CLOB, so it
     * also reaches one through MERGE's {@code SELECT ? … FROM DUAL}.
     */
    @Override
    void bindText(PreparedStatement ps, int i, String s) throws SQLException {
        if (s.length() > 1000 && s.getBytes(StandardCharsets.UTF_8).length > 4000) {
            ps.setClob(i, new java.io.StringReader(s), s.length());
        } else {
            ps.setString(i, s);
        }
    }

    /** Text for an NVARCHAR2, NCHAR or NCLOB column as national character data; anything else as {@link #bind}. */
    @Override
    void bind(PreparedStatement ps, int i, Kind kind, Object v, boolean bulk, WritePlan.TargetColumn into)
        throws SQLException {
        if (into != null && national(into.typeName()) && v instanceof String s) {
            // past 4,000 bytes of national characters only an NCLOB holds it
            if (s.length() > 2000) {
                ps.setNClob(i, new java.io.StringReader(s), s.length());
            } else {
                ps.setNString(i, s);
            }
            return;
        }
        bind(ps, i, kind, v, bulk);
    }

    /**
     * True or false as 1 or 0 (into NUMBER(1), and into BOOLEAN, which takes numbers); a time of day
     * as text ({@code 09:30:00.000000}), as the node's own columns keep it; a timestamp with a zone
     * in UTC.
     */
    @Override
    void bind(PreparedStatement ps, int i, Kind kind, Object v) throws SQLException {
        if (v == null) {
            ps.setNull(i, nullType(kind));
            return;
        }
        switch (kind) {
            case BOOLEAN -> ps.setInt(i, (Boolean) v ? 1 : 0);
            case TIME -> ps.setString(i, TextForm.time((LocalTime) v));
            case DATE -> ps.setObject(i, (LocalDate) v);
            case TIMESTAMP -> ps.setObject(i, (LocalDateTime) v);
            case TIMESTAMP_TZ -> ps.setObject(i, OffsetDateTime.ofInstant((Instant) v, ZoneOffset.UTC));
            default -> super.bind(ps, i, kind, v);
        }
    }

    /** Oracle stores empty text and empty bytes as null. */
    @Override
    boolean emptyMeansNull() {
        return true;
    }

    /**
     * Empty text or bytes (Oracle makes them null) unless allowed; a decimal with more significant
     * digits than Oracle keeps (it would round it), or outside a NUMBER's range.
     */
    @Override
    String valueProblem(Kind kind, Object v) {
        if (v instanceof String s && s.isEmpty() && !emptyAsNull) {
            return "it is empty text, which Oracle stores as null. Set empty_text_as_null: true to write it as "
                + "null, or turn it into another value with a compute node";
        }
        if (v instanceof byte[] b && b.length == 0 && !emptyAsNull) {
            return "it is empty (no bytes), which Oracle stores as null. Set empty_text_as_null: true to write it as "
                + "null, or turn it into another value with a compute node";
        }
        if (v instanceof BigDecimal d && d.signum() != 0) {
            BigDecimal exact = d.stripTrailingZeros();
            if (exact.precision() > MAX_DIGITS) {
                return "the value " + d.toPlainString() + " has more than " + MAX_DIGITS + " significant digits, which "
                    + "Oracle would round, and it is never rounded";
            }
            int exponent = exact.precision() - exact.scale() - 1;
            if (exponent < MIN_EXPONENT || exponent > MAX_EXPONENT) {
                return "the value " + exact.toString() + " is outside what an Oracle NUMBER holds (from 1E-130 up to, "
                    + "not including, 1E126 either side of zero)";
            }
        }
        return null;
    }

    /**
     * Per column of the table written to ({@code t} null for a column the node creates, which is
     * VARCHAR2 or CLOB in the database's character set): text must be storable in the column's
     * character set, and within a column's size in bytes; a FLOAT(b) column keeps
     * ceil(b × 0.30103) significant digits, so a value with more is refused rather than rounded.
     */
    @Override
    String targetValueProblem(WritePlan.TargetColumn t, Object v) {
        if (v instanceof String s && (t == null || characterColumn(t.typeName()))) {
            boolean toNational = t != null && national(t.typeName());
            OracleCharset cs = toNational ? nationalCharset : charset;
            String unstorable = cs.unstorable(s);
            if (unstorable != null) {
                return unstorable + (!toNational && nationalCharset.unicode() ? ". Write it to an NVARCHAR2 or NCLOB "
                    + "column, whose character set (" + nationalCharset.name() + ") holds it, or replace the character "
                    + "with a compute node" : ". Replace the character with a compute node");
            }
            if (t != null && t.precision() > 0 && !t.typeName().endsWith("CLOB") && !t.typeName().equals("LONG")) {
                long bytes = cs.bytes(s);
                if (bytes > t.precision()) {
                    return "the value takes " + bytes + " bytes in the character set " + cs.name() + ", but the column "
                        + "holds at most " + t.precision() + " bytes. Shorten the values with a compute node, or ask for "
                        + "the column to be widened";
                }
            }
        }
        if (v instanceof BigDecimal d && t != null && t.typeName().equals("FLOAT") && t.scale() > 0
            && d.signum() != 0 && d.stripTrailingZeros().precision() > t.scale()) {
            return "the value " + d.toPlainString() + " has " + d.stripTrailingZeros().precision() + " significant "
                + "digits, but the FLOAT column keeps " + t.scale() + ", so Oracle would round it, and it is never "
                + "rounded";
        }
        return null;
    }

    /** DATE keeps whole seconds; TIMESTAMP(n) keeps n digits. */
    @Override
    int fractionDigits(WritePlan.TargetColumn column) {
        return column.kind() == Kind.TIMESTAMP || column.kind() == Kind.TIMESTAMP_TZ ? column.scale() : -1;
    }

    @Override
    long maxTextLength() {
        return 4000;
    }

    @Override
    boolean transactionalDdl() {
        return false;
    }
}

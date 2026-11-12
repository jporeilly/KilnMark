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
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import io.kilnmark.kiln.plugins.jdbc.JdbcColumn.Kind;
import io.kilnmark.kiln.plugins.write.TextForm;
import io.kilnmark.kiln.plugins.write.WriteColumn;

/**
 * The SQL a database writer runs, for PostgreSQL and SQL Server here and MySQL and MariaDB in
 * {@link MySqlWriteSql}. Every identifier goes through
 * {@link SqlDialect#quote}; values are never written into SQL: they are bound as parameters, or
 * (PostgreSQL's {@code COPY}) streamed as CSV data. The catalogue queries take the table's quoted
 * name as a parameter.
 */
abstract class WriteSql {

    /** A statement and its parameters, all text. */
    record Sql(String text, List<String> params) {

        Sql(String text) {
            this(text, List.of());
        }
    }

    /**
     * A check run before writing: a query giving one true/false value.
     *
     * @param missing what to say when it gives no row or null (the schema or table is not there);
     *                null to skip the check then
     */
    record Check(Sql query, String missing) {
    }

    abstract SqlDialect dialect();

    final String quote(String identifier) {
        return dialect().quote(identifier);
    }

    final String table(TableName t) {
        return dialect().table(t.schema(), t.table());
    }

    /** The quoted name as a parameter for the catalogue functions ({@code to_regclass}, {@code OBJECT_ID}). */
    final String regName(TableName t) {
        return table(t);
    }

    /** Problems with a name used as an identifier: too long for the database. */
    abstract String identifierProblem(String name);

    /** Whether two column names are the same column (SQL Server's usual collations ignore case). */
    abstract boolean sameName(String a, String b);

    /**
     * The name a column created from the input gets: the input's name as it is by default; Oracle
     * writes a plain lower-case name in upper case, as an unquoted name would be.
     */
    String storedName(String name) {
        return name;
    }

    /**
     * For a timestamp or time column of an existing table, how many digits of a second it keeps, so
     * a value with more is refused rather than rounded or cut; -1 (the default) when the database
     * refuses such values itself or keeps microseconds.
     */
    int fractionDigits(WritePlan.TargetColumn column) {
        return -1;
    }

    /**
     * Why the input column {@code c} cannot go into {@code t} although {@link WritePlan#misfit}
     * accepts it (a database type that reads as text but parses values its own way), or null.
     */
    String targetMisfit(WriteColumn c, WritePlan.TargetColumn t) {
        return null;
    }

    /**
     * Why the converted value {@code v} cannot go into {@code t}, or null (a YEAR column's range, a
     * character the column's character set lacks); {@code t} is null for a column of a table the
     * node creates.
     */
    String targetValueProblem(WritePlan.TargetColumn t, Object v) {
        return null;
    }

    /**
     * Whether the database stores empty text (and empty bytes) as null (Oracle), so an empty key
     * column could never match a row and upsert refuses it whatever else the node allows.
     */
    boolean emptyMeansNull() {
        return false;
    }

    /** The column type a table created from the input gets. */
    abstract String columnType(WriteColumn c, int textLength);

    /** {@code CREATE TABLE} with every input column, all allowing nulls. */
    String createTable(TableName t, List<WriteColumn> columns, int textLength) {
        return "CREATE TABLE " + table(t) + " (" + columns.stream()
            .map(c -> quote(storedName(c.name())) + " " + columnType(c, textLength))
            .collect(Collectors.joining(", ")) + ")";
    }

    final String insert(TableName t, List<String> columns) {
        return "INSERT INTO " + table(t) + " (" + columnList(columns) + ") VALUES ("
            + columns.stream().map(c -> "?").collect(Collectors.joining(", ")) + ")";
    }

    /** Inserts a row, or updates the row with the same key. */
    abstract String upsert(TableName t, List<String> columns, List<String> key);

    final String columnList(List<String> columns) {
        return columns.stream().map(this::quote).collect(Collectors.joining(", "));
    }

    /** A query with the table's columns and no rows, for their types. */
    final String probe(TableName t) {
        return "SELECT * FROM " + table(t) + " WHERE 1 = 0";
    }

    String drop(TableName t) {
        return "DROP TABLE " + table(t);
    }

    /** Whether the table (or view) exists: one row, true or false. */
    abstract Sql exists(TableName t);

    /**
     * The table's schema and name as the catalogue spells them, one row when it exists; null where
     * names are compared exactly, so the spelling written is the catalogue's (PostgreSQL, whose
     * quoted names keep their case).
     */
    Sql realName(TableName t) {
        return null;
    }

    /** The names of the table's columns that need a value: NOT NULL, no default, not generated. */
    abstract Sql requiredColumns(TableName t);

    /** Unique indexes usable to detect a conflict, as rows (index, column); null if not needed. */
    Sql uniqueKeys(TableName t) {
        return null;
    }

    /**
     * Where an upsert's insert turns into an update on a conflict with any unique index, not only
     * the key's (MySQL's {@code ON DUPLICATE KEY UPDATE}): every unique index of the table, as rows
     * (index, column), the column null for a part that is a prefix or an expression. Null (the
     * default) where only the key's index decides.
     */
    Sql allUniqueKeys(TableName t) {
        return null;
    }

    /**
     * Foreign keys on the table that {@link #blockers} may not see (MySQL lists only those on
     * tables the login has rights on), one reason a row; null when there is no other place to look.
     * A failure to read it (a missing privilege) is ignored.
     */
    Sql hiddenBlockers(TableName t) {
        return null;
    }

    /** Whether dropping the old table failed because a foreign key still refers to it. */
    boolean foreignKeyRefused(SQLException e) {
        return false;
    }

    /**
     * Where DDL commits by itself: the statement putting the old table back after {@link #swap}
     * renamed it, the new table taking {@code newName} again; null where the swap is one transaction.
     */
    Sql swapBack(TableName target, String newName, String oldName) {
        return null;
    }

    /**
     * Why replace cannot swap the table, one reason a row as a clause ("the view x depends on it",
     * "it is a partition of y"): it is not a plain table, it is part of a partition or inheritance
     * tree, or other objects depend on it that dropping the old table would break.
     */
    abstract Sql blockers(TableName t);

    /** Whether the table has row-level security (PostgreSQL) or a security policy (SQL Server): one row, true or false. */
    abstract Sql rowSecurity(TableName t);

    /**
     * Whether rows reach the table only through statements that apply row-level security, so a
     * bulk load ({@code COPY}) would be refused: one row, true or false. False by default.
     */
    Sql copyRefused(TableName t) {
        return null;
    }

    /**
     * The old table's owner when it is not the login: one row (owner name, whether the login may
     * hand the new table to it); no row when the login owns it or ownership needs no repeating.
     */
    abstract Sql owner(TableName t);

    /** Gives {@code t} to {@code owner}. */
    abstract String setOwner(TableName t, String owner);

    /** Repeats what an owner may do on {@code t}, when ownership itself cannot be given. */
    abstract String grantOwnerRights(TableName t, String owner);

    /** Statements bounding how long the swap waits for locks, in milliseconds. */
    abstract List<String> lockTimeout(long millis);

    /** Whether a failure is a lock wait running out. */
    abstract boolean lockTimedOut(SQLException e);

    /** The table's grants, as rows a {@link #grant} turns into statements. */
    abstract Sql grants(TableName t);

    /** The statement repeating a grant row from {@link #grants} on {@code to}; null to skip it. */
    abstract String grant(TableName to, java.sql.ResultSet row) throws SQLException;

    /** Swaps the new table in: the old one is renamed out of the way, the new one takes its name, the old one goes. */
    List<Sql> swap(TableName target, String newName, String oldName) {
        return List.of(rename(target, oldName), rename(new TableName(target.schema(), newName), target.table()),
            new Sql(drop(new TableName(target.schema(), oldName))));
    }

    /** Renames {@code from} to {@code name}, in the same schema. */
    abstract Sql rename(TableName from, String name);

    /** The checks that the login may write as {@code mode} asks, before anything is written. */
    abstract List<Check> privileges(String mode, TableName t, boolean exists);

    /** What a login needs for {@code mode}, for messages. */
    abstract String needs(String mode, TableName t, boolean exists);

    /** Whether a failure is the database refusing permission. */
    abstract boolean permissionDenied(SQLException e);

    /** Whether a failure is a row-level security policy refusing a row, rather than a missing grant. */
    boolean policyRefused(SQLException e) {
        return false;
    }

    /** The JDBC type for a null of {@code kind}. */
    abstract int nullType(Kind kind);

    /** How many characters of a column's length a value takes. */
    abstract int length(String s);

    /**
     * Binds a value of {@code kind} (null allowed) as parameter {@code i}; {@code bulk} when the
     * batch goes through the driver's bulk copy into a table this node created.
     */
    void bind(PreparedStatement ps, int i, Kind kind, Object v, boolean bulk) throws SQLException {
        bind(ps, i, kind, v);
    }

    /**
     * As {@link #bind(PreparedStatement, int, Kind, Object, boolean)}, knowing the table's column it
     * goes to ({@code into}; null for a table the node creates): Oracle binds national character
     * columns' text as such.
     */
    void bind(PreparedStatement ps, int i, Kind kind, Object v, boolean bulk, WritePlan.TargetColumn into)
        throws SQLException {
        bind(ps, i, kind, v, bulk);
    }

    /** Binds a value of {@code kind} (null allowed) as parameter {@code i}. */
    void bind(PreparedStatement ps, int i, Kind kind, Object v) throws SQLException {
        if (v == null) {
            ps.setNull(i, nullType(kind));
            return;
        }
        switch (kind) {
            case BOOLEAN -> ps.setBoolean(i, (Boolean) v);
            case INT16 -> ps.setShort(i, ((Long) v).shortValue());
            case INT32 -> ps.setInt(i, ((Long) v).intValue());
            case INT64 -> ps.setLong(i, (Long) v);
            case FLOAT32 -> ps.setFloat(i, (Float) v);
            case FLOAT64 -> ps.setDouble(i, (Double) v);
            case DECIMAL -> ps.setBigDecimal(i, (BigDecimal) v);
            case TEXT -> bindText(ps, i, (String) v);
            case BINARY -> ps.setBytes(i, (byte[]) v);
            case DATE -> ps.setObject(i, (LocalDate) v);
            case TIME -> ps.setObject(i, (LocalTime) v);
            case TIMESTAMP -> ps.setObject(i, (LocalDateTime) v);
            case TIMESTAMP_TZ -> ps.setObject(i, OffsetDateTime.ofInstant((Instant) v, ZoneOffset.UTC));
        }
    }

    abstract void bindText(PreparedStatement ps, int i, String s) throws SQLException;

    /** Checks a text value can be sent: PostgreSQL text cannot hold a zero character. */
    String textProblem(String s) {
        return null;
    }

    /** Why a value of {@code kind} cannot be sent, or null: by default only {@link #textProblem} for text. */
    String valueProblem(Kind kind, Object v) {
        return v instanceof String s ? textProblem(s) : null;
    }

    /** Rows sent at a time when the node does not say ({@code batch_size}). */
    int defaultBatchSize(String mode) {
        return 1000;
    }

    /** The longest {@code text_length} a create may ask for. */
    long maxTextLength() {
        return 10_485_760;
    }

    /** Whether rows go through the driver's or database's bulk path for this mode and these settings. */
    boolean bulk(String mode, io.kilnmark.kiln.plugins.write.WriteSettings settings)
        throws io.kilnmark.kiln.connect.ConnectorException {
        return false;
    }

    /**
     * Whether CREATE, RENAME and DROP take part in the write's transaction (PostgreSQL, SQL
     * Server). Where they commit by themselves (MySQL), a created table is filled under a
     * temporary name and dropped again after a failure.
     */
    boolean transactionalDdl() {
        return true;
    }

    /**
     * Whether the table exists, read from the driver's {@link java.sql.DatabaseMetaData} rather than
     * by {@link #exists}; null (the default) to run {@link #exists}. For databases Kilnmark knows only
     * through the driver (generic JDBC).
     */
    Boolean existsByMetadata(java.sql.Connection connection, TableName t) throws SQLException {
        return null;
    }

    /** As {@link #requiredColumns}, from the driver's metadata; null (the default) to run {@link #requiredColumns}. */
    Set<String> requiredByMetadata(java.sql.Connection connection, TableName t) throws SQLException {
        return null;
    }

    /**
     * The table's columns from the driver's metadata (sizes and decimal digits as
     * {@link java.sql.DatabaseMetaData#getColumns} reports them), or null (the default) to read them
     * from a prepared statement's description.
     */
    List<WritePlan.TargetColumn> targetColumnsByMetadata(java.sql.Connection connection, TableName t)
        throws SQLException {
        return null;
    }

    /**
     * Why a table cannot be created with these columns (a kind the database has no type for), or
     * null (the default) when it can.
     */
    String createProblem(List<WriteColumn> columns, int textLength) {
        return null;
    }

    /**
     * Whether a table this node creates is described again once created, so each value is checked
     * against the columns the database actually made (lengths, digits of a second, decimal places)
     * as for an append: where the column types are chosen from what the driver offers rather than
     * known in advance. False by default.
     */
    boolean checksCreatedTable() {
        return false;
    }

    /**
     * Where a created table may have been committed by the database on its own (DDL that commits),
     * though {@link #transactionalDdl()} is true: whether a failed create drops it again. False by
     * default.
     */
    boolean dropCreatedOnFailure() {
        return false;
    }

    /** Sets up a write's session before anything else runs (MySQL's strict mode); nothing by default. */
    void prepareSession(java.sql.Connection connection) throws SQLException {
        // nothing to set
    }

    /** Why upsert by {@code key} is refused when no unique index matches, with what to ask for. */
    String keyIndexAdvice(TableName t, List<String> key) {
        return "PostgreSQL finds the existing row through a unique index or primary key on exactly those columns, "
            + "and the table has none. Ask a database administrator to add one, for example CREATE UNIQUE INDEX ON "
            + table(t) + " (" + columnList(key) + ")";
    }

    static String hexName() {
        return Long.toHexString(new java.security.SecureRandom().nextLong() >>> 1);
    }

    // ------------------------------------------------------------------ PostgreSQL

    /** PostgreSQL: transactional DDL, {@code COPY}, {@code INSERT … ON CONFLICT}. */
    static final class Postgres extends WriteSql {

        // Here rather than in WriteSql: a superclass that makes its subclasses can deadlock two threads
        // loading both at once.
        static final Postgres INSTANCE = new Postgres();

        private static final Set<String> PRIVILEGES = Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE",
            "REFERENCES", "TRIGGER", "MAINTAIN");
        private static final HexFormat HEX = HexFormat.of();

        @Override
        SqlDialect dialect() {
            return SqlDialect.POSTGRESQL;
        }

        /**
         * {@code timestamp(p)}, {@code timestamptz(p)} and {@code time(p)} with p below 6 round what
         * they cannot keep; the driver reports p as the scale (6 when not declared).
         */
        @Override
        int fractionDigits(WritePlan.TargetColumn column) {
            return switch (column.kind()) {
                case TIME, TIMESTAMP, TIMESTAMP_TZ -> column.scale() >= 0 && column.scale() < 6 ? column.scale() : -1;
                default -> -1;
            };
        }

        @Override
        String identifierProblem(String name) {
            int bytes = name.getBytes(StandardCharsets.UTF_8).length;
            return bytes > 63 ? "the name " + name + " is " + bytes + " bytes long; PostgreSQL keeps only 63, so "
                + "shorten it" : name.indexOf('\0') >= 0 ? "a name holds a zero character" : null;
        }

        @Override
        boolean sameName(String a, String b) {
            return a.equals(b);
        }

        @Override
        String columnType(WriteColumn c, int textLength) {
            return switch (c.kind()) {
                case BOOLEAN -> "boolean";
                case INT16 -> "smallint";
                case INT32 -> "integer";
                case INT64 -> "bigint";
                case FLOAT32 -> "real";
                case FLOAT64 -> "double precision";
                case DECIMAL -> "numeric(" + c.precision() + "," + c.scale() + ")";
                case TEXT -> textLength > 0 ? "varchar(" + textLength + ")" : "text";
                case BINARY -> "bytea";
                case DATE -> "date";
                case TIME -> "time";
                case TIMESTAMP -> "timestamp";
                case TIMESTAMP_TZ -> "timestamptz";
            };
        }

        @Override
        String upsert(TableName t, List<String> columns, List<String> key) {
            List<String> rest = columns.stream().filter(c -> !key.contains(c)).toList();
            return insert(t, columns) + " ON CONFLICT (" + columnList(key) + ") DO "
                + (rest.isEmpty() ? "NOTHING" : "UPDATE SET " + rest.stream()
                .map(c -> quote(c) + " = EXCLUDED." + quote(c)).collect(Collectors.joining(", ")));
        }

        /** {@code COPY} into the table's columns, as CSV: see {@link #copyValue}. */
        String copy(TableName t, List<String> columns) {
            return "COPY " + table(t) + " (" + columnList(columns) + ") FROM STDIN WITH (FORMAT csv)";
        }

        /**
         * Appends {@code v} (of {@code kind}) as one CSV field for {@code COPY … (FORMAT csv)}: a null is
         * an empty unquoted field, text is always quoted (so an empty string stays an empty string),
         * dates before year 1 end in {@code BC}, timestamps in UTC end in {@code +00}.
         *
         * @throws IllegalArgumentException for text holding a zero character
         */
        static void copyValue(StringBuilder b, Kind kind, Object v) {
            if (v == null) {
                return;
            }
            switch (kind) {
                case BOOLEAN -> b.append((Boolean) v ? 't' : 'f');
                case TEXT -> {
                    String s = (String) v;
                    if (s.indexOf('\0') >= 0) {
                        throw new IllegalArgumentException("text holding a zero character, which PostgreSQL cannot store");
                    }
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
                case BINARY -> b.append("\\x").append(HEX.formatHex((byte[]) v));
                case DATE -> {
                    LocalDate d = (LocalDate) v;
                    b.append(date(d)).append(d.getYear() <= 0 ? " BC" : "");
                }
                case TIME -> b.append(TextForm.time((LocalTime) v));
                case TIMESTAMP -> {
                    LocalDateTime t = (LocalDateTime) v;
                    b.append(date(t.toLocalDate())).append(' ').append(TextForm.time(t.toLocalTime()))
                        .append(t.getYear() <= 0 ? " BC" : "");
                }
                case TIMESTAMP_TZ -> {
                    LocalDateTime t = LocalDateTime.ofInstant((Instant) v, ZoneOffset.UTC);
                    b.append(date(t.toLocalDate())).append(' ').append(TextForm.time(t.toLocalTime())).append("+00")
                        .append(t.getYear() <= 0 ? " BC" : "");
                }
                case DECIMAL -> b.append(((BigDecimal) v).toPlainString());
                default -> b.append(v); // integers and floats; NaN and Infinity as PostgreSQL reads them
            }
        }

        /** {@code 2025-01-31}; year 0 and before as PostgreSQL counts them (0 is 1 BC), without the BC. */
        private static String date(LocalDate d) {
            int year = d.getYear() <= 0 ? 1 - d.getYear() : d.getYear();
            return String.format(Locale.ROOT, "%04d-%02d-%02d", year, d.getMonthValue(), d.getDayOfMonth());
        }

        @Override
        Sql exists(TableName t) {
            return new Sql("SELECT pg_catalog.to_regclass(?) IS NOT NULL", List.of(regName(t)));
        }

        @Override
        Sql requiredColumns(TableName t) {
            return new Sql("SELECT a.attname FROM pg_attribute a WHERE a.attrelid = pg_catalog.to_regclass(?) AND a.attnum > 0 "
                + "AND NOT a.attisdropped AND a.attnotnull AND NOT a.atthasdef AND a.attidentity = '' "
                + "AND a.attgenerated = ''", List.of(regName(t)));
        }

        /**
         * Unique, immediate, whole-table indexes on plain columns: those ON CONFLICT can use, by
         * their key columns only (columns an index INCLUDEs are not part of its key).
         */
        @Override
        Sql uniqueKeys(TableName t) {
            return new Sql("SELECT i.indexrelid, a.attname FROM pg_index i JOIN pg_attribute a ON a.attrelid = "
                + "i.indrelid AND a.attnum = ANY ((i.indkey::int2[])[0:i.indnkeyatts - 1]) WHERE i.indrelid = "
                + "pg_catalog.to_regclass(?) AND i.indisunique AND i.indimmediate AND i.indpred IS NULL AND i.indexprs IS NULL",
                List.of(regName(t)));
        }

        /**
         * Not a plain table; a partition, an inheritance parent or child; a table a publication
         * replicates by name (publications FOR ALL TABLES or FOR TABLES IN SCHEMA take in the new
         * table by themselves); or any object depending in the normal way on the table, its row
         * type, the array of its row type, or a sequence it owns, that is not part of the table
         * itself and so would not go with it (views, foreign keys from other tables, functions with
         * SQL bodies or taking the row type, columns of its type, other tables' defaults using its
         * sequences).
         */
        @Override
        Sql blockers(TableName t) {
            return new Sql("WITH t AS (SELECT c.oid, c.relkind, c.relispartition, c.reltype, ty.typarray "
                + "FROM pg_class c LEFT JOIN pg_type ty ON ty.oid = c.reltype WHERE c.oid = pg_catalog.to_regclass(?)), "
                + "refs AS (SELECT 'pg_class'::pg_catalog.regclass AS cls, t.oid AS obj FROM t "
                + "UNION ALL SELECT 'pg_type'::pg_catalog.regclass, t.reltype FROM t WHERE t.reltype <> 0 "
                + "UNION ALL SELECT 'pg_type'::pg_catalog.regclass, t.typarray FROM t WHERE t.typarray <> 0 "
                + "UNION ALL SELECT 'pg_class'::pg_catalog.regclass, d.objid FROM pg_depend d JOIN t ON d.refobjid = t.oid "
                + "JOIN pg_class s ON s.oid = d.objid WHERE d.classid = 'pg_class'::pg_catalog.regclass "
                + "AND d.refclassid = 'pg_class'::pg_catalog.regclass AND d.deptype IN ('a', 'i') AND s.relkind = 'S') "
                + "SELECT 'it is ' || CASE t.relkind WHEN 'v' THEN 'a view' WHEN 'm' THEN 'a materialised view' "
                + "WHEN 'p' THEN 'a partitioned table' WHEN 'f' THEN 'a foreign table' WHEN 'S' THEN 'a sequence' "
                + "WHEN 'i' THEN 'an index' WHEN 'I' THEN 'a partitioned index' WHEN 'c' THEN 'a composite type' "
                + "ELSE 'another kind of object' END || ', not a table' FROM t WHERE t.relkind <> 'r' "
                + "UNION SELECT CASE WHEN t.relispartition THEN 'it is a partition of ' ELSE 'it inherits from ' END "
                + "|| i.inhparent::pg_catalog.regclass::text FROM pg_inherits i JOIN t ON i.inhrelid = t.oid "
                + "UNION SELECT 'the table ' || i.inhrelid::pg_catalog.regclass::text || ' inherits from it' "
                + "FROM pg_inherits i JOIN t ON i.inhparent = t.oid "
                + "UNION SELECT 'the publication ' || p.pubname || ' replicates it' FROM pg_publication_rel r "
                + "JOIN pg_publication p ON p.oid = r.prpubid JOIN t ON r.prrelid = t.oid "
                + "UNION SELECT 'the ' || CASE WHEN d.classid = 'pg_rewrite'::pg_catalog.regclass THEN 'view ' "
                + "|| (SELECT w.ev_class::pg_catalog.regclass::text FROM pg_rewrite w WHERE w.oid = d.objid) "
                + "ELSE pg_describe_object(d.classid, d.objid, 0) END || ' depends on it' "
                + "FROM pg_depend d JOIN refs ON d.refclassid = refs.cls AND d.refobjid = refs.obj, t "
                + "WHERE d.deptype = 'n' "
                + "AND NOT EXISTS (SELECT 1 FROM pg_depend o WHERE o.classid = d.classid AND o.objid = d.objid "
                + "AND o.refclassid = 'pg_class'::pg_catalog.regclass AND o.refobjid = t.oid AND o.deptype IN ('a', 'i')) "
                + "AND NOT (d.classid = 'pg_rewrite'::pg_catalog.regclass AND EXISTS (SELECT 1 FROM pg_rewrite w "
                + "WHERE w.oid = d.objid AND w.ev_class = t.oid)) "
                + "AND NOT (d.classid = 'pg_class'::pg_catalog.regclass AND d.objid = t.oid) "
                + "AND NOT (d.classid = 'pg_class'::pg_catalog.regclass AND EXISTS (SELECT 1 FROM pg_inherits i "
                + "WHERE i.inhrelid = d.objid AND i.inhparent = t.oid))", List.of(regName(t)));
        }

        @Override
        Sql rowSecurity(TableName t) {
            return new Sql("SELECT c.relrowsecurity FROM pg_class c WHERE c.oid = pg_catalog.to_regclass(?)", List.of(regName(t)));
        }

        /** COPY FROM is refused on a table whose row-level security applies to the login. */
        @Override
        Sql copyRefused(TableName t) {
            return new Sql("SELECT c.relrowsecurity AND NOT (r.rolsuper OR r.rolbypassrls OR "
                + "(pg_catalog.pg_has_role(c.relowner, 'USAGE') AND NOT c.relforcerowsecurity)) FROM pg_class c, pg_roles r "
                + "WHERE c.oid = pg_catalog.to_regclass(?) AND r.rolname = current_user", List.of(regName(t)));
        }

        /**
         * May the login give the new table to the old owner: a superuser always; otherwise it must
         * be able to SET ROLE to the owner (PostgreSQL 16 and later; membership before) and the
         * owner must be allowed to create in the schema.
         */
        @Override
        Sql owner(TableName t) {
            return new Sql("SELECT o.rolname, me.rolsuper OR ((CASE WHEN pg_catalog.current_setting('server_version_num')::int "
                + ">= 160000 THEN pg_catalog.pg_has_role(c.relowner, 'SET') ELSE pg_catalog.pg_has_role(c.relowner, 'MEMBER') END) "
                + "AND pg_catalog.has_schema_privilege(c.relowner, c.relnamespace, 'CREATE')) FROM pg_class c "
                + "JOIN pg_roles o ON o.oid = c.relowner JOIN pg_roles me ON me.rolname = current_user "
                + "WHERE c.oid = pg_catalog.to_regclass(?) AND c.relowner <> me.oid", List.of(regName(t)));
        }

        @Override
        String setOwner(TableName t, String owner) {
            return "ALTER TABLE " + table(t) + " OWNER TO " + quote(owner);
        }

        @Override
        String grantOwnerRights(TableName t, String owner) {
            return "GRANT ALL PRIVILEGES ON " + table(t) + " TO " + quote(owner);
        }

        @Override
        List<String> lockTimeout(long millis) {
            return List.of("SET LOCAL lock_timeout = " + Math.max(1, millis));
        }

        @Override
        boolean lockTimedOut(SQLException e) {
            return "55P03".equals(e.getSQLState());
        }

        @Override
        Sql grants(TableName t) {
            return new Sql("SELECT a.grantee = 0, r.rolname, a.privilege_type, a.is_grantable FROM pg_class c "
                + "CROSS JOIN LATERAL aclexplode(c.relacl) a LEFT JOIN pg_roles r ON r.oid = a.grantee "
                + "WHERE c.oid = pg_catalog.to_regclass(?) AND a.grantee <> c.relowner", List.of(regName(t)));
        }

        @Override
        String grant(TableName to, java.sql.ResultSet row) throws SQLException {
            String privilege = row.getString(3);
            if (privilege == null || !PRIVILEGES.contains(privilege)) {
                return null;
            }
            String grantee = row.getBoolean(1) ? "PUBLIC" : row.getString(2) == null ? null : quote(row.getString(2));
            if (grantee == null) {
                return null; // a role dropped meanwhile
            }
            return "GRANT " + privilege + " ON " + table(to) + " TO " + grantee
                + (row.getBoolean(4) ? " WITH GRANT OPTION" : "");
        }

        @Override
        Sql rename(TableName from, String name) {
            return new Sql("ALTER TABLE " + table(from) + " RENAME TO " + quote(name));
        }

        @Override
        List<Check> privileges(String mode, TableName t, boolean exists) {
            List<Check> out = new ArrayList<>();
            switch (mode) {
                case "create", "replace" -> {
                    out.add(new Check(new Sql("SELECT pg_catalog.has_schema_privilege(n.oid, 'CREATE') FROM pg_namespace n "
                        + "WHERE n.nspname = ?", List.of(t.schema())), "the schema " + t.schema() + " does not exist: "
                        + "ask a database administrator to create it, or write to another schema"));
                    if (exists) {
                        out.add(new Check(new Sql("SELECT pg_catalog.pg_has_role(c.relowner, 'USAGE') FROM pg_class c "
                            + "WHERE c.oid = pg_catalog.to_regclass(?)", List.of(regName(t))), null));
                    }
                }
                case "append" -> out.add(columnPrivilege(t, "INSERT"));
                case "upsert" -> {
                    out.add(columnPrivilege(t, "INSERT"));
                    out.add(columnPrivilege(t, "UPDATE"));
                    out.add(columnPrivilege(t, "SELECT"));
                }
                default -> { }
            }
            return out;
        }

        /** On the table or on at least one of its columns: column grants are checked by the database as rows go in. */
        private Check columnPrivilege(TableName t, String privilege) {
            return new Check(new Sql("SELECT pg_catalog.has_any_column_privilege(pg_catalog.to_regclass(?), '" + privilege + "')",
                List.of(regName(t))), null);
        }

        @Override
        String needs(String mode, TableName t, boolean exists) {
            return switch (mode) {
                case "create" -> "CREATE on the schema " + t.schema();
                case "replace" -> "CREATE on the schema " + t.schema() + (exists ? ", and make it the owner of " + t
                    + " (or a member of the owning role), since replace drops the old table" : "");
                case "upsert" -> "INSERT, UPDATE and SELECT on " + t;
                default -> "INSERT on " + t;
            };
        }

        @Override
        boolean permissionDenied(SQLException e) {
            return "42501".equals(e.getSQLState());
        }

        /** 42501 too, but "new row violates row-level security policy": a policy, not a missing grant. */
        @Override
        boolean policyRefused(SQLException e) {
            return "42501".equals(e.getSQLState()) && e.getMessage() != null
                && e.getMessage().contains("row-level security");
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
                case DECIMAL -> Types.NUMERIC;
                case TEXT -> Types.OTHER; // the server decides: text, varchar, json, uuid ...
                case BINARY -> Types.BINARY;
                case DATE -> Types.DATE;
                case TIME -> Types.TIME;
                case TIMESTAMP -> Types.TIMESTAMP;
                case TIMESTAMP_TZ -> Types.TIMESTAMP_WITH_TIMEZONE;
            };
        }

        @Override
        int length(String s) {
            return s.codePointCount(0, s.length());
        }

        /** Sent untyped, so text reaches json, uuid and enum columns as well as text ones. */
        @Override
        void bindText(PreparedStatement ps, int i, String s) throws SQLException {
            ps.setObject(i, s, Types.OTHER);
        }

        @Override
        String textProblem(String s) {
            return s.indexOf('\0') >= 0 ? "it holds a zero character, which PostgreSQL text cannot store" : null;
        }
    }

    // ------------------------------------------------------------------ SQL Server

    /** SQL Server: DDL in transactions, {@code sp_rename}, {@code MERGE … WITH (HOLDLOCK)}. */
    static final class SqlServer extends WriteSql {

        static final SqlServer INSTANCE = new SqlServer();

        /** Inserts go 5,000 at a time; upserts (one MERGE each) 1,000. */
        @Override
        int defaultBatchSize(String mode) {
            return mode.equals("upsert") ? 1000 : 5000;
        }

        @Override
        long maxTextLength() {
            return 4000;
        }

        /** The driver's bulk copy, for tables the node creates, unless {@code bulk_copy} is false. */
        @Override
        boolean bulk(String mode, io.kilnmark.kiln.plugins.write.WriteSettings settings)
            throws io.kilnmark.kiln.connect.ConnectorException {
            return (mode.equals("create") || mode.equals("replace")) && settings.bool("bulk_copy", true);
        }

        private static final Set<String> PERMISSIONS = Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "REFERENCES",
            "ALTER", "CONTROL", "TAKE OWNERSHIP", "VIEW DEFINITION", "VIEW CHANGE TRACKING");

        /** Permission errors: denied on an object or column, CREATE TABLE denied, and "does not exist or no permission". */
        private static final Set<Integer> DENIED = Set.of(229, 230, 262, 1088, 2760, 3701, 4902, 15151, 15247);

        /** Microsoft's driver's type number for datetimeoffset ({@code microsoft.sql.Types.DATETIMEOFFSET}). */
        private static final int DATETIMEOFFSET = -155;

        @Override
        SqlDialect dialect() {
            return SqlDialect.SQL_SERVER;
        }

        @Override
        String identifierProblem(String name) {
            return name.length() > 128 ? "the name " + name + " is " + name.length() + " characters long; SQL Server "
                + "allows 128" : null;
        }

        @Override
        boolean sameName(String a, String b) {
            return a.equalsIgnoreCase(b);
        }

        /** Text is nvarchar(max) unless {@code text_length} says otherwise. */
        @Override
        String columnType(WriteColumn c, int textLength) {
            return switch (c.kind()) {
                case BOOLEAN -> "bit";
                case INT16 -> "smallint";
                case INT32 -> "int";
                case INT64 -> "bigint";
                case FLOAT32 -> "real";
                case FLOAT64 -> "float";
                case DECIMAL -> "decimal(" + Math.max(1, c.precision()) + "," + c.scale() + ")";
                case TEXT -> textLength > 0 ? "nvarchar(" + textLength + ")" : "nvarchar(max)";
                case BINARY -> "varbinary(max)";
                case DATE -> "date";
                case TIME -> "time(6)";
                case TIMESTAMP -> "datetime2(6)";
                case TIMESTAMP_TZ -> "datetimeoffset(6)";
            };
        }

        /** One MERGE per row, holding its key range locked so concurrent upserts cannot both insert. */
        @Override
        String upsert(TableName t, List<String> columns, List<String> key) {
            List<String> rest = columns.stream().filter(c -> !key.contains(c)).toList();
            String source = quote("source");
            String target = quote("target");
            return "MERGE INTO " + table(t) + " WITH (HOLDLOCK) AS " + target + " USING (VALUES ("
                + columns.stream().map(c -> "?").collect(Collectors.joining(", ")) + ")) AS " + source + " ("
                + columnList(columns) + ") ON " + key.stream().map(k -> target + "." + quote(k) + " = " + source + "."
                + quote(k)).collect(Collectors.joining(" AND "))
                + (rest.isEmpty() ? "" : " WHEN MATCHED THEN UPDATE SET " + rest.stream()
                .map(c -> quote(c) + " = " + source + "." + quote(c)).collect(Collectors.joining(", ")))
                + " WHEN NOT MATCHED THEN INSERT (" + columnList(columns) + ") VALUES ("
                + columns.stream().map(c -> source + "." + quote(c)).collect(Collectors.joining(", ")) + ");";
        }

        @Override
        Sql exists(TableName t) {
            return new Sql("SELECT CASE WHEN OBJECT_ID(?, 'U') IS NOT NULL OR OBJECT_ID(?, 'V') IS NOT NULL THEN 1 "
                + "ELSE 0 END", List.of(regName(t), regName(t)));
        }

        /** Names compare ignoring case under SQL Server's usual collations: the catalogue's spelling. */
        @Override
        Sql realName(TableName t) {
            return new Sql("SELECT s.name, o.name FROM sys.objects o JOIN sys.schemas s ON s.schema_id = o.schema_id "
                + "WHERE o.object_id = OBJECT_ID(?) AND o.type IN ('U', 'V')", List.of(regName(t)));
        }

        @Override
        Sql requiredColumns(TableName t) {
            return new Sql("SELECT c.name FROM sys.columns c WHERE c.object_id = OBJECT_ID(?) AND c.is_nullable = 0 "
                + "AND c.default_object_id = 0 AND c.is_identity = 0 AND c.is_computed = 0 "
                + "AND TYPE_NAME(c.system_type_id) NOT IN ('timestamp', 'rowversion')", List.of(regName(t)));
        }

        /**
         * Not a table; schema-bound views and functions (which stop it being renamed or dropped);
         * foreign keys from other tables; replication and change data capture, which renaming would
         * break.
         */
        @Override
        Sql blockers(TableName t) {
            String r = regName(t);
            // Catalogue names (sysname) carry the server's collation and type_desc another one; every
            // branch is cast to the database's collation, or the UNION cannot compare them.
            return new Sql("SELECT N'it is a view, not a table' COLLATE DATABASE_DEFAULT WHERE OBJECT_ID(?, 'U') "
                + "IS NULL AND OBJECT_ID(?, 'V') IS NOT NULL "
                + "UNION SELECT (N'the schema-bound ' + CASE o.type WHEN 'V' THEN N'view ' WHEN 'FN' THEN N'function ' "
                + "WHEN 'IF' THEN N'function ' WHEN 'TF' THEN N'function ' "
                + "ELSE LOWER(o.type_desc) COLLATE DATABASE_DEFAULT + N' ' END "
                + "+ OBJECT_SCHEMA_NAME(d.referencing_id) COLLATE DATABASE_DEFAULT + N'.' "
                + "+ OBJECT_NAME(d.referencing_id) COLLATE DATABASE_DEFAULT + N' depends on it') COLLATE DATABASE_DEFAULT "
                + "FROM sys.sql_expression_dependencies d JOIN sys.objects o ON o.object_id = d.referencing_id "
                + "WHERE d.referenced_id = OBJECT_ID(?) AND d.is_schema_bound_reference = 1 "
                + "UNION SELECT (N'the foreign key ' + f.name COLLATE DATABASE_DEFAULT + N' on ' "
                + "+ OBJECT_SCHEMA_NAME(f.parent_object_id) COLLATE DATABASE_DEFAULT + N'.' "
                + "+ OBJECT_NAME(f.parent_object_id) COLLATE DATABASE_DEFAULT + N' depends on it') COLLATE DATABASE_DEFAULT "
                + "FROM sys.foreign_keys f "
                + "WHERE f.referenced_object_id = OBJECT_ID(?) AND f.parent_object_id <> f.referenced_object_id "
                + "UNION SELECT N'it is published for replication' COLLATE DATABASE_DEFAULT FROM sys.tables x "
                + "WHERE x.object_id = OBJECT_ID(?) "
                + "AND (x.is_published = 1 OR x.is_merge_published = 1 OR x.is_schema_published = 1) "
                + "UNION SELECT N'change data capture tracks it' COLLATE DATABASE_DEFAULT FROM sys.tables x "
                + "WHERE x.object_id = OBJECT_ID(?) AND x.is_tracked_by_cdc = 1",
                List.of(r, r, r, r, r, r));
        }

        @Override
        Sql rowSecurity(TableName t) {
            return new Sql("SELECT CASE WHEN EXISTS (SELECT 1 FROM sys.security_predicates p "
                + "WHERE p.target_object_id = OBJECT_ID(?)) THEN 1 ELSE 0 END", List.of(regName(t)));
        }

        /**
         * A table owned through its schema (the usual case) keeps that owner when recreated in the
         * same schema; a table with an owner of its own is reported, and its owner's rights are
         * granted on the new table (giving ownership itself needs IMPERSONATE on that owner).
         */
        @Override
        Sql owner(TableName t) {
            return new Sql("SELECT p.name, 0 FROM sys.objects o JOIN sys.database_principals p "
                + "ON p.principal_id = o.principal_id WHERE o.object_id = OBJECT_ID(?) AND o.principal_id IS NOT NULL "
                + "AND p.name COLLATE DATABASE_DEFAULT <> USER_NAME() COLLATE DATABASE_DEFAULT", List.of(regName(t)));
        }

        @Override
        String setOwner(TableName t, String owner) {
            return "ALTER AUTHORIZATION ON OBJECT::" + table(t) + " TO " + quote(owner);
        }

        @Override
        String grantOwnerRights(TableName t, String owner) {
            return "GRANT CONTROL ON " + table(t) + " TO " + quote(owner);
        }

        @Override
        List<String> lockTimeout(long millis) {
            return List.of("SET LOCK_TIMEOUT " + Math.max(1, millis));
        }

        /** 1222: the lock request time-out period was exceeded. */
        @Override
        boolean lockTimedOut(SQLException e) {
            return e.getErrorCode() == 1222;
        }

        @Override
        Sql grants(TableName t) {
            return new Sql("SELECT p.state_desc, p.permission_name, pr.name FROM sys.database_permissions p "
                + "JOIN sys.database_principals pr ON pr.principal_id = p.grantee_principal_id "
                + "WHERE p.class = 1 AND p.major_id = OBJECT_ID(?) AND p.minor_id = 0", List.of(regName(t)));
        }

        @Override
        String grant(TableName to, java.sql.ResultSet row) throws SQLException {
            String state = row.getString(1);
            String permission = row.getString(2);
            String grantee = row.getString(3);
            if (permission == null || grantee == null || !PERMISSIONS.contains(permission)) {
                return null;
            }
            String on = " ON " + table(to) + " TO " + quote(grantee);
            return switch (state == null ? "" : state) {
                case "GRANT" -> "GRANT " + permission + on;
                case "GRANT_WITH_GRANT_OPTION" -> "GRANT " + permission + on + " WITH GRANT OPTION";
                case "DENY" -> "DENY " + permission + on;
                default -> null;
            };
        }

        @Override
        Sql rename(TableName from, String name) {
            return new Sql("EXEC sp_rename @objname = ?, @newname = ?", List.of(table(from), name));
        }

        @Override
        List<Check> privileges(String mode, TableName t, boolean exists) {
            List<Check> out = new ArrayList<>();
            switch (mode) {
                case "create", "replace" -> {
                    out.add(new Check(new Sql("SELECT CASE WHEN SCHEMA_ID(?) IS NULL THEN NULL ELSE 1 END",
                        List.of(t.schema())), "the schema " + t.schema() + " does not exist: ask a database "
                        + "administrator to create it, or write to another schema"));
                    out.add(new Check(new Sql("SELECT HAS_PERMS_BY_NAME(NULL, NULL, 'CREATE TABLE')"), null));
                    out.add(new Check(new Sql("SELECT HAS_PERMS_BY_NAME(?, 'SCHEMA', 'ALTER')",
                        List.of(quote(t.schema()))), null));
                    if (exists) {
                        out.add(objectPermission(t, "ALTER"));
                    }
                }
                case "append" -> out.add(objectPermission(t, "INSERT"));
                case "upsert" -> {
                    out.add(objectPermission(t, "INSERT"));
                    out.add(anyColumnPermission(t, "UPDATE"));
                    out.add(anyColumnPermission(t, "SELECT"));
                }
                default -> { }
            }
            return out;
        }

        /** UPDATE and SELECT can be granted per column: on the table or on at least one of its columns. */
        private Check anyColumnPermission(TableName t, String permission) {
            return new Check(new Sql("SELECT CASE WHEN HAS_PERMS_BY_NAME(?, 'OBJECT', '" + permission + "') = 1 "
                + "OR EXISTS (SELECT 1 FROM sys.columns c WHERE c.object_id = OBJECT_ID(?) AND HAS_PERMS_BY_NAME(?, "
                + "'OBJECT', '" + permission + "', c.name, 'COLUMN') = 1) THEN 1 ELSE 0 END",
                List.of(regName(t), regName(t), regName(t))), null);
        }

        private Check objectPermission(TableName t, String permission) {
            return new Check(new Sql("SELECT HAS_PERMS_BY_NAME(?, 'OBJECT', '" + permission + "')",
                List.of(regName(t))), null);
        }

        @Override
        String needs(String mode, TableName t, boolean exists) {
            return switch (mode) {
                case "create" -> "CREATE TABLE in the database and ALTER on the schema " + t.schema();
                case "replace" -> "CREATE TABLE in the database and ALTER on the schema " + t.schema()
                    + (exists ? ", and ALTER on " + t + ", since replace renames and drops the old table" : "");
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
                case BOOLEAN -> Types.BIT;
                case INT16 -> Types.SMALLINT;
                case INT32 -> Types.INTEGER;
                case INT64 -> Types.BIGINT;
                case FLOAT32 -> Types.REAL;
                case FLOAT64 -> Types.DOUBLE;
                case DECIMAL -> Types.DECIMAL;
                case TEXT -> Types.NVARCHAR;
                case BINARY -> Types.VARBINARY;
                case DATE -> Types.DATE;
                case TIME -> Types.TIME;
                case TIMESTAMP -> Types.TIMESTAMP;
                case TIMESTAMP_TZ -> DATETIMEOFFSET;
            };
        }

        /** nvarchar lengths count UTF-16 code units. */
        @Override
        int length(String s) {
            return s.length();
        }

        @Override
        void bindText(PreparedStatement ps, int i, String s) throws SQLException {
            ps.setString(i, s);
        }

        /**
         * The driver's bulk copy for batches turns date and time parameters into text with
         * {@code toString()}, which drops zero seconds ({@code 2024-01-01T00:00}) and which SQL
         * Server then refuses. For bulk copy into the node's own columns (date, time(6),
         * datetime2(6), datetimeoffset(6)) they are sent as full ISO 8601 text instead, which those
         * types read whatever the session's language or date format.
         */
        @Override
        void bind(PreparedStatement ps, int i, Kind kind, Object v, boolean bulk) throws SQLException {
            String text = bulk && v != null ? isoText(kind, v) : null;
            if (text != null) {
                ps.setString(i, text);
            } else {
                bind(ps, i, kind, v);
            }
        }

        /** Full ISO 8601 for a date, time or timestamp ({@code 2024-01-01T00:00:00.000000}); null for other kinds. */
        static String isoText(Kind kind, Object v) {
            return switch (kind) {
                case DATE -> ISO_DATE.format((LocalDate) v);
                case TIME -> ISO_TIME.format((LocalTime) v);
                case TIMESTAMP -> ISO_DATE.format((LocalDateTime) v) + "T" + ISO_TIME.format((LocalDateTime) v);
                case TIMESTAMP_TZ -> {
                    LocalDateTime t = LocalDateTime.ofInstant((Instant) v, ZoneOffset.UTC);
                    yield ISO_DATE.format(t) + "T" + ISO_TIME.format(t) + "+00:00";
                }
                default -> null;
            };
        }

        private static final java.time.format.DateTimeFormatter ISO_DATE =
            java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd", java.util.Locale.ROOT);
        private static final java.time.format.DateTimeFormatter ISO_TIME =
            // six digits, the scale of the node's own time(6), datetime2(6) and datetimeoffset(6)
            // columns (bulk copy refuses text with more: "invalid column length"). Values reach the
            // writer in microseconds (WriteColumn), so nothing is cut, and the typed path stores the
            // same value.
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSS", java.util.Locale.ROOT);
    }
}

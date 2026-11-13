package io.kilnmark.kiln.plugins.jdbc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.kiln.plugins.write.WriteColumn;

/**
 * The SQL generator of the database tools (ADR 0045): {@code CREATE TABLE}, {@code ALTER TABLE …
 * ADD}, {@code CREATE INDEX}, {@code DROP INDEX}, {@code DROP TABLE} and {@code TRUNCATE TABLE} in
 * each database's dialect, as text. Nothing here runs a statement.
 *
 * <p>Types and quoting come from the write node's own code ({@link WriteSql}), so a table the
 * generator writes is the one a pipeline's {@code create} mode would make. Names are always quoted
 * and schema-qualified where the database allows; a column's type is the dialect's type for its
 * kind, never text taken from a catalogue; lengths, precision and scale are whole numbers within the
 * dialect's ranges. Defaults, checks, comments, expressions and partial indexes are never written:
 * they can quote values.
 *
 * <p><b>Names.</b> A name read from the same database's catalogue is written exactly as it is
 * stored. A name a person typed, and every name when writing for another database, is written as
 * that database stores a plain name ({@link WriteSql#storedName}: Oracle stores {@code orders} as
 * {@code ORDERS}), as a pipeline's {@code create} writes it.
 */
public final class DdlWriter {

    /** The databases the generator writes for. */
    public enum Dialect {
        POSTGRESQL("postgresql", "PostgreSQL", 1000, 1000),
        SQLSERVER("sqlserver", "SQL Server", 38, 38),
        MYSQL("mysql", "MySQL", 65, 30),
        MARIADB("mariadb", "MariaDB", 65, 30),
        ORACLE("oracle", "Oracle", 38, 38);

        private final String kind;
        private final String label;
        private final int maxPrecision;
        private final int maxScale;

        Dialect(String kind, String label, int maxPrecision, int maxScale) {
            this.kind = kind;
            this.label = label;
            this.maxPrecision = maxPrecision;
            this.maxScale = maxScale;
        }

        /** The source kind of this database. */
        public String kind() {
            return kind;
        }

        /** The database's name, for people. */
        public String label() {
            return label;
        }

        /** The most digits a decimal column keeps. */
        public int maxPrecision() {
            return maxPrecision;
        }

        /** The most digits after the point a decimal column keeps (MySQL and MariaDB: 30). */
        public int maxScale() {
            return maxScale;
        }

        /** The dialect of a source kind, or empty for a kind with none (Generic JDBC, files). */
        public static Optional<Dialect> forKind(String kind) {
            for (Dialect d : values()) {
                if (d.kind.equals(kind)) {
                    return Optional.of(d);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * A column to write: its name, its kind ({@code JdbcColumn.Kind}), for text its length (0 for
     * unbounded), for decimals precision and scale (precision 0: no precision of its own), and
     * whether it allows nulls.
     */
    public record Column(String name, JdbcColumn.Kind kind, int length, int precision, int scale, boolean nullable) {
    }

    /** An index over columns, in order. */
    public record Index(String name, List<String> columns, boolean unique) {

        public Index {
            columns = List.copyOf(columns);
        }
    }

    /** The statements written, in order, and what was left out of them or changed, for people to read. */
    public record Generated(List<String> statements, List<String> leftOut) {

        public Generated {
            statements = List.copyOf(statements);
            leftOut = List.copyOf(leftOut);
        }
    }

    private final Dialect dialect;
    private final WriteSql sql;
    private final boolean converting;

    private DdlWriter(Dialect dialect, WriteSql sql, boolean converting) {
        this.dialect = dialect;
        this.sql = sql;
        this.converting = converting;
    }

    /** The generator for one database, for names read from that same database. */
    public static DdlWriter of(Dialect dialect) {
        return of(dialect, dialect);
    }

    /**
     * The generator for {@code dialect}, for names read from a {@code from} database (null when not
     * known): when they differ, every name is written as {@code dialect} stores a plain name.
     * Oracle is written without its 23ai {@code BOOLEAN}, as {@code create} writes it.
     */
    public static DdlWriter of(Dialect dialect, Dialect from) {
        return new DdlWriter(dialect, switch (dialect) {
            case POSTGRESQL -> WriteSql.Postgres.INSTANCE;
            case SQLSERVER -> WriteSql.SqlServer.INSTANCE;
            case MYSQL -> MySqlWriteSql.MYSQL;
            case MARIADB -> MySqlWriteSql.MARIADB;
            case ORACLE -> new OracleWriteSql(false, false);
        }, from != dialect);
    }

    public Dialect dialect() {
        return dialect;
    }

    /** The longest text a sized text column of this database keeps. */
    public long maxTextLength() {
        return sql.maxTextLength();
    }

    /**
     * {@code CREATE TABLE} with the columns, their nulls and the primary key, then a
     * {@code CREATE INDEX} for each index.
     *
     * @throws ConnectorException when a name cannot be written, a key or index names a column the
     *                            table does not have, or a size is out of range
     */
    public Generated createTable(String schema, String table, List<Column> columns, List<String> primaryKey,
                                 List<Index> indexes) throws ConnectorException {
        if (columns.isEmpty()) {
            throw new ConnectorException("A table needs at least one column.");
        }
        List<String> leftOut = new ArrayList<>();
        checkNames(schema, table);
        Set<String> names = new HashSet<>();
        Set<String> written = new HashSet<>();
        List<String> parts = new ArrayList<>();
        for (Column c : columns) {
            checkName(c.name());
            // two names the target stores alike (id and ID, written for Oracle) would be one column
            if (!names.add(c.name()) || !written.add(name(c.name(), false))) {
                throw new ConnectorException("Two columns are called " + c.name() + " in " + dialect.label() + ".");
            }
            parts.add(column(c, false, leftOut));
        }
        if (!primaryKey.isEmpty()) {
            for (String k : primaryKey) {
                known(names, k, "The primary key");
            }
            parts.add("PRIMARY KEY (" + primaryKey.stream().map(n -> name(n, false))
                .collect(Collectors.joining(", ")) + ")");
        }
        List<String> out = new ArrayList<>();
        String charset = dialect == Dialect.MYSQL || dialect == Dialect.MARIADB ? " DEFAULT CHARACTER SET utf8mb4" : "";
        out.add("CREATE TABLE " + qualified(schema, table) + " (\n  " + String.join(",\n  ", parts) + "\n)" + charset);
        for (Index i : indexes) {
            for (String col : i.columns()) {
                known(names, col, "The index " + i.name());
            }
            out.add(createIndexStatement(schema, table, i, false));
        }
        return new Generated(out, leftOut);
    }

    /**
     * {@code ALTER TABLE … ADD} for new columns, one statement each (every database here accepts
     * that form). The columns' names are a person's and are written as the database stores them.
     */
    public Generated addColumns(String schema, String table, List<Column> columns) throws ConnectorException {
        if (columns.isEmpty()) {
            throw new ConnectorException("Name at least one column to add.");
        }
        checkNames(schema, table);
        List<String> leftOut = new ArrayList<>();
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Column c : columns) {
            checkName(c.name());
            if (!seen.add(c.name().toLowerCase(Locale.ROOT))) {
                throw new ConnectorException("Two new columns are called " + c.name() + ".");
            }
            String add = dialect == Dialect.SQLSERVER || dialect == Dialect.ORACLE ? " ADD " : " ADD COLUMN ";
            out.add("ALTER TABLE " + qualified(schema, table) + add + column(c, true, leftOut));
            if (!c.nullable() && dialect != Dialect.MYSQL && dialect != Dialect.MARIADB) {
                leftOut.add("A column that allows no nulls and has no default cannot be added to a table that "
                    + "already has rows: " + c.name() + " needs the table to be empty.");
            }
        }
        return new Generated(out, leftOut);
    }

    /** {@code CREATE INDEX}, or {@code CREATE UNIQUE INDEX}, over existing columns in order; the index's name is a person's. */
    public Generated createIndex(String schema, String table, Index index) throws ConnectorException {
        checkNames(schema, table);
        return new Generated(List.of(createIndexStatement(schema, table, index, true)), List.of());
    }

    /** {@code DROP INDEX} of an index read from the catalogue, in the form each database takes. */
    public Generated dropIndex(String schema, String table, String index) throws ConnectorException {
        checkNames(schema, table);
        checkName(index);
        String q = name(index, false);
        return new Generated(List.of(switch (dialect) {
            case POSTGRESQL, ORACLE -> "DROP INDEX " + name(schema, false) + "." + q;
            case SQLSERVER, MYSQL, MARIADB -> "DROP INDEX " + q + " ON " + qualified(schema, table);
        }), List.of());
    }

    /**
     * {@code DROP TABLE}, never with {@code CASCADE}: a table other objects depend on is refused by
     * the database. Oracle's table goes to its recycle bin (no {@code PURGE}), so it can be brought back.
     */
    public Generated dropTable(String schema, String table) throws ConnectorException {
        checkNames(schema, table);
        return new Generated(List.of("DROP TABLE " + qualified(schema, table)), List.of());
    }

    /** {@code TRUNCATE TABLE}; on PostgreSQL {@code TRUNCATE ONLY}, so no child table is emptied with it. */
    public Generated truncate(String schema, String table) throws ConnectorException {
        checkNames(schema, table);
        return new Generated(List.of((dialect == Dialect.POSTGRESQL ? "TRUNCATE ONLY " : "TRUNCATE TABLE ")
            + qualified(schema, table)), List.of());
    }

    // ------------------------------------------------------------------ helpers

    /**
     * {@code CREATE INDEX}. PostgreSQL puts an index in its table's schema and takes no schema on
     * its name; Oracle puts an unqualified name in the connected user's schema, so its name is
     * qualified with the table's; SQL Server and MySQL name only the index.
     */
    private String createIndexStatement(String schema, String table, Index i, boolean typedName)
        throws ConnectorException {
        checkName(i.name());
        if (i.columns().isEmpty()) {
            throw new ConnectorException("The index " + i.name() + " needs at least one column.");
        }
        for (String c : i.columns()) {
            checkName(c);
        }
        String name = (dialect == Dialect.ORACLE ? name(schema, false) + "." : "") + name(i.name(), typedName);
        return "CREATE " + (i.unique() ? "UNIQUE " : "") + "INDEX " + name + " ON " + qualified(schema, table)
            + " (" + i.columns().stream().map(n -> name(n, false)).collect(Collectors.joining(", ")) + ")";
    }

    private String column(Column c, boolean typedName, List<String> leftOut) throws ConnectorException {
        if (c.length() < 0 || c.precision() < 0 || c.scale() < 0) {
            throw new ConnectorException("The column " + c.name() + " has a negative size.");
        }
        int length = c.length();
        if (c.kind() == JdbcColumn.Kind.TEXT && length > sql.maxTextLength()) {
            leftOut.add("The length of " + c.name() + " (" + length + "): " + dialect.label() + " keeps at most "
                + sql.maxTextLength() + " in a sized text column, so it is written without a length.");
            length = 0;
        }
        String type;
        if (c.kind() == JdbcColumn.Kind.DECIMAL) {
            type = decimalType(c, leftOut);
        } else {
            type = sql.createdType(new WriteColumn(c.name(), c.kind(), 0, 0, null), length);
        }
        return name(c.name(), typedName) + " " + type + (c.nullable() ? "" : " NOT NULL");
    }

    /** A decimal's type: its digits where the database keeps that many, the database's own unconstrained decimal where it has one. */
    private String decimalType(Column c, List<String> leftOut) throws ConnectorException {
        int precision = c.precision();
        int scale = c.scale();
        if (precision == 0) {
            switch (dialect) {
                case POSTGRESQL:
                    return "numeric";
                case ORACLE:
                    return "NUMBER";
                default:
                    int p = dialect.maxPrecision();
                    int s = Math.min(Math.min(Math.max(scale, 10), p / 2), dialect.maxScale());
                    leftOut.add(c.name() + " has no precision of its own; " + dialect.label() + " needs one, so it is "
                        + "written with " + p + " digits, " + s + " after the point.");
                    precision = p;
                    scale = s;
            }
        }
        if (precision > dialect.maxPrecision() || scale > precision || scale > dialect.maxScale()) {
            throw new ConnectorException("The column " + c.name() + " needs a precision from 1 to "
                + dialect.maxPrecision() + " on " + dialect.label() + ", and a scale no larger than it or "
                + dialect.maxScale() + ".");
        }
        return sql.createdType(new WriteColumn(c.name(), JdbcColumn.Kind.DECIMAL, precision, scale, null), 0);
    }

    /** A name quoted: as stored when it was read from this database; as a plain name is stored otherwise. */
    private String name(String n, boolean typed) {
        return sql.quote(typed || converting ? sql.storedName(n) : n);
    }

    private String qualified(String schema, String table) {
        return name(schema, false) + "." + name(table, false);
    }

    private void checkNames(String schema, String table) throws ConnectorException {
        checkName(schema);
        checkName(table);
    }

    private void checkName(String name) throws ConnectorException {
        if (name == null || name.isBlank()) {
            throw new ConnectorException("A name is missing.");
        }
        if (name.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7f)) {
            throw new ConnectorException("A name holds a control character; use letters, digits and underscores.");
        }
        String problem = sql.identifierProblem(name);
        if (problem != null) {
            throw new ConnectorException(Character.toUpperCase(problem.charAt(0)) + problem.substring(1) + ".");
        }
    }

    private static void known(Set<String> names, String column, String what) throws ConnectorException {
        if (!names.contains(column)) {
            throw new ConnectorException(what + " names " + column + ", which is not a column of the table.");
        }
    }

    /** A kind by its name as {@code AssetInfo} spells it ({@code int32}), or null for one Kilnmark does not know. */
    public static JdbcColumn.Kind kind(String name) {
        if (name == null) {
            return null;
        }
        try {
            return JdbcColumn.Kind.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

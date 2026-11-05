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
 * and schema-qualified; a column's type is the dialect's type for its kind, never text taken from a
 * catalogue; lengths, precision and scale are whole numbers within the dialect's ranges. Defaults,
 * checks, comments, expressions and partial indexes are never written: they can quote values.
 */
public final class DdlWriter {

    /** The databases the generator writes for. */
    public enum Dialect {
        POSTGRESQL("postgresql", "PostgreSQL"),
        SQLSERVER("sqlserver", "SQL Server"),
        MYSQL("mysql", "MySQL"),
        MARIADB("mariadb", "MariaDB"),
        ORACLE("oracle", "Oracle");

        private final String kind;
        private final String label;

        Dialect(String kind, String label) {
            this.kind = kind;
            this.label = label;
        }

        /** The source kind of this database. */
        public String kind() {
            return kind;
        }

        /** The database's name, for people. */
        public String label() {
            return label;
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
     * unbounded), for decimals precision and scale, and whether it allows nulls.
     */
    public record Column(String name, JdbcColumn.Kind kind, int length, int precision, int scale, boolean nullable) {
    }

    /** An index over columns, in order. */
    public record Index(String name, List<String> columns, boolean unique) {

        public Index {
            columns = List.copyOf(columns);
        }
    }

    /** The statements written, in order, and what was left out of them, for people to read. */
    public record Generated(List<String> statements, List<String> leftOut) {

        public Generated {
            statements = List.copyOf(statements);
            leftOut = List.copyOf(leftOut);
        }
    }

    /** The longest decimal precision any of these databases keeps. */
    static final int MAX_PRECISION = 38;

    private final Dialect dialect;
    private final WriteSql sql;

    private DdlWriter(Dialect dialect, WriteSql sql) {
        this.dialect = dialect;
        this.sql = sql;
    }

    /** The generator for one database. Oracle is written without its 23ai {@code BOOLEAN}, as {@code create} writes it. */
    public static DdlWriter of(Dialect dialect) {
        return new DdlWriter(dialect, switch (dialect) {
            case POSTGRESQL -> WriteSql.Postgres.INSTANCE;
            case SQLSERVER -> WriteSql.SqlServer.INSTANCE;
            case MYSQL -> MySqlWriteSql.MYSQL;
            case MARIADB -> MySqlWriteSql.MARIADB;
            case ORACLE -> new OracleWriteSql(false, false);
        });
    }

    public Dialect dialect() {
        return dialect;
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
        List<String> parts = new ArrayList<>();
        for (Column c : columns) {
            checkName(c.name());
            if (!names.add(c.name())) {
                throw new ConnectorException("Two columns are called " + c.name() + ".");
            }
            parts.add(column(c, leftOut));
        }
        if (!primaryKey.isEmpty()) {
            for (String k : primaryKey) {
                known(names, k, "The primary key");
            }
            parts.add("PRIMARY KEY (" + primaryKey.stream().map(this::quoteStored).collect(Collectors.joining(", "))
                + ")");
        }
        List<String> out = new ArrayList<>();
        out.add("CREATE TABLE " + qualified(schema, table) + " (\n  " + String.join(",\n  ", parts) + "\n)");
        for (Index i : indexes) {
            for (String col : i.columns()) {
                known(names, col, "The index " + i.name());
            }
            out.add(createIndexStatement(schema, table, i));
        }
        return new Generated(out, leftOut);
    }

    /** {@code ALTER TABLE … ADD} for new columns, one statement each (every database here accepts that form). */
    public Generated addColumns(String schema, String table, List<Column> columns) throws ConnectorException {
        if (columns.isEmpty()) {
            throw new ConnectorException("Name at least one column to add.");
        }
        checkNames(schema, table);
        List<String> leftOut = new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (Column c : columns) {
            checkName(c.name());
            String add = dialect == Dialect.SQLSERVER || dialect == Dialect.ORACLE ? " ADD " : " ADD COLUMN ";
            out.add("ALTER TABLE " + qualified(schema, table) + add + column(c, leftOut));
        }
        return new Generated(out, leftOut);
    }

    /** {@code CREATE INDEX}, or {@code CREATE UNIQUE INDEX}, over columns in order. */
    public Generated createIndex(String schema, String table, Index index) throws ConnectorException {
        checkNames(schema, table);
        return new Generated(List.of(createIndexStatement(schema, table, index)), List.of());
    }

    /** {@code DROP INDEX}, in the form each database takes. */
    public Generated dropIndex(String schema, String table, String index) throws ConnectorException {
        checkNames(schema, table);
        checkName(index);
        String q = quoteStored(index);
        return new Generated(List.of(switch (dialect) {
            case POSTGRESQL, ORACLE -> "DROP INDEX " + sql.quote(schema) + "." + q;
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

    private String createIndexStatement(String schema, String table, Index i) throws ConnectorException {
        checkName(i.name());
        if (i.columns().isEmpty()) {
            throw new ConnectorException("The index " + i.name() + " needs at least one column.");
        }
        for (String c : i.columns()) {
            checkName(c);
        }
        // PostgreSQL and Oracle keep an index in its table's schema; the others name only the index
        String name = quoteStored(i.name());
        return "CREATE " + (i.unique() ? "UNIQUE " : "") + "INDEX " + name + " ON " + qualified(schema, table)
            + " (" + i.columns().stream().map(this::quoteStored).collect(Collectors.joining(", ")) + ")";
    }

    private String column(Column c, List<String> leftOut) throws ConnectorException {
        if (c.length() < 0 || c.precision() < 0 || c.scale() < 0) {
            throw new ConnectorException("The column " + c.name() + " has a negative size.");
        }
        int length = c.length();
        if (c.kind() == JdbcColumn.Kind.TEXT && length > sql.maxTextLength()) {
            leftOut.add("The length of " + c.name() + " (" + length + "): " + dialect.label() + " keeps at most "
                + sql.maxTextLength() + " in a sized text column, so it is written without a length.");
            length = 0;
        }
        int precision = c.precision();
        int scale = c.scale();
        if (c.kind() == JdbcColumn.Kind.DECIMAL) {
            if (precision < 1 || precision > MAX_PRECISION || scale > precision) {
                throw new ConnectorException("The column " + c.name() + " needs a precision from 1 to " + MAX_PRECISION
                    + " and a scale no larger than it.");
            }
        }
        WriteColumn w = new WriteColumn(c.name(), c.kind(), precision, scale, null);
        return quoteStored(c.name()) + " " + sql.columnType(w, length) + (c.nullable() ? "" : " NOT NULL");
    }

    private String quoteStored(String name) {
        return sql.quote(sql.storedName(name));
    }

    private String qualified(String schema, String table) {
        return sql.quote(schema) + "." + sql.quote(table);
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

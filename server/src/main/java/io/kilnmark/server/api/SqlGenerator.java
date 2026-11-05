package io.kilnmark.server.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.kiln.connect.TableDetail;
import io.kilnmark.kiln.plugins.jdbc.DdlWriter;
import io.kilnmark.kiln.plugins.jdbc.JdbcColumn;

/**
 * The SQL generator of the database tools (ADR 0045, D2): text for a table Explore read, in the
 * source's dialect or another's. Nothing is run. Names come from the catalogue or from the request
 * as values and are quoted by {@link DdlWriter}; types only from each kind's own type; no default,
 * check, comment or expression text is ever copied.
 */
final class SqlGenerator {

    /** The statements a person may ask for. */
    static final List<String> STATEMENTS = List.of("create-table", "add-columns", "create-index", "drop-index",
        "drop-table", "truncate");

    /** Type names that are text, so a text column with one of them is not "written as text" but kept as it is. */
    private static final Set<String> TEXT_TYPES = Set.of("varchar", "char", "character", "character varying", "bpchar",
        "text", "nvarchar", "nchar", "ntext", "varchar2", "nvarchar2", "clob", "nclob", "longtext", "mediumtext",
        "tinytext", "string", "name", "citext");

    /** What a person asks for. {@code dialect} is a source kind ({@code postgresql}…), or null for the source's own. */
    record Request(String statement, String asset, String dialect, List<NewColumn> columns, NewIndex index) {
    }

    /** A column to add: its name, its kind ({@code int32}, {@code text}…), sizes as whole numbers, nulls. */
    record NewColumn(String name, String kind, Integer length, Integer precision, Integer scale, Boolean nullable) {
    }

    /** An index to create or drop. */
    record NewIndex(String name, List<String> columns, Boolean unique) {
    }

    private SqlGenerator() {
    }

    /** The dialect asked for, or the source kind's own. */
    static DdlWriter.Dialect dialect(String asked, String sourceKind) {
        String kind = asked == null || asked.isBlank() ? sourceKind : asked;
        return DdlWriter.Dialect.forKind(kind).orElseThrow(() -> new IllegalArgumentException(
            asked == null || asked.isBlank()
                ? "dialect: this source's database has no dialect of its own in Kilnmark; choose the database to "
                    + "write the SQL for (postgresql, sqlserver, mysql, mariadb or oracle)"
                : "dialect: one of postgresql, sqlserver, mysql, mariadb or oracle"));
    }

    /** Numeric type names, so a decimal the scan reads as text (Oracle's {@code NUMBER} without precision) is still a decimal. */
    private static final Set<String> NUMBER_TYPES = Set.of("number", "numeric", "decimal", "float", "dec");

    /** The longest text a key or an index can hold in each database, in characters (UTF-8 throughout). */
    private static int keyTextLength(DdlWriter.Dialect d) {
        return switch (d) {
            case POSTGRESQL -> 0; // text can be a key as it is
            case SQLSERVER -> 450;
            case MYSQL, MARIADB -> 768;
            case ORACLE -> 1000;
        };
    }

    /** The statements for {@code r} on the table {@code t} of a {@code from} database (null: no dialect of its own), in {@code d}. */
    static Views.GeneratedSql generate(Request r, TableDetail t, DdlWriter.Dialect d, DdlWriter.Dialect from) {
        DdlWriter w = DdlWriter.of(d, from);
        String statement = r.statement() == null ? "" : r.statement();
        boolean table = t.kind().equals("table") || t.kind().equals("partitioned table");
        try {
            DdlWriter.Generated g = switch (statement) {
                case "create-table" -> createTable(w, t, d, from == null);
                case "add-columns" -> {
                    requireTable(table, t);
                    yield w.addColumns(t.schema(), t.table(), newColumns(r.columns(), t));
                }
                case "create-index" -> {
                    requireTable(table, t);
                    NewIndex i = requireIndex(r.index());
                    for (String c : i.columns() == null ? List.<String>of() : i.columns()) {
                        if (t.columns().stream().noneMatch(col -> col.name().equals(c))) {
                            throw new IllegalArgumentException("index: " + t.path() + " has no column called " + c);
                        }
                    }
                    yield w.createIndex(t.schema(), t.table(), new DdlWriter.Index(i.name(),
                        i.columns() == null ? List.of() : i.columns(), Boolean.TRUE.equals(i.unique())));
                }
                case "drop-index" -> {
                    requireTable(table, t);
                    NewIndex i = requireIndex(r.index());
                    TableDetail.Index found = t.indexes().stream().filter(x -> x.name().equals(i.name())).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("index: " + t.path() + " has no index called "
                            + i.name()));
                    if (found.primaryKey()) {
                        throw new IllegalArgumentException("index: " + i.name() + " holds the primary key; drop the key "
                            + "with the table's own tools");
                    }
                    yield w.dropIndex(t.schema(), t.table(), i.name());
                }
                case "drop-table" -> {
                    requireTable(table, t);
                    yield w.dropTable(t.schema(), t.table());
                }
                case "truncate" -> {
                    requireTable(table, t);
                    if (d == DdlWriter.Dialect.POSTGRESQL && t.kind().equals("partitioned table")) {
                        throw new IllegalArgumentException(t.path() + " is partitioned: PostgreSQL empties it only "
                            + "with all its partitions. Truncate its partitions one at a time instead.");
                    }
                    yield w.truncate(t.schema(), t.table());
                }
                default -> throw new IllegalArgumentException("statement: one of " + String.join(", ", STATEMENTS));
            };
            return new Views.GeneratedSql(d.kind(), g.statements(), g.leftOut());
        } catch (ConnectorException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    private static void requireTable(boolean table, TableDetail t) {
        if (!table) {
            throw new IllegalArgumentException(t.path() + " is a " + t.kind() + ", not a table: generate SQL for its "
                + "tables instead");
        }
    }

    private static NewIndex requireIndex(NewIndex i) {
        if (i == null || i.name() == null || i.name().isBlank()) {
            throw new IllegalArgumentException("index: give the index's name");
        }
        return i;
    }

    /** {@code CREATE TABLE} for a table Explore read, with what was left out or changed listed. */
    private static DdlWriter.Generated createTable(DdlWriter w, TableDetail t, DdlWriter.Dialect d, boolean sizesUnknown)
        throws ConnectorException {
        List<String> leftOut = new ArrayList<>();
        List<DdlWriter.Column> columns = new ArrayList<>();
        List<String> defaults = new ArrayList<>();
        List<DdlWriter.Index> indexes = new ArrayList<>();
        Set<String> keyed = new java.util.HashSet<>(t.primaryKey());
        for (TableDetail.Index i : t.indexes()) {
            if (i.primaryKey()) {
                continue;
            }
            if (i.partial() || i.columns().stream().anyMatch(c -> t.columns().stream().noneMatch(x -> x.name().equals(c)))) {
                leftOut.add("The index " + i.name() + " is on an expression or covers only some rows; it is not "
                    + "written, as its text can quote values.");
                continue;
            }
            indexes.add(new DdlWriter.Index(i.name(), i.columns(), i.unique()));
            keyed.addAll(i.columns());
        }
        int keyText = keyTextLength(d);
        for (TableDetail.Column c : t.columns()) {
            JdbcColumn.Kind kind = DdlWriter.kind(c.kind());
            String type = c.sourceType() == null ? "" : c.sourceType().toLowerCase(Locale.ROOT);
            boolean number = NUMBER_TYPES.contains(type);
            if (kind == JdbcColumn.Kind.TEXT && number) {
                kind = JdbcColumn.Kind.DECIMAL; // a number without precision of its own, read as text by the scan
            } else if (kind == null || (kind == JdbcColumn.Kind.TEXT && !TEXT_TYPES.contains(type))) {
                leftOut.add(c.name() + " (" + c.sourceType() + ") has no type of its own in " + d.label()
                    + " that Kilnmark writes; it is written as text.");
                kind = JdbcColumn.Kind.TEXT;
            }
            Integer size = c.size() == null || c.size() <= 0 || c.size() >= 1_000_000 ? null : c.size();
            int length = kind == JdbcColumn.Kind.TEXT && TEXT_TYPES.contains(type) && size != null ? size : 0;
            if (kind == JdbcColumn.Kind.TEXT && keyed.contains(c.name()) && keyText > 0
                && (length == 0 || length > keyText)) {
                leftOut.add(c.name() + (length == 0 ? "" : " (" + length + " characters)") + " is in a key or an index, "
                    + "which " + d.label() + " cannot build on text " + (length == 0 ? "without a length" : "that long")
                    + ", so it is written with " + keyText + " characters: longer values will not fit.");
                length = keyText;
            }
            int precision = 0;
            int scale = 0;
            if (kind == JdbcColumn.Kind.DECIMAL) {
                // no precision of its own (0), or more than the target keeps: the target's widest or unconstrained decimal
                precision = size == null || size > d.maxPrecision() ? 0 : size;
                scale = c.scale() == null ? 0 : Math.max(0, c.scale());
                if (precision > 0 && scale > precision) {
                    leftOut.add(c.name() + " keeps more digits after the point (" + scale + ") than in all (" + precision
                        + "), which " + d.label() + " does not allow; it is written with " + precision + " after the point.");
                    scale = precision;
                }
                if (scale > d.maxScale()) {
                    leftOut.add(c.name() + " keeps " + scale + " digits after the point; " + d.label() + " keeps at most "
                        + d.maxScale() + ", so it is written with " + d.maxScale() + ".");
                    scale = d.maxScale();
                    if (precision > 0 && precision < scale) {
                        precision = scale;
                    }
                }
                if (size != null && size > d.maxPrecision()) {
                    leftOut.add(c.name() + " keeps " + size + " digits, more than " + d.label() + " keeps in a decimal "
                        + "with a precision; it is written without one where " + d.label() + " allows.");
                }
            }
            if (c.hasDefault() || c.generated()) {
                defaults.add(c.name());
            }
            columns.add(new DdlWriter.Column(c.name(), kind, length, precision, scale, c.nullable()));
        }
        if (sizesUnknown) {
            leftOut.add("This source does not say its columns' lengths and digits (Generic JDBC), so text is written "
                + "without a length and decimals without a precision.");
        }
        if (!indexes.isEmpty()) {
            leftOut.add("Indexes are written as plain ones: full-text, spatial, GIN or GiST kinds, descending order and "
                + "prefix lengths are not copied; check them.");
        }
        if (!defaults.isEmpty()) {
            leftOut.add("Defaults and values the database fills (" + String.join(", ", defaults) + ") are not "
                + "copied: their text can quote values.");
        }
        if (!t.foreignKeys().isEmpty()) {
            leftOut.add("Foreign keys are not written: add them once the tables they point to exist.");
        }
        leftOut.add("Checks, comments, triggers, grants and partitions are never copied.");
        DdlWriter.Generated g = w.createTable(t.schema(), t.table(), columns, t.primaryKey(), indexes);
        List<String> all = new ArrayList<>(leftOut);
        all.addAll(g.leftOut());
        return new DdlWriter.Generated(g.statements(), all);
    }

    private static List<DdlWriter.Column> newColumns(List<NewColumn> asked, TableDetail t) {
        if (asked == null || asked.isEmpty()) {
            throw new IllegalArgumentException("columns: name at least one column to add");
        }
        List<DdlWriter.Column> out = new ArrayList<>();
        for (NewColumn c : asked) {
            if (c.name() == null || c.name().isBlank()) {
                throw new IllegalArgumentException("columns: every column needs a name");
            }
            if (t.columns().stream().anyMatch(x -> x.name().equalsIgnoreCase(c.name()))) {
                throw new IllegalArgumentException("columns: " + t.path() + " already has a column called " + c.name());
            }
            JdbcColumn.Kind kind = DdlWriter.kind(c.kind());
            if (kind == null) {
                throw new IllegalArgumentException("columns: " + c.name() + " needs a kind: boolean, int16, int32, "
                    + "int64, float32, float64, decimal, text, binary, date, time, timestamp or timestamp_tz");
            }
            out.add(new DdlWriter.Column(c.name(), kind, whole(c.length()), whole(c.precision()), whole(c.scale()),
                !Boolean.FALSE.equals(c.nullable())));
        }
        return out;
    }

    private static int whole(Integer n) {
        if (n != null && (n < 0 || n > 1_000_000_000)) {
            throw new IllegalArgumentException("columns: sizes are whole numbers from 0");
        }
        return n == null ? 0 : n;
    }
}

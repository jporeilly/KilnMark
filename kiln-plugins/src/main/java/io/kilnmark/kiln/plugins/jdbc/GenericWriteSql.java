package io.kilnmark.kiln.plugins.jdbc;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.kilnmark.kiln.plugins.jdbc.JdbcColumn.Kind;
import io.kilnmark.kiln.plugins.write.WriteColumn;

/**
 * Writing to a database Kilnmark knows only through its JDBC driver: {@code append} and
 * {@code create}, nothing else ({@link GenericJdbcConnector#writer}). Rows go as batches of one
 * prepared {@code INSERT} with every value bound as a parameter. Everything that differs between
 * databases is read from the driver's {@link DatabaseMetaData}:
 *
 * <ul>
 *   <li>whether the table exists, its columns with their sizes and decimal digits, and which need a
 *       value ({@link DatabaseMetaData#getColumns});</li>
 *   <li>the column types a created table gets, from the types the driver lists
 *       ({@link DatabaseMetaData#getTypeInfo}); a kind the database has no type for refuses the
 *       create, naming it;</li>
 *   <li>how long names may be, how identifiers are quoted, and whether table definitions take part
 *       in transactions. Where they commit by themselves, a failed create drops the table again.</li>
 * </ul>
 *
 * <p>Each value is checked against the column it goes to, also for a table the node created (it is
 * described again once made): a decimal that would need rounding or more digits, text longer than
 * the column, bytes longer than the column, fractions of a second the column does not keep, an
 * integer outside a {@code TINYINT} or unsigned column. A column whose size the driver does not
 * report, or of a type Kilnmark does not know, is refused before anything is written, rather than
 * written unchecked.
 */
final class GenericWriteSql extends WriteSql {

    /** For capabilities and write settings: nothing about any database is known before connecting. */
    static final GenericWriteSql PLACEHOLDER = new GenericWriteSql(new GenericCatalog.Shape(
        GenericCatalog.Level.SCHEMA, null, "\"", ".", true), Map.of(), 0, false, Set.of());

    /** One type the driver lists. */
    record TypeInfo(String name, int jdbcType, long precision, boolean unsigned, int maxScale) {

        /**
         * The name with microseconds asked for, for a time or timestamp type that keeps fractions of a
         * second ({@code TIME(6)}, {@code TIMESTAMP(6) WITH TIME ZONE}): the engine's values have six
         * digits, and SQL's default for a time of day is none.
         */
        String withMicros() {
            if (maxScale <= 0) {
                return name;
            }
            String digits = "(" + Math.min(6, maxScale) + ")";
            int with = name.toUpperCase(Locale.ROOT).indexOf(" WITH");
            return with < 0 ? name + digits : name.substring(0, with) + digits + name.substring(with);
        }
    }

    private final GenericCatalog.Shape shape;
    /** The driver's types, by JDBC type code, in the driver's order. */
    private final Map<Integer, List<TypeInfo>> types;
    private final int maxColumnName;
    private final boolean ddlCommits;
    /** Type names the driver says are unsigned. */
    private final Set<String> unsignedTypes;
    /**
     * Why a column of the table described last cannot be written safely, by column name: its size or
     * type is unknown. Filled by {@link #targetColumnsByMetadata}; one node writes one table.
     */
    private final Map<String, String> unchecked = new HashMap<>();
    /**
     * The most bytes a text column holds, where the driver reports it ({@code CHAR_OCTET_LENGTH}),
     * by column name: columns sized in bytes (Sybase, Teradata's LATIN) cut longer text silently.
     */
    private final Map<String, Integer> octets = new HashMap<>();
    private final SqlDialect dialect;

    private GenericWriteSql(GenericCatalog.Shape shape, Map<Integer, List<TypeInfo>> types, int maxColumnName,
                            boolean ddlCommits, Set<String> unsignedTypes) {
        this.shape = shape;
        this.types = types;
        this.maxColumnName = maxColumnName;
        this.ddlCommits = ddlCommits;
        this.unsignedTypes = unsignedTypes;
        this.dialect = new Dialect(shape);
    }

    /** What the connection's driver says about its database. */
    static GenericWriteSql of(Connection c, GenericCatalog.Shape shape) throws SQLException {
        DatabaseMetaData md = c.getMetaData();
        Map<Integer, List<TypeInfo>> types = new HashMap<>();
        Set<String> unsigned = new java.util.HashSet<>();
        try (ResultSet rs = md.getTypeInfo()) {
            while (rs.next()) {
                String name = rs.getString("TYPE_NAME");
                int code = rs.getInt("DATA_TYPE");
                long precision = rs.getLong("PRECISION");
                boolean isUnsigned = rs.getBoolean("UNSIGNED_ATTRIBUTE");
                int maxScale = rs.getInt("MAXIMUM_SCALE");
                if (name != null) {
                    types.computeIfAbsent(code, k -> new ArrayList<>()).add(new TypeInfo(name, code, precision,
                        isUnsigned, maxScale));
                    if (isUnsigned) {
                        unsigned.add(name.toUpperCase(Locale.ROOT));
                    }
                }
            }
        } catch (SQLException | RuntimeException e) {
            // a driver that lists no types: every create is refused with what to do instead
        }
        boolean commits = true;
        try {
            commits = !md.supportsDataDefinitionAndDataManipulationTransactions()
                || md.dataDefinitionCausesTransactionCommit() || md.dataDefinitionIgnoredInTransactions();
        } catch (SQLException | RuntimeException e) {
            // assume the worst: a created table may outlive a rollback
        }
        return new GenericWriteSql(shape, types, safe(md::getMaxColumnNameLength), commits, unsigned);
    }

    private interface IntCall {
        int get() throws SQLException;
    }

    private static int safe(IntCall call) {
        try {
            return Math.max(0, call.get());
        } catch (SQLException | RuntimeException e) {
            return 0;
        }
    }

    /** Quoting as the driver says; the container and table as the database's level of names wants them. */
    private record Dialect(GenericCatalog.Shape shape) implements SqlDialect {

        @Override
        public String quote(String identifier) {
            return shape.quoted(identifier);
        }

        @Override
        public String table(String schema, String table) {
            return shape.qualified(schema, table);
        }

        @Override
        public String text(String expression) {
            return "CAST(" + expression + " AS VARCHAR(4000))";
        }

        @Override
        public String limit(String select, int n) {
            return "SELECT * FROM (" + select + ") limited FETCH FIRST " + n + " ROWS ONLY";
        }

        @Override
        public String regex(String expression, String pattern) {
            return null;
        }

        @Override
        public String now() {
            return "CURRENT_TIMESTAMP";
        }

        @Override
        public List<String> readOnly() {
            return List.of();
        }

        @Override
        public boolean enforcesReadOnly() {
            return false;
        }
    }

    @Override
    SqlDialect dialect() {
        return dialect;
    }

    GenericCatalog.Shape shape() {
        return shape;
    }

    @Override
    String identifierProblem(String name) {
        if (shape.quote().isEmpty() && !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return "the name " + name + " needs quoting, which this database's driver does not offer: use letters, "
                + "digits and _ only";
        }
        if (maxColumnName > 0 && name.length() > maxColumnName) {
            return "the name " + name + " is longer than the " + maxColumnName + " characters this database allows";
        }
        return null;
    }

    @Override
    boolean sameName(String a, String b) {
        return a.equals(b);
    }

    @Override
    boolean transactionalDdl() {
        // never the temporary name and rename of WriteSql's other databases: renaming is not portable
        return true;
    }

    @Override
    boolean dropCreatedOnFailure() {
        return ddlCommits;
    }

    @Override
    boolean checksCreatedTable() {
        return true;
    }

    // ------------------------------------------------------------------ creating

    /** The first type the driver lists for any of {@code codes}, preferring {@code names}, in order. */
    private TypeInfo pick(List<Integer> codes, String... names) {
        for (String n : names) {
            for (int code : codes) {
                for (TypeInfo t : types.getOrDefault(code, List.of())) {
                    if (t.name().equalsIgnoreCase(n)) {
                        return t;
                    }
                }
            }
        }
        for (int code : codes) {
            for (TypeInfo t : types.getOrDefault(code, List.of())) {
                // a type named for a vendor's variant (VARCHAR_IGNORECASE, ...) only when there is nothing plainer
                if (!t.unsigned() && !t.name().contains("_") && !t.name().toUpperCase(Locale.ROOT).contains("UNSIGNED")) {
                    return t;
                }
            }
        }
        for (int code : codes) {
            for (TypeInfo t : types.getOrDefault(code, List.of())) {
                if (!t.unsigned() && !t.name().toUpperCase(Locale.ROOT).contains("UNSIGNED")) {
                    return t;
                }
            }
        }
        return null;
    }

    private TypeInfo textType() {
        return pick(List.of(Types.VARCHAR, Types.NVARCHAR), "VARCHAR", "CHARACTER VARYING", "NVARCHAR");
    }

    private TypeInfo longTextType() {
        return pick(List.of(Types.CLOB, Types.LONGVARCHAR, Types.NCLOB, Types.LONGNVARCHAR), "CLOB",
            "CHARACTER LARGE OBJECT", "TEXT", "LONG VARCHAR");
    }

    private TypeInfo decimalType() {
        return pick(List.of(Types.DECIMAL, Types.NUMERIC), "DECIMAL", "NUMERIC", "NUMBER");
    }

    private TypeInfo timestampTzType() {
        TypeInfo t = pick(List.of(Types.TIMESTAMP_WITH_TIMEZONE), "TIMESTAMP WITH TIME ZONE", "TIMESTAMPTZ",
            "TIMESTAMP_TZ");
        if (t != null) {
            return t;
        }
        for (TypeInfo i : types.getOrDefault(Types.TIMESTAMP, List.of())) {
            if (i.name().toUpperCase(Locale.ROOT).contains("WITH TIME ZONE")) {
                return i;
            }
        }
        return null;
    }

    /** The type for {@code c}, or null when the driver lists none that holds it. */
    private String typeFor(WriteColumn c, int textLength) {
        TypeInfo t;
        return switch (c.kind()) {
            case BOOLEAN -> (t = pick(List.of(Types.BOOLEAN, Types.BIT), "BOOLEAN", "BIT")) == null ? null : t.name();
            case INT16 -> (t = pick(List.of(Types.SMALLINT), "SMALLINT")) != null ? t.name() : intType(Types.INTEGER, 5);
            case INT32 -> intType(Types.INTEGER, 10);
            case INT64 -> intType(Types.BIGINT, 19);
            case FLOAT32 -> (t = pick(List.of(Types.REAL), "REAL")) != null ? t.name() : doubleType();
            case FLOAT64 -> doubleType();
            case DECIMAL -> {
                TypeInfo d = decimalType();
                yield d == null || (d.precision() > 0 && c.precision() > d.precision()) ? null
                    : d.name() + "(" + c.precision() + "," + c.scale() + ")";
            }
            case TEXT -> {
                TypeInfo v = textType();
                if (textLength > 0) {
                    yield v == null || (v.precision() > 0 && textLength > v.precision()) ? null
                        : v.name() + "(" + textLength + ")";
                }
                TypeInfo l = longTextType();
                yield l != null ? l.name() : v != null && v.precision() > 0 && v.precision() <= Integer.MAX_VALUE
                    ? v.name() + "(" + v.precision() + ")" : null;
            }
            case BINARY -> {
                TypeInfo l = pick(List.of(Types.BLOB, Types.LONGVARBINARY), "BLOB", "BINARY LARGE OBJECT",
                    "LONG VARBINARY");
                if (l != null) {
                    yield l.name();
                }
                TypeInfo v = pick(List.of(Types.VARBINARY), "VARBINARY", "BINARY VARYING");
                yield v != null && v.precision() > 0 && v.precision() <= Integer.MAX_VALUE
                    ? v.name() + "(" + v.precision() + ")" : null;
            }
            case DATE -> (t = pick(List.of(Types.DATE), "DATE")) == null ? null : t.name();
            case TIME -> (t = pick(List.of(Types.TIME), "TIME")) == null ? null : t.withMicros();
            case TIMESTAMP -> {
                TypeInfo ts = pick(List.of(Types.TIMESTAMP), "TIMESTAMP", "DATETIME2", "DATETIME");
                yield ts == null || ts.name().toUpperCase(Locale.ROOT).contains("ZONE") ? null : ts.withMicros();
            }
            case TIMESTAMP_TZ -> (t = timestampTzType()) == null ? null : t.withMicros();
        };
    }

    private String intType(int code, int digits) {
        TypeInfo t = code == Types.INTEGER ? pick(List.of(Types.INTEGER), "INTEGER", "INT")
            : pick(List.of(Types.BIGINT), "BIGINT");
        if (t != null) {
            return t.name();
        }
        if (code == Types.INTEGER) {
            TypeInfo b = pick(List.of(Types.BIGINT), "BIGINT");
            if (b != null) {
                return b.name();
            }
        }
        TypeInfo d = decimalType();
        return d == null || (d.precision() > 0 && d.precision() < digits) ? null : d.name() + "(" + digits + ",0)";
    }

    private String doubleType() {
        TypeInfo t = pick(List.of(Types.DOUBLE, Types.FLOAT), "DOUBLE PRECISION", "DOUBLE", "FLOAT", "FLOAT8");
        return t == null ? null : t.name();
    }

    @Override
    String columnType(WriteColumn c, int textLength) {
        String t = typeFor(c, textLength);
        if (t == null) {
            throw new IllegalStateException("no type for " + c.kindName() + ": createProblem refuses this first");
        }
        return t;
    }

    @Override
    String createProblem(List<WriteColumn> columns, int textLength) {
        if (shape.level() == GenericCatalog.Level.NONE) {
            return "this database's driver reports no schemas or catalogues to create a table in";
        }
        List<String> problems = new ArrayList<>();
        for (WriteColumn c : columns) {
            if (typeFor(c, textLength) == null) {
                String what = c.kind() == Kind.DECIMAL ? "decimal(" + c.precision() + "," + c.scale() + ")" : c.kindName();
                problems.add("the column " + c.name() + " is " + what + ", and the driver lists no type for it"
                    + switch (c.kind()) {
                        case TIMESTAMP_TZ -> ": convert it to a timestamp without a time zone (in UTC) with a compute "
                            + "node first";
                        case TEXT -> textLength > 0 ? " that long: lower text_length" : ": set text_length";
                        case DECIMAL -> " with that many digits";
                        default -> ": convert it with a compute node first";
                    });
            }
        }
        return problems.isEmpty() ? null : String.join("; ", problems);
    }

    @Override
    long maxTextLength() {
        TypeInfo v = textType();
        return v != null && v.precision() > 0 ? Math.min(v.precision(), Integer.MAX_VALUE) : super.maxTextLength();
    }

    // ------------------------------------------------------------------ the table, from metadata

    @Override
    Boolean existsByMetadata(Connection c, TableName t) throws SQLException {
        DatabaseMetaData md = c.getMetaData();
        try (ResultSet rs = md.getTables(shape.catalogArg(t.schema()), schemaArg(md, t.schema()),
            GenericCatalog.pattern(md, t.table()), null)) {
            while (rs.next()) {
                if (t.table().equals(rs.getString("TABLE_NAME")) && inContainer(rs, t.schema())) {
                    return true;
                }
            }
        }
        return false;
    }

    private String schemaArg(DatabaseMetaData md, String container) {
        return shape.level() == GenericCatalog.Level.SCHEMA ? GenericCatalog.pattern(md, container) : null;
    }

    private boolean inContainer(ResultSet rs, String container) throws SQLException {
        return switch (shape.level()) {
            case SCHEMA -> container.equals(rs.getString("TABLE_SCHEM"));
            case CATALOG -> container.equals(rs.getString("TABLE_CAT"));
            case NONE -> true;
        };
    }

    @Override
    Set<String> requiredByMetadata(Connection c, TableName t) throws SQLException {
        Set<String> out = new LinkedHashSet<>();
        DatabaseMetaData md = c.getMetaData();
        try (ResultSet rs = md.getColumns(shape.catalogArg(t.schema()), schemaArg(md, t.schema()),
            GenericCatalog.pattern(md, t.table()), "%")) {
            while (rs.next()) {
                if (!t.table().equals(rs.getString("TABLE_NAME")) || !inContainer(rs, t.schema())) {
                    continue;
                }
                boolean notNull = rs.getInt("NULLABLE") == DatabaseMetaData.columnNoNulls;
                String def = rs.getString("COLUMN_DEF");
                if (notNull && def == null && !"YES".equalsIgnoreCase(optional(rs, "IS_AUTOINCREMENT"))
                    && !"YES".equalsIgnoreCase(optional(rs, "IS_GENERATEDCOLUMN"))) {
                    out.add(rs.getString("COLUMN_NAME"));
                }
            }
        }
        return out;
    }

    /** For tests: a column's size in bytes as a driver sizing text in bytes reports it. */
    void octetsForTest(String column, int bytes) {
        octets.put(column, bytes);
    }

    private static Integer optionalInt(ResultSet rs, String column) {
        try {
            int v = rs.getInt(column);
            return rs.wasNull() ? null : v;
        } catch (SQLException | RuntimeException e) {
            return null; // older drivers do not have it
        }
    }

    private static String optional(ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (SQLException | RuntimeException e) {
            return null; // older drivers do not have it
        }
    }

    /**
     * The table's columns from {@link DatabaseMetaData#getColumns}: a decimal's precision and scale
     * ({@code COLUMN_SIZE}, {@code DECIMAL_DIGITS}), a text column's length in characters, a binary
     * column's in bytes, a time's digits of a second. A size the driver leaves out is marked unknown
     * (-1), and {@link #targetMisfit} refuses the column.
     */
    @Override
    List<WritePlan.TargetColumn> targetColumnsByMetadata(Connection c, TableName t) throws SQLException {
        unchecked.clear();
        octets.clear();
        List<WritePlan.TargetColumn> out = new ArrayList<>();
        DatabaseMetaData md = c.getMetaData();
        try (ResultSet rs = md.getColumns(shape.catalogArg(t.schema()), schemaArg(md, t.schema()),
            GenericCatalog.pattern(md, t.table()), "%")) {
            while (rs.next()) {
                if (!t.table().equals(rs.getString("TABLE_NAME")) || !inContainer(rs, t.schema())) {
                    continue;
                }
                String name = rs.getString("COLUMN_NAME");
                int code = rs.getInt("DATA_TYPE");
                String typeName = rs.getString("TYPE_NAME");
                int size = rs.getInt("COLUMN_SIZE");
                boolean sizeKnown = !rs.wasNull() && size > 0;
                int digits = rs.getInt("DECIMAL_DIGITS");
                boolean digitsKnown = !rs.wasNull() && digits >= 0;
                if (code == Types.CHAR || code == Types.VARCHAR || code == Types.NCHAR || code == Types.NVARCHAR) {
                    Integer bytes = optionalInt(rs, "CHAR_OCTET_LENGTH");
                    if (bytes != null && bytes > 0) {
                        octets.put(name, bytes);
                    }
                }
                out.add(target(name, code, typeName == null ? GenericTypes.jdbcName(code) : typeName, size, sizeKnown,
                    digits, digitsKnown));
            }
        }
        return out.isEmpty() ? null : out;
    }

    /** One column as {@link #targetColumnsByMetadata} describes it. */
    WritePlan.TargetColumn target(String name, int code, String typeName, int size, boolean sizeKnown, int digits,
                                  boolean digitsKnown) {
        GenericTypes.Mapped m = GenericTypes.map(code, typeName, size, digits);
        switch (code) {
            case Types.NUMERIC, Types.DECIMAL -> {
                // always a decimal here, whatever its width: text would let the database round as it parses
                if (!sizeKnown) {
                    unchecked.put(name, "the driver does not say how many digits it keeps");
                }
                return new WritePlan.TargetColumn(name, typeName, Kind.DECIMAL, sizeKnown ? size : -1,
                    digitsKnown ? digits : 0, 0);
            }
            case Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR -> {
                if (!sizeKnown) {
                    unchecked.put(name, "the driver does not say how many characters it holds");
                }
                return new WritePlan.TargetColumn(name, typeName, Kind.TEXT, sizeKnown ? size : 0,
                    0, sizeKnown ? size : -1);
            }
            case Types.BINARY, Types.VARBINARY -> {
                if (!sizeKnown) {
                    unchecked.put(name, "the driver does not say how many bytes it holds");
                }
                return new WritePlan.TargetColumn(name, typeName, Kind.BINARY, sizeKnown ? size : 0, 0, 0);
            }
            case Types.TIME, Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> {
                return new WritePlan.TargetColumn(name, typeName, m.kind(), 0, digitsKnown ? Math.min(digits, 9) : -1,
                    0);
            }
            default -> {
                if (m.note() != null || m.read() == GenericTypes.Read.NUMBER_TEXT) {
                    unchecked.put(name, "Kilnmark does not know its type, " + typeName + ", so cannot check values fit");
                }
                return new WritePlan.TargetColumn(name, typeName, m.kind(), 0, 0, 0);
            }
        }
    }

    @Override
    String targetMisfit(WriteColumn c, WritePlan.TargetColumn t) {
        String why = unchecked.get(t.name());
        return why == null ? null : "the table's column " + t.name() + " (" + t.typeName() + ") cannot be written "
            + "safely: " + why + ", so a value could be rounded or cut without anyone knowing";
    }

    @Override
    int fractionDigits(WritePlan.TargetColumn column) {
        boolean time = column.kind() == Kind.TIME || column.kind() == Kind.TIMESTAMP
            || column.kind() == Kind.TIMESTAMP_TZ;
        return time && column.scale() >= 0 ? column.scale() : -1;
    }

    @Override
    // The nanoseconds within the second are wanted: the fraction a column may be too short for.
    @SuppressWarnings({"JavaLocalDateTimeGetNano", "JavaInstantGetSecondsGetNano", "JavaLocalTimeGetNano"})
    String targetValueProblem(WritePlan.TargetColumn t, Object v) {
        if (t == null) {
            return null;
        }
        String type = t.typeName() == null ? "" : t.typeName().toUpperCase(Locale.ROOT);
        Integer maxBytes = octets.get(t.name());
        // too many characters is said as such by the length check that follows; bytes are checked otherwise
        if (v instanceof String text && maxBytes != null && !(t.maxLength() > 0 && length(text) > t.maxLength())) {
            int bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes > maxBytes) {
                return "the value is " + bytes + " bytes long in UTF-8, but the column holds at most " + maxBytes
                    + " bytes, and longer text could be cut. Shorten the values with a compute node, or ask for the "
                    + "column to be widened";
            }
        }
        if (v instanceof byte[] bytes && t.kind() == Kind.BINARY && t.precision() > 0 && bytes.length > t.precision()) {
            return "it is " + bytes.length + " bytes long, but the column holds at most " + t.precision();
        }
        if (v instanceof Long n) {
            boolean unsigned = type.contains("UNSIGNED") || unsignedTypes.contains(type);
            if (unsigned && n < 0) {
                return "the value " + n + " is negative, and the column " + t.typeName() + " holds none";
            }
            if (type.equals("TINYINT") && (unsigned ? n > 255 : n < -128 || n > 127)) {
                return "the value " + n + " is outside " + (unsigned ? "0 to 255" : "-128 to 127") + ", what the "
                    + "column's TINYINT holds";
            }
        }
        boolean time = t.kind() == Kind.TIME || t.kind() == Kind.TIMESTAMP || t.kind() == Kind.TIMESTAMP_TZ;
        if (time && t.scale() < 0) {
            int nanos = switch (v) {
                case LocalDateTime x -> x.getNano();
                case Instant x -> x.getNano();
                case LocalTime x -> x.getNano();
                default -> 0;
            };
            if (nanos != 0) {
                return "the value " + v + " has fractions of a second, and the driver does not say how many digits of "
                    + "a second the column keeps, so they could be cut. Truncate them with a compute node first";
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ binding

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
            case TEXT -> Types.VARCHAR;
            case BINARY -> Types.VARBINARY;
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

    @Override
    void bindText(PreparedStatement ps, int i, String s) throws SQLException {
        ps.setString(i, s);
    }

    /**
     * As every writer binds, with {@code java.time} values; a driver that cannot take them gets the
     * {@code java.sql} types instead (a timestamp with a zone as its instant).
     */
    @Override
    void bind(PreparedStatement ps, int i, Kind kind, Object v) throws SQLException {
        if (v == null) {
            try {
                ps.setNull(i, nullType(kind));
            } catch (SQLFeatureNotSupportedException e) {
                ps.setNull(i, Types.NULL);
            }
            return;
        }
        try {
            super.bind(ps, i, kind, v);
        } catch (SQLFeatureNotSupportedException | AbstractMethodError e) {
            switch (kind) {
                case DATE -> ps.setDate(i, java.sql.Date.valueOf((LocalDate) v));
                case TIME -> ps.setTime(i, java.sql.Time.valueOf((LocalTime) v));
                case TIMESTAMP -> ps.setTimestamp(i, java.sql.Timestamp.valueOf((LocalDateTime) v));
                case TIMESTAMP_TZ -> ps.setTimestamp(i, java.sql.Timestamp.from((Instant) v),
                    java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone(ZoneOffset.UTC)));
                case DECIMAL -> ps.setString(i, ((BigDecimal) v).toPlainString());
                default -> throw e instanceof SQLException s ? s : new SQLException(e);
            }
        }
    }

    // ------------------------------------------------------------------ what generic JDBC never does

    private static UnsupportedOperationException refused() {
        return new UnsupportedOperationException("generic JDBC writes only append and create");
    }

    @Override
    String upsert(TableName t, List<String> columns, List<String> key) {
        throw refused();
    }

    @Override
    Sql exists(TableName t) {
        throw refused(); // existsByMetadata answers
    }

    @Override
    Sql requiredColumns(TableName t) {
        throw refused(); // requiredByMetadata answers
    }

    @Override
    Sql blockers(TableName t) {
        throw refused();
    }

    @Override
    Sql rowSecurity(TableName t) {
        throw refused();
    }

    @Override
    Sql owner(TableName t) {
        throw refused();
    }

    @Override
    String setOwner(TableName t, String owner) {
        throw refused();
    }

    @Override
    String grantOwnerRights(TableName t, String owner) {
        throw refused();
    }

    @Override
    List<String> lockTimeout(long millis) {
        return List.of();
    }

    @Override
    boolean lockTimedOut(SQLException e) {
        return false;
    }

    @Override
    Sql grants(TableName t) {
        throw refused();
    }

    @Override
    String grant(TableName to, ResultSet row) {
        throw refused();
    }

    @Override
    Sql rename(TableName from, String name) {
        throw refused();
    }

    /** Rights cannot be checked the same way everywhere: the database refuses, and the message says what to ask for. */
    @Override
    List<Check> privileges(String mode, TableName t, boolean exists) {
        return List.of();
    }

    @Override
    String needs(String mode, TableName t, boolean exists) {
        return exists ? "INSERT on " + t : "the right to create tables in " + t.schema() + " (and to insert into them)";
    }

    /** SQL state 42501 (insufficient privilege), or a 42 state whose message says so. */
    @Override
    boolean permissionDenied(SQLException e) {
        String state = e.getSQLState();
        String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return "42501".equals(state) || (state != null && state.startsWith("42")
            && (m.contains("permission") || m.contains("privilege") || m.contains("not authorized")));
    }
}

package io.kilnmark.kiln.plugins.jdbc;

import java.util.Set;

import io.kilnmark.kiln.connect.AssetInfo;

/** PostgreSQL, and databases that speak its protocol. */
public final class PostgresConnector extends JdbcConnector {

    /** PostgreSQL's own schemas, and Apache AGE's. Names are case-sensitive in PostgreSQL. */
    private static final Set<String> SYSTEM_SCHEMAS = Set.of("pg_catalog", "information_schema", "pg_toast",
        "ag_catalog");

    @Override
    public String kind() {
        return "postgresql";
    }

    @Override
    public String displayName() {
        return "PostgreSQL";
    }

    /**
     * The sslmode the driver will use, worked out by the driver's own parser from the URL and the
     * properties this source adds (the URL wins, as when connecting): require, verify-ca and
     * verify-full always encrypt; prefer, the default, falls back to plain text.
     */
    @Override
    public String unencryptedReason(io.kilnmark.kiln.connect.SourceConfig source) {
        String url = jdbcUrl(source);
        java.util.Properties p = url == null ? null : org.postgresql.Driver.parseURL(url, driverSettings(source));
        if (p == null) {
            return "url: not a PostgreSQL JDBC URL";
        }
        try {
            org.postgresql.jdbc.SslMode mode = org.postgresql.jdbc.SslMode.of(p);
            return mode.requireEncryption() ? null : "url: with sslmode=" + mode.value + " the connection may fall back "
                + "to plain text; add sslmode=require (or verify-full) to the URL";
        } catch (java.sql.SQLException e) {
            return "url: an sslmode the PostgreSQL driver does not know";
        }
    }

    @Override
    public String monogram() {
        return "Pg";
    }

    @Override
    protected String urlPrefix() {
        return "jdbc:postgresql://";
    }

    @Override
    protected String exampleUrl() {
        return "jdbc:postgresql://db.example.com:5432/sales";
    }

    @Override
    protected String selectSql(String schema, String table, long rowLimit) {
        return "SELECT * FROM " + quote(schema) + "." + quote(table) + (rowLimit > 0 ? " LIMIT " + rowLimit : "");
    }

    /** text, and the character types; not uuid, json, enums or numbers without a precision read as text. */
    @Override
    protected boolean comparesAsText(String baseType) {
        return baseType.equals("text") || super.comparesAsText(baseType);
    }

    @Override
    protected String selectSql(AssetInfo asset, long rowLimit, boolean explicit, String where) {
        String columns = explicit ? String.join(", ", asset.fields().stream().map(f -> quote(f.name())).toList()) : "*";
        return "SELECT " + columns + " FROM " + quote(asset.hints().get("schema")) + "." + quote(asset.hints().get("table"))
            + (where == null ? "" : " WHERE " + where) + (rowLimit > 0 ? " LIMIT " + rowLimit : "");
    }

    /** pgjdbc's connect, login and socket timeouts, in seconds. */
    @Override
    protected java.util.Properties timeouts(java.time.Duration timeout) {
        java.util.Properties p = new java.util.Properties();
        String s = Integer.toString(seconds(timeout));
        p.setProperty("connectTimeout", s);
        p.setProperty("loginTimeout", s);
        p.setProperty("socketTimeout", s);
        return p;
    }

    /** {@code jdbc:postgresql://host:5432/database}. */
    @Override
    protected String addressUrl(String host, Integer port, String database) {
        String h = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return "jdbc:postgresql://" + h + ":" + (port == null ? 5432 : port) + "/"
            + (database == null || database.isBlank() ? "postgres" : database);
    }

    @Override
    public SqlDialect dialect() {
        return SqlDialect.POSTGRESQL;
    }

    @Override
    WriteSql writeSql() {
        return WriteSql.Postgres.INSTANCE;
    }

    /** Ordinary tables kept in this database only, for Count exactly (ADR 0045). */
    @Override
    protected String localTableSql(int major) {
        return major >= 12 ? LOCAL_TABLE : LOCAL_TABLE_BEFORE_12;
    }

    private static final String LOCAL_TABLE_BEFORE_12 = "SELECT 1 FROM pg_catalog.pg_class c "
        + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = ? AND c.relname = ? "
        + "AND c.relkind = 'r' AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_inherits i WHERE i.inhparent = c.oid)";

    /**
     * An ordinary table without child tables, or (PostgreSQL 12 and later, which have
     * {@code pg_partition_tree}) a partitioned one with no foreign partition at any level: a count
     * of either never reaches another server.
     */
    static final String LOCAL_TABLE = "SELECT 1 FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n "
        + "ON n.oid = c.relnamespace WHERE n.nspname = ? AND c.relname = ? AND ("
        + "(c.relkind = 'r' AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_inherits i WHERE i.inhparent = c.oid)) "
        + "OR (c.relkind = 'p' AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_partition_tree(c.oid) t "
        + "JOIN pg_catalog.pg_class k ON k.oid = t.relid WHERE k.relkind = 'f')))";

    /**
     * A relation an extension owns, wherever it sits ({@code pg_stat_statements} in {@code public}
     * keeps other sessions' statements, literals and all).
     */
    @Override
    protected String refusedTableSql() {
        return "SELECT 1 FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
            + "JOIN pg_catalog.pg_depend d ON d.objid = c.oid AND d.classid = 'pg_catalog.pg_class'::pg_catalog.regclass "
            + "AND d.deptype = 'e' WHERE n.nspname = ? AND c.relname = ?";
    }

    /** The driver lists an index's {@code INCLUDE} columns as keys: keep the first {@code indnkeyatts}. */
    @Override
    protected java.util.List<io.kilnmark.kiln.connect.TableDetail.Index> keyColumnsOnly(java.sql.Connection c,
        String schema, String table, java.util.List<io.kilnmark.kiln.connect.TableDetail.Index> indexes)
        throws java.sql.SQLException {
        if (c.getMetaData().getDatabaseMajorVersion() < 11 || indexes.isEmpty()) {
            return indexes;
        }
        java.util.Map<String, Integer> keys = new java.util.HashMap<>();
        try (java.sql.PreparedStatement s = c.prepareStatement("SELECT i.relname, x.indnkeyatts "
            + "FROM pg_catalog.pg_index x JOIN pg_catalog.pg_class i ON i.oid = x.indexrelid "
            + "JOIN pg_catalog.pg_class t ON t.oid = x.indrelid JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace "
            + "WHERE n.nspname = ? AND t.relname = ?")) {
            s.setString(1, schema);
            s.setString(2, table);
            try (java.sql.ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    keys.put(rs.getString(1), rs.getInt(2));
                }
            }
        }
        return indexes.stream().map(i -> {
            Integer n = keys.get(i.name());
            return n == null || n >= i.columns().size() ? i : new io.kilnmark.kiln.connect.TableDetail.Index(i.name(),
                i.columns().subList(0, n), i.unique(), i.primaryKey(), i.method(), i.partial());
        }).toList();
    }

    /** The planner's estimate (-1 before the table was first analysed, so it is then read in full). */
    @Override
    protected String estimateRowsSql(io.kilnmark.kiln.connect.AssetInfo asset) {
        String name = quote(asset.hints().get("schema")) + "." + quote(asset.hints().get("table"));
        return "SELECT reltuples::bigint FROM pg_class WHERE oid = to_regclass(" + dialect().literal(name) + ")";
    }

    /** Block sampling, repeatable: the same blocks while the table is unchanged. */
    @Override
    protected String tableSampleClause(String percent) {
        return "TABLESAMPLE SYSTEM (" + percent + ") REPEATABLE (7)";
    }

    @Override
    protected boolean isSystemSchema(String schema) {
        return SYSTEM_SCHEMAS.contains(schema) || schema.startsWith("pg_temp") || schema.startsWith("pg_toast");
    }

    /** Foreign keys with one metadata call per schema: the driver accepts a null table name. */
    @Override
    protected java.util.List<JdbcCatalog.ForeignKey> readForeignKeys(java.sql.Connection connection,
                                                                     java.util.List<JdbcCatalog.Table> tables)
        throws java.sql.SQLException {
        return JdbcCatalog.foreignKeysBySchema(connection, tables);
    }

    /**
     * Turns {@code standard_conforming_strings} on for the transaction, then checks that the driver
     * says it is on. SQL a person writes is checked as PostgreSQL reads it with that setting on (a
     * backslash in a string is a plain character); with it off, a string can end elsewhere and hide a
     * statement from the check and split differently in the driver. Call it inside a transaction and
     * again before every statement a person wrote, as an earlier statement may have turned it off.
     *
     * @throws java.sql.SQLException when the setting is off however it was set (the database, the
     *                               login, a URL's options, or SQL run before)
     */
    public static void conformingStrings(java.sql.Connection c) throws java.sql.SQLException {
        try (java.sql.Statement st = c.createStatement()) {
            st.execute("SET LOCAL standard_conforming_strings = on");
        }
        requireConformingStrings(c);
    }

    /** Checks, without changing it, that the driver says {@code standard_conforming_strings} is on. */
    public static void requireConformingStrings(java.sql.Connection c) throws java.sql.SQLException {
        if (!c.unwrap(org.postgresql.core.BaseConnection.class).getStandardConformingStrings()) {
            throw new java.sql.SQLException("standard_conforming_strings is off for this session: Kilnmark runs SQL a "
                + "person wrote only with it on", "42000");
        }
    }
}

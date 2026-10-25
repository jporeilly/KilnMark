package io.kilnmark.kiln.plugins.jdbc;

import java.util.Set;

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
        return WriteSql.POSTGRESQL;
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
}

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

    @Override
    public SqlDialect dialect() {
        return SqlDialect.POSTGRESQL;
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
}

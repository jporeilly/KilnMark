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

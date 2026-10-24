package io.kilnmark.server.relations;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import io.kilnmark.kiln.plugins.jdbc.JdbcCatalog;
import io.kilnmark.kiln.plugins.jdbc.JdbcConnector;

/**
 * A test connector for PostgreSQL whose foreign keys can be made unreadable, as for an account
 * that may not see them. Listed in this test tree's META-INF/services, so tests only.
 */
public final class FlakyKeysConnector extends JdbcConnector {

    /** When true, reading foreign keys fails. */
    static volatile boolean failKeys;

    @Override
    public String kind() {
        return "test-flaky-keys";
    }

    @Override
    public String displayName() {
        return "PostgreSQL with unreadable keys (test)";
    }

    @Override
    protected String urlPrefix() {
        return "jdbc:postgresql://";
    }

    @Override
    protected String exampleUrl() {
        return "jdbc:postgresql://localhost:5432/test";
    }

    @Override
    protected String selectSql(String schema, String table, long rowLimit) {
        return "SELECT * FROM " + quote(schema) + "." + quote(table) + (rowLimit > 0 ? " LIMIT " + rowLimit : "");
    }

    @Override
    protected List<JdbcCatalog.ForeignKey> readForeignKeys(Connection connection, List<JdbcCatalog.Table> tables)
        throws SQLException {
        if (failKeys) {
            throw new SQLException("permission denied to read foreign keys (test)");
        }
        return super.readForeignKeys(connection, tables);
    }
}

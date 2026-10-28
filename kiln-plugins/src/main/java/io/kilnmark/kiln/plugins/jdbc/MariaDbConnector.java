package io.kilnmark.kiln.plugins.jdbc;

/**
 * MariaDB (kind {@code mariadb}): {@link MySqlConnector}'s code, with MariaDB's name, letter tile,
 * help text and version check (10.6 and later).
 */
public final class MariaDbConnector extends MySqlConnector {

    public MariaDbConnector() {
        super(Product.MARIADB);
    }
}

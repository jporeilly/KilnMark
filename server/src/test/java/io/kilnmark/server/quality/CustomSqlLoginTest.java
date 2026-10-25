package io.kilnmark.server.quality;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * The login check custom SQL runs first, against a real PostgreSQL (CI's container tests): a
 * read-only login that may reach other databases, through dblink or a foreign server, is refused
 * like a superuser. Everything happens in a database of its own, dropped afterwards, so the
 * extensions never touch the database other tests use.
 */
@QuarkusTest
class CustomSqlLoginTest {

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String url;

    @ConfigProperty(name = "quarkus.datasource.username")
    String user;

    @ConfigProperty(name = "quarkus.datasource.password")
    String password;

    private static final String DB = "kilnmark_login_check";
    private static final String READER = "login_check_reader";

    /** The test database's URL with another database name. */
    private String urlOf(String database) {
        int slash = url.indexOf('/', "jdbc:postgresql://".length());
        int query = url.indexOf('?', slash);
        return url.substring(0, slash + 1) + database + (query < 0 ? "" : url.substring(query));
    }

    private void admin(String database, String... statements) throws SQLException {
        try (Connection c = DriverManager.getConnection(database == null ? url : urlOf(database), user, password);
             Statement s = c.createStatement()) {
            for (String q : statements) {
                s.execute(q);
            }
        }
    }

    private boolean refused() throws SQLException {
        try (Connection c = DriverManager.getConnection(urlOf(DB), READER, "reader-pw")) {
            c.setAutoCommit(false);
            c.setReadOnly(true);
            return RuleRunner.privileged(c);
        }
    }

    @Test
    void loginsThatMayReachOtherDatabasesAreRefused() throws Exception {
        admin(null, "DROP DATABASE IF EXISTS " + DB, "CREATE DATABASE " + DB,
            "DROP ROLE IF EXISTS " + READER, "CREATE ROLE " + READER + " LOGIN PASSWORD 'reader-pw'");
        try {
            assertFalse(refused(), "a plain read-only login may run custom SQL");

            // dblink's functions are granted to PUBLIC when the extension is created, whatever its schema.
            admin(DB, "CREATE SCHEMA ext", "CREATE EXTENSION dblink SCHEMA ext");
            assertTrue(refused(), "dblink is executable");
            admin(DB, "REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA ext FROM PUBLIC");
            assertFalse(refused(), "dblink is installed but not executable");

            admin(DB, "CREATE EXTENSION postgres_fdw",
                "CREATE SERVER elsewhere FOREIGN DATA WRAPPER postgres_fdw OPTIONS (dbname 'postgres')");
            assertFalse(refused(), "a foreign server the login may not use");
            admin(DB, "GRANT USAGE ON FOREIGN SERVER elsewhere TO " + READER);
            assertTrue(refused(), "a foreign server the login may use");
            admin(DB, "REVOKE USAGE ON FOREIGN SERVER elsewhere FROM " + READER,
                "CREATE USER MAPPING FOR " + READER + " SERVER elsewhere OPTIONS (user 'x', password 'y')");
            assertTrue(refused(), "a user mapping for the login: foreign tables it may read reach elsewhere");
            admin(DB, "DROP USER MAPPING FOR " + READER + " SERVER elsewhere",
                "CREATE USER MAPPING FOR PUBLIC SERVER elsewhere OPTIONS (user 'x', password 'y')");
            assertTrue(refused(), "a user mapping for PUBLIC");
            admin(DB, "DROP USER MAPPING FOR PUBLIC SERVER elsewhere");
            assertFalse(refused());
        } finally {
            admin(null, "DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)", "DROP ROLE IF EXISTS " + READER);
        }
    }
}

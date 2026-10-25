package io.kilnmark.server.quality;

import static org.junit.jupiter.api.Assertions.assertNull;
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
 * like a superuser, and so is any database with a user mapping or a foreign table, which a view
 * owned by another role can reach. Each refusal names the fix. Everything happens in a database of its own, dropped afterwards, so the
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
    private static final String OWNER = "login_check_owner";

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

    /** Why custom SQL is refused for the reader on the test's database, or null. */
    private String refused() throws SQLException {
        try (Connection c = DriverManager.getConnection(urlOf(DB), READER, "reader-pw")) {
            c.setAutoCommit(false);
            c.setReadOnly(true);
            return RuleRunner.privileged(c);
        }
    }

    private static void says(String refusal, String fix) {
        assertTrue(refusal != null && refusal.contains(fix), refusal);
    }

    @Test
    void loginsThatMayReachOtherDatabasesAreRefused() throws Exception {
        admin(null, "DROP DATABASE IF EXISTS " + DB, "CREATE DATABASE " + DB,
            "DROP ROLE IF EXISTS " + READER, "CREATE ROLE " + READER + " LOGIN PASSWORD 'reader-pw'",
            "DROP ROLE IF EXISTS " + OWNER, "CREATE ROLE " + OWNER);
        try {
            assertNull(refused(), "a plain read-only login may run custom SQL");

            // dblink's functions are granted to PUBLIC when the extension is created, whatever its schema.
            admin(DB, "CREATE SCHEMA ext", "CREATE EXTENSION dblink SCHEMA ext");
            says(refused(), "REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA ext FROM PUBLIC");
            admin(DB, "REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA ext FROM PUBLIC");
            assertNull(refused(), "dblink is installed but not executable");

            admin(DB, "CREATE EXTENSION postgres_fdw",
                "CREATE SERVER elsewhere FOREIGN DATA WRAPPER postgres_fdw OPTIONS (dbname 'postgres')");
            assertNull(refused(), "a foreign server nobody may use, with no mappings or tables");
            admin(DB, "GRANT USAGE ON FOREIGN SERVER elsewhere TO " + READER);
            says(refused(), "REVOKE USAGE ON FOREIGN SERVER elsewhere");
            admin(DB, "REVOKE USAGE ON FOREIGN SERVER elsewhere FROM " + READER,
                "CREATE USER MAPPING FOR " + READER + " SERVER elsewhere OPTIONS (user 'x', password 'y')");
            says(refused(), "user mappings");
            admin(DB, "DROP USER MAPPING FOR " + READER + " SERVER elsewhere",
                "CREATE USER MAPPING FOR PUBLIC SERVER elsewhere OPTIONS (user 'x', password 'y')");
            says(refused(), "user mappings");
            admin(DB, "DROP USER MAPPING FOR PUBLIC SERVER elsewhere");
            assertNull(refused());

            // Another role's mapping reaches a foreign table through a view the reader may read.
            admin(DB, "GRANT USAGE ON FOREIGN SERVER elsewhere TO " + OWNER,
                "CREATE USER MAPPING FOR " + OWNER + " SERVER elsewhere OPTIONS (user 'x', password 'y')",
                "CREATE SCHEMA o AUTHORIZATION " + OWNER, "SET ROLE " + OWNER,
                "CREATE FOREIGN TABLE o.remote (n int) SERVER elsewhere OPTIONS (table_name 'pg_class')",
                "CREATE VIEW o.near AS SELECT * FROM o.remote", "GRANT USAGE ON SCHEMA o TO " + READER,
                "GRANT SELECT ON o.near TO " + READER, "RESET ROLE");
            says(refused(), "user mappings");
            admin(DB, "DROP USER MAPPING FOR " + OWNER + " SERVER elsewhere");
            says(refused(), "foreign tables");
            admin(DB, "DROP SCHEMA o CASCADE");
            assertNull(refused());
        } finally {
            admin(null, "DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)", "DROP ROLE IF EXISTS " + READER,
                "DROP ROLE IF EXISTS " + OWNER);
        }
    }
}

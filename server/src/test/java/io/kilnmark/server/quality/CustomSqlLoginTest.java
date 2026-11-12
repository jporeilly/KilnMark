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

    /**
     * A login that is a member of a role that is a superuser or may create roles, directly or through
     * another role, inherited or not, could act as that role (SET ROLE), so it is refused as such a
     * role itself would be (owner decision, 12 November 2026). A member of an ordinary role is not.
     */
    @Test
    void membersOfSuperuserOrRoleCreatingRolesAreRefused() throws Exception {
        String sup = "login_check_super";
        String maker = "login_check_maker";
        String middle = "login_check_middle";
        admin(null, "DROP DATABASE IF EXISTS " + DB, "CREATE DATABASE " + DB,
            "DROP ROLE IF EXISTS " + READER, "CREATE ROLE " + READER + " LOGIN PASSWORD 'reader-pw'",
            "DROP ROLE IF EXISTS " + middle, "DROP ROLE IF EXISTS " + sup, "DROP ROLE IF EXISTS " + maker,
            "DROP ROLE IF EXISTS " + OWNER, "CREATE ROLE " + OWNER,
            "CREATE ROLE " + sup + " SUPERUSER NOLOGIN", "CREATE ROLE " + maker + " CREATEROLE NOLOGIN",
            "CREATE ROLE " + middle + " NOLOGIN");
        try {
            assertNull(refused(), "a plain read-only login may run custom SQL");
            admin(null, "GRANT " + OWNER + " TO " + READER);
            assertNull(refused(), "a member of an ordinary role still may");

            admin(null, "GRANT " + sup + " TO " + READER);
            String direct = refused();
            says(direct, "it is a member of the role " + sup + ", which is a superuser or may create roles");
            says(direct, "REVOKE " + sup + " FROM " + READER);
            admin(null, "REVOKE " + sup + " FROM " + READER);
            assertNull(refused());

            // Not inherited: SET ROLE still makes the login that role
            admin(null, "ALTER ROLE " + READER + " NOINHERIT", "GRANT " + maker + " TO " + READER);
            says(refused(), "it is a member of the role " + maker);
            admin(null, "REVOKE " + maker + " FROM " + READER, "ALTER ROLE " + READER + " INHERIT");
            assertNull(refused());

            // Through another role
            admin(null, "GRANT " + sup + " TO " + middle, "GRANT " + middle + " TO " + READER);
            says(refused(), "it is a member of the role " + sup);
            admin(null, "REVOKE " + middle + " FROM " + READER);
            assertNull(refused());

            // PostgreSQL 16 and later: a grant that neither inherits nor may SET ROLE is still a membership
            if (version() >= 160000) {
                admin(null, "GRANT " + sup + " TO " + READER + " WITH INHERIT FALSE, SET FALSE");
                says(refused(), "it is a member of the role " + sup);
                admin(null, "REVOKE " + sup + " FROM " + READER);
                admin(null, "GRANT " + maker + " TO " + READER + " WITH INHERIT FALSE, SET TRUE");
                says(refused(), "it is a member of the role " + maker);
                admin(null, "REVOKE " + maker + " FROM " + READER);
            }
            // The login's own attributes are refused as before, with their own words
            admin(null, "ALTER ROLE " + READER + " CREATEROLE");
            assertTrue(refused().startsWith(RuleRunner.PRIVILEGED_REFUSAL.substring(0, 40)), refused());
            admin(null, "ALTER ROLE " + READER + " NOCREATEROLE");
            assertNull(refused());
        } finally {
            admin(null, "DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)", "DROP ROLE IF EXISTS " + READER,
                "DROP ROLE IF EXISTS " + middle, "DROP ROLE IF EXISTS " + sup, "DROP ROLE IF EXISTS " + maker,
                "DROP ROLE IF EXISTS " + OWNER);
        }
    }

    /**
     * The fourth review: another database user could switch the check off by planting a function in a
     * schema on the login's search path (public, by default on PostgreSQL 14 and older) that matched
     * the check's unqualified quote_ident(name) better than the built-in one, so a membership or a
     * foreign server read as nothing. Every name in the check is now qualified, and it runs with the
     * search path set to pg_catalog alone. A login with REPLICATION, which can make replication slots
     * from a read-only transaction, is refused too.
     */
    @Test
    void plantedFunctionsChangeNothingAndReplicationLoginsAreRefused() throws Exception {
        String sup = "login_check_super";
        admin(null, "DROP DATABASE IF EXISTS " + DB, "CREATE DATABASE " + DB,
            "DROP ROLE IF EXISTS " + READER, "CREATE ROLE " + READER + " LOGIN PASSWORD 'reader-pw'",
            "DROP ROLE IF EXISTS " + sup, "CREATE ROLE " + sup + " SUPERUSER NOLOGIN");
        try {
            admin(DB, "GRANT CREATE ON SCHEMA public TO PUBLIC",
                "CREATE FUNCTION public.quote_ident(name) RETURNS text LANGUAGE sql AS 'SELECT NULL::text'",
                "CREATE FUNCTION public.concat(text, text, text, text) RETURNS text LANGUAGE sql AS 'SELECT NULL::text'");
            assertNull(refused(), "the planted functions do not refuse a plain login either");
            admin(null, "GRANT " + sup + " TO " + READER);
            says(refused(), "it is a member of the role " + sup);
            admin(null, "REVOKE " + sup + " FROM " + READER);

            admin(DB, "CREATE EXTENSION postgres_fdw",
                "CREATE SERVER elsewhere FOREIGN DATA WRAPPER postgres_fdw OPTIONS (dbname 'postgres')",
                "GRANT USAGE ON FOREIGN SERVER elsewhere TO " + READER);
            says(refused(), "REVOKE USAGE ON FOREIGN SERVER elsewhere");
            admin(DB, "REVOKE USAGE ON FOREIGN SERVER elsewhere FROM " + READER);
            assertNull(refused());

            admin(null, "ALTER ROLE " + READER + " REPLICATION");
            says(refused(), "it has the REPLICATION attribute");
            admin(null, "ALTER ROLE " + READER + " NOREPLICATION");
            assertNull(refused());
        } finally {
            admin(null, "DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)", "DROP ROLE IF EXISTS " + READER,
                "DROP ROLE IF EXISTS " + sup);
        }
    }

    private int version() throws SQLException {
        try (Connection c = DriverManager.getConnection(url, user, password); Statement s = c.createStatement();
             java.sql.ResultSet rs = s.executeQuery("SHOW server_version_num")) {
            rs.next();
            return Integer.parseInt(rs.getString(1));
        }
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
            String dblink = refused();
            says(dblink, "REVOKE EXECUTE ON FUNCTION ");
            says(dblink, "dblink_connect(text,text)");
            assertTrue(!dblink.contains("IN SCHEMA"), "never a whole-schema revoke: " + dblink);
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

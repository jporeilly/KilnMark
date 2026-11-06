package io.kilnmark.server.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The Starter Pack's database (ADR 0051), against the real kilnmark-postgres image (built as
 * kilnmark-postgres:dev, as CI does, or the tag KILNMARK_TEST_POSTGRES_IMAGE names, for a machine
 * where other work shares that tag) and its own script, kilnmark-samples-db.sh, which the samples-db
 * service runs at every start: the login kilnmark_samples connects to kilnmark_samples only, over
 * TLS; it cannot connect to Kilnmark's own database, nor read its tables even if it were let in;
 * and every start closes again a database a restore left open.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SamplesDatabaseIsolationTest {

    static final String SUPER_PASSWORD = "super-0123456789-abcdef";
    static final String SAMPLES_PASSWORD = "samples-0123456789-abcdef";

    /** The image under test: kilnmark-postgres:dev as CI builds it, or KILNMARK_TEST_POSTGRES_IMAGE. */
    static final String IMAGE = System.getenv().getOrDefault("KILNMARK_TEST_POSTGRES_IMAGE", "kilnmark-postgres:dev");

    @Container
    static final GenericContainer<?> PG = new GenericContainer<>(DockerImageName.parse(IMAGE))
        .withEnv("POSTGRES_DB", "kilnmark").withEnv("POSTGRES_USER", "kilnmark")
        .withEnv("POSTGRES_PASSWORD", SUPER_PASSWORD).withExposedPorts(5432)
        .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2));

    private static String url(String database) {
        return "jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/" + database + "?sslmode=require";
    }

    private static Connection as(String user, String password, String database) throws SQLException {
        Properties p = new Properties();
        p.setProperty("user", user);
        p.setProperty("password", password);
        return DriverManager.getConnection(url(database), p);
    }

    private static Connection superuser(String database) throws SQLException {
        return as("kilnmark", SUPER_PASSWORD, database);
    }

    private static Connection samples(String database) throws SQLException {
        return as("kilnmark_samples", SAMPLES_PASSWORD, database);
    }

    /** Runs the image's own script as samples-db does: the superuser over TLS, the password in the environment. */
    private static ExecResult provision(String samplesPassword) throws Exception {
        return PG.execInContainer("sh", "-c", "PGHOST=127.0.0.1 PGUSER=kilnmark PGPASSWORD='" + SUPER_PASSWORD
            + "' PGSSLMODE=require KILNMARK_SAMPLES_DB_PASSWORD='" + samplesPassword
            + "' /usr/local/bin/kilnmark-samples-db.sh");
    }

    private static void exec(Connection c, String... sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            for (String s : sql) {
                st.execute(s);
            }
        }
    }

    private static boolean exists(String sql) throws SQLException {
        try (Connection c = superuser("postgres"); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next();
        }
    }

    /** "permission denied for database": refused for want of CONNECT. */
    private static void refused(String database) {
        SQLException e = assertThrows(SQLException.class, () -> samples(database).close(), database);
        assertEquals("42501", e.getSQLState(), database + ": " + e.getMessage());
    }

    @Test
    @Order(1)
    void withoutAPasswordNothingIsMadeAndATooShortOneIsRefused() throws Exception {
        ExecResult r = provision("");
        assertEquals(0, r.getExitCode(), r.getStderr());
        assertTrue(r.getStdout().contains("not set"), r.getStdout());
        assertFalse(exists("SELECT 1 FROM pg_roles WHERE rolname = 'kilnmark_samples'"));
        assertFalse(exists("SELECT 1 FROM pg_database WHERE datname = 'kilnmark_samples'"));
        r = provision("short");
        assertEquals(1, r.getExitCode());
        assertFalse(exists("SELECT 1 FROM pg_roles WHERE rolname = 'kilnmark_samples'"));
    }

    @Test
    @Order(2)
    void theSamplesLoginConnectsToItsOwnDatabaseOnlyOverTls() throws Exception {
        // Kilnmark's own tables, as its migrations make them: a schema of its own, owned by kilnmark.
        try (Connection c = superuser("kilnmark")) {
            exec(c, "CREATE SCHEMA kilnmark", "CREATE TABLE kilnmark.api_token (id int, hash text)",
                "INSERT INTO kilnmark.api_token VALUES (1, 'secret')", "CREATE TABLE public.notes (t text)");
        }
        for (int run = 0; run < 2; run++) { // twice: the second run changes nothing and fails nothing
            ExecResult r = provision(SAMPLES_PASSWORD);
            assertEquals(0, r.getExitCode(), r.getStderr());
        }
        try (Connection c = samples("kilnmark_samples"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT current_database(), ssl, (SELECT rolsuper OR rolcreatedb OR rolcreaterole "
                 + "OR rolreplication OR rolbypassrls FROM pg_roles WHERE rolname = current_user), (SELECT count(*) FROM "
                 + "pg_auth_members m JOIN pg_roles r ON r.oid = m.member WHERE r.rolname = current_user) FROM pg_stat_ssl "
                 + "WHERE pid = pg_backend_pid()")) {
            assertTrue(rs.next());
            assertEquals("kilnmark_samples", rs.getString(1));
            assertTrue(rs.getBoolean(2), "over TLS");
            assertFalse(rs.getBoolean(3), "no superuser, database, role, replication or row-security powers");
            assertEquals(0, rs.getInt(4), "a member of no role");
            // it owns its database, so it can make the sample tables there
            exec(c, "CREATE TABLE public.crm_customers (id int)", "DROP TABLE public.crm_customers");
        }
        refused("kilnmark");
        refused("postgres");
        refused("template1");
        assertTrue(exists("SELECT 1 FROM pg_database WHERE datname = 'kilnmark_samples' AND datdba = "
            + "(SELECT oid FROM pg_roles WHERE rolname = 'kilnmark_samples')"), "owned by its login");
        assertTrue(exists("SELECT 1 FROM pg_database WHERE datname = 'kilnmark' AND datdba = "
            + "(SELECT oid FROM pg_roles WHERE rolname = 'kilnmark')"), "Kilnmark's own database is still Kilnmark's");
    }

    @Test
    @Order(3)
    void letInByMistakeItStillCannotReadKilnmarksTablesAndTheNextStartShutsItOutAgain() throws Exception {
        try (Connection c = superuser("kilnmark")) {
            exec(c, "GRANT CONNECT ON DATABASE kilnmark TO kilnmark_samples");
        }
        try (Connection c = samples("kilnmark"); Statement st = c.createStatement()) {
            for (String sql : new String[] {"SELECT * FROM kilnmark.api_token", "SELECT * FROM public.notes",
                "CREATE TABLE public.mine (id int)", "CREATE SCHEMA mine"}) {
                SQLException e = assertThrows(SQLException.class, () -> st.execute(sql), sql);
                assertEquals("42501", e.getSQLState(), sql + ": " + e.getMessage());
            }
        }
        assertEquals(0, provision(SAMPLES_PASSWORD).getExitCode());
        refused("kilnmark");
    }

    @Test
    @Order(4)
    void aDatabaseARestoreMadeIsClosedAgainAtTheNextStart() throws Exception {
        // restore.sh makes kilnmark_restoring with createdb and renames it to kilnmark: PostgreSQL
        // opens a new database to everyone.
        try (Connection c = superuser("postgres")) {
            exec(c, "CREATE DATABASE kilnmark_restoring");
        }
        samples("kilnmark_restoring").close(); // the hole, before samples-db runs again
        assertEquals(0, provision(SAMPLES_PASSWORD).getExitCode());
        refused("kilnmark_restoring");
    }
}

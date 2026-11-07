package io.kilnmark.server.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
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
import org.testcontainers.utility.MountableFile;

/**
 * The Starter Pack's database (ADR 0051), against the real kilnmark-postgres image and this tree's
 * kilnmark-samples-db.sh, which the samples-db service runs at every start of a Starter Pack
 * install, only once Kilnmark's own record says starter-pack: the login kilnmark_samples owns
 * kilnmark_samples, reached over TLS, which is closed to everyone else when it is made, once;
 * pg_hba.conf (kilnmark-hba.sh) refuses that login every other database, and grants alone would let
 * it read or make nothing in Kilnmark's; and the script changes no other database's grants, nor, at a
 * later start, the samples database's.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SamplesDatabaseIsolationTest {

    static final String SUPER_PASSWORD = "super-0123456789-abcdef";
    static final String SAMPLES_PASSWORD = "samples-0123456789-abcdef";

    /** The database image: kilnmark-postgres:dev, as CI builds it, or KILNMARK_TEST_POSTGRES_IMAGE. */
    static final String IMAGE = System.getenv().getOrDefault("KILNMARK_TEST_POSTGRES_IMAGE", "kilnmark-postgres:dev");

    /**
     * The script under test: this tree's, copied in, so a kilnmark-postgres:dev that other work on the
     * same machine has tagged meanwhile (CI's runners share one engine) cannot stand in for it. The
     * release's image carries the same file (containers/postgres/Containerfile).
     */
    static final String SCRIPT = "/tmp/kilnmark-samples-db.sh";

    /** This tree's pg_hba.conf keeper, copied in too, and run as the image's start runs it. */
    static final String HBA = "/tmp/kilnmark-hba.sh";

    @Container
    static final GenericContainer<?> PG = new GenericContainer<>(DockerImageName.parse(IMAGE))
        .withCopyFileToContainer(MountableFile.forHostPath(Path.of("../containers/postgres/kilnmark-samples-db.sh")
            .toAbsolutePath().normalize()), SCRIPT)
        .withCopyFileToContainer(MountableFile.forHostPath(Path.of("../containers/postgres/kilnmark-hba.sh")
            .toAbsolutePath().normalize()), HBA)
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

    /**
     * Runs the script as samples-db does: the superuser over TLS, the passwords read from a settings
     * file mounted as config/.env is, not the environment; waiting a few seconds at most for Kilnmark's
     * recorded mode.
     */
    private static ExecResult provision(String samplesPassword) throws Exception {
        PG.copyFileToContainer(org.testcontainers.images.builder.Transferable.of("KILNMARK_DB_PASSWORD=" + SUPER_PASSWORD
            + "\r\nKILNMARK_SAMPLES_DB_PASSWORD=" + samplesPassword + "\r\n"), "/run/kilnmark/env");
        return PG.execInContainer("sh", "-c", "PGHOST=127.0.0.1 PGUSER=kilnmark PGSSLMODE=require KILNMARK_SAMPLES_DB_WAIT=4 sh "
            + SCRIPT);
    }

    /** What Kilnmark recorded at its first start, as its migrations and ModeCheck leave it; null: nothing. */
    private static void recordMode(String mode) throws SQLException {
        try (Connection c = superuser("kilnmark")) {
            exec(c, "CREATE SCHEMA IF NOT EXISTS kilnmark", "CREATE TABLE IF NOT EXISTS kilnmark.server_setting "
                + "(key text PRIMARY KEY, value text NOT NULL, updated_by text)", "DELETE FROM kilnmark.server_setting");
            if (mode != null) {
                try (java.sql.PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO kilnmark.server_setting VALUES ('deployment_mode', ?, 'test')")) {
                    ps.setString(1, mode);
                    ps.executeUpdate();
                }
            }
        }
    }

    /** "pg_hba.conf rejects connection": refused before any grant is looked at. */
    private static void rejected(String user, String password, String database, String sslmode) {
        Properties p = new Properties();
        p.setProperty("user", user);
        p.setProperty("password", password);
        SQLException e = assertThrows(SQLException.class, () -> DriverManager.getConnection("jdbc:postgresql://" + PG.getHost()
            + ":" + PG.getMappedPort(5432) + "/" + database + "?sslmode=" + sslmode, p).close(), database);
        assertEquals("28000", e.getSQLState(), database + ": " + e.getMessage());
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

    /** "permission denied for database": this login refused for want of CONNECT. */
    private static void refused(String user, String password, String database) {
        SQLException e = assertThrows(SQLException.class, () -> as(user, password, database).close(), database);
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

    /** Each database's grants, by name: what the script must leave as it was. */
    private static java.util.Map<String, String> grants() throws SQLException {
        java.util.Map<String, String> out = new java.util.TreeMap<>();
        try (Connection c = superuser("postgres"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT datname, coalesce(datacl::text, '') FROM pg_database")) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getString(2));
            }
        }
        return out;
    }

    @Test
    @Order(2)
    void onlyWhereKilnmarksOwnRecordSaysStarterPack() throws Exception {
        // .env alone never decides: nothing recorded yet (waits, then makes nothing), or production
        for (String mode : new String[] {null, "production", "Starter-Pack"}) {
            recordMode(mode);
            ExecResult r = provision(SAMPLES_PASSWORD);
            assertEquals(1, r.getExitCode(), mode + ": " + r.getStdout());
            assertTrue(r.getStderr().contains(mode == null ? "has not recorded its deployment mode" : "not a Starter Pack install"),
                r.getStderr());
            assertFalse(exists("SELECT 1 FROM pg_database WHERE datname = 'kilnmark_samples'"), mode + ": nothing made");
        }
        recordMode("starter-pack");
    }

    @Test
    @Order(3)
    void theSamplesLoginOwnsItsDatabaseClosedToOthersAndReadsNothingOfKilnmarks() throws Exception {
        // Kilnmark's own tables, as its migrations make them: a schema of its own, owned by kilnmark.
        try (Connection c = superuser("kilnmark")) {
            exec(c, "CREATE TABLE kilnmark.api_token (id int, hash text)",
                "INSERT INTO kilnmark.api_token VALUES (1, 'secret')", "CREATE TABLE public.notes (t text)");
        }
        java.util.Map<String, String> before = grants();
        for (int run = 0; run < 2; run++) { // twice: the second run changes nothing and fails nothing
            ExecResult r = provision(SAMPLES_PASSWORD);
            assertEquals(0, r.getExitCode(), r.getStderr());
        }
        java.util.Map<String, String> after = grants();
        String samplesAcl = after.remove("kilnmark_samples");
        assertEquals(before, after, "no other database's grants changed");
        // an entry for PUBLIC has no name before its "="
        assertFalse(java.util.regex.Pattern.compile("[{,]=[A-Za-z*]*c").matcher(samplesAcl).find(),
            "PUBLIC may not connect to it: " + samplesAcl);
        try (Connection c = superuser("postgres")) {
            exec(c, "CREATE ROLE someone_else LOGIN PASSWORD 'someone-0123456789'");
        }
        refused("someone_else", "someone-0123456789", "kilnmark_samples");
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
        assertTrue(exists("SELECT 1 FROM pg_database WHERE datname = 'kilnmark_samples' AND datdba = "
            + "(SELECT oid FROM pg_roles WHERE rolname = 'kilnmark_samples')"), "owned by its login");
        assertTrue(exists("SELECT 1 FROM pg_database WHERE datname = 'kilnmark' AND datdba = "
            + "(SELECT oid FROM pg_roles WHERE rolname = 'kilnmark')"), "Kilnmark's own database is still Kilnmark's");
        // In Kilnmark's own database, were it let in: it reads and makes nothing (the grants alone). An
        // image from this tree refuses it there already, at its start (pg_hba.conf, below).
        Connection in;
        try {
            in = samples("kilnmark");
        } catch (SQLException e) {
            assertEquals("28000", e.getSQLState(), e.getMessage());
            return;
        }
        try (Connection c = in; Statement st = c.createStatement()) {
            for (String sql : new String[] {"SELECT * FROM kilnmark.api_token", "SELECT * FROM public.notes",
                "CREATE TABLE public.mine (id int)", "CREATE SCHEMA mine", "CREATE TABLE kilnmark.mine (id int)"}) {
                SQLException e = assertThrows(SQLException.class, () -> st.execute(sql), sql);
                assertEquals("42501", e.getSQLState(), sql + ": " + e.getMessage());
            }
        }
    }

    @Test
    @Order(4)
    void pgHbaLetsTheSamplesLoginReachItsOwnDatabaseOnlyOverTls() throws Exception {
        // as the image's start runs it, twice: the second changes nothing
        for (int run = 0; run < 2; run++) {
            ExecResult r = PG.execInContainer("sh", "-c", "sh " + HBA + " \"$PGDATA/pg_hba.conf\"");
            assertEquals(0, r.getExitCode(), r.getStderr());
        }
        ExecResult head = PG.execInContainer("sh", "-c", "grep -c 'kilnmark-samples: begin' \"$PGDATA/pg_hba.conf\"");
        assertEquals("1", head.getStdout().strip(), "the lines once");
        try (Connection c = superuser("postgres")) {
            exec(c, "SELECT pg_reload_conf()");
        }
        Thread.sleep(500);
        for (String database : new String[] {"kilnmark", "postgres", "template1"}) {
            rejected("kilnmark_samples", SAMPLES_PASSWORD, database, "require");
        }
        rejected("kilnmark_samples", SAMPLES_PASSWORD, "kilnmark_samples", "disable");
        samples("kilnmark_samples").close();
        superuser("kilnmark").close();   // everyone else as before
    }

    @Test
    @Order(5)
    void aLaterStartLeavesTheSamplesDatabasesGrantsAsTheSiteSetThem() throws Exception {
        // the samples database is closed once, when it is made: a grant an admin adds since is theirs
        try (Connection c = superuser("postgres")) {
            exec(c, "CREATE ROLE reporting LOGIN", "GRANT CONNECT ON DATABASE kilnmark_samples TO reporting");
        }
        java.util.Map<String, String> before = grants();
        assertEquals(0, provision(SAMPLES_PASSWORD).getExitCode());
        assertEquals(before, grants(), "nothing revoked or granted again");
        assertTrue(before.get("kilnmark_samples").contains("reporting=c"), before.toString());
    }
}

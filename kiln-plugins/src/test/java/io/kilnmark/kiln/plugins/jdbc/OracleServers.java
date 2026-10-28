package io.kilnmark.kiln.plugins.jdbc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import io.kilnmark.kiln.connect.ConnectorContext;
import io.kilnmark.kiln.connect.DriverFolder;
import io.kilnmark.kiln.connect.SourceConfig;

/**
 * The Oracle Database Free server the integration tests run against, pinned, started on first use
 * and stopped by Testcontainers when the JVM ends; and a drivers folder holding Oracle's JDBC driver
 * as a customer would install it: the jar on the test class path, copied in and verified against
 * the checksum {@link OracleConnector#DRIVER} pins. The connector reaches the driver only through
 * that folder.
 */
final class OracleServers {

    /** Oracle Database Free 23ai, slim, with its database created at build time (fast start). */
    static final String IMAGE = "docker.io/gvenzl/oracle-free:23.26.3-slim-faststart";

    static final String SYSTEM_PASSWORD = "Kt-system-2026";
    static final String SERVICE = "FREEPDB1";

    private static Server server;

    private OracleServers() {
    }

    /**
     * A server, a connector using the drivers folder, and how to reach the server as SYSTEM.
     */
    record Server(OracleConnector connector, DriverFolder drivers, String host, int port) {

        /** A connection as SYSTEM, in auto-commit. */
        Connection connect() throws SQLException {
            Properties p = new Properties();
            p.setProperty("user", "SYSTEM");
            p.setProperty("password", SYSTEM_PASSWORD);
            return connector.connect(OracleConnector.url(host, Integer.toString(port), SERVICE, false), p, "accepted");
        }

        /** Runs statements as SYSTEM. */
        void sql(String... statements) throws SQLException {
            try (Connection c = connect(); Statement s = c.createStatement()) {
                for (String st : statements) {
                    s.execute(st);
                }
            }
        }

        /** Runs statements as SYSTEM, ignoring failures (dropping what may not exist). */
        void quietly(String... statements) {
            for (String st : statements) {
                try {
                    sql(st);
                } catch (SQLException e) {
                    // not there
                }
            }
        }

        /** A source on this server as {@code user}, with further settings. */
        SourceConfig source(String name, String user, String password, Map<String, String> more) {
            Map<String, String> settings = new HashMap<>(Map.of("host", host, "port", Integer.toString(port),
                "service_name", SERVICE, "username", user));
            settings.putAll(more);
            return new SourceConfig(name, "oracle", settings, password);
        }

        /** A source on this server as SYSTEM. */
        SourceConfig source(String name, Map<String, String> more) {
            return source(name, "SYSTEM", SYSTEM_PASSWORD, more);
        }
    }

    static synchronized Server oracle() {
        if (server == null) {
            DriverFolder drivers = driversFolder();
            OracleConnector connector = (OracleConnector) new OracleConnector()
                .withContext(new ConnectorContext(Map.of(), drivers));
            server = start(connector, drivers);
        }
        return server;
    }

    /** A drivers folder holding the ojdbc jar from the test class path, as Maven Central publishes it. */
    static DriverFolder driversFolder() {
        try {
            Path jar = Path.of(Class.forName(OracleConnector.DRIVER.driverClass()).getProtectionDomain().getCodeSource()
                .getLocation().toURI());
            Path dir = Files.createDirectories(Files.createTempDirectory("kilnmark-test").resolve("drivers"));
            Files.copy(jar, dir.resolve(OracleConnector.DRIVER.fileName()));
            return new DriverFolder(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ClassNotFoundException | URISyntaxException e) {
            throw new IllegalStateException("The tests need ojdbc11 on their class path", e);
        }
    }

    @SuppressWarnings("resource") // stopped by Testcontainers when the JVM ends
    private static Server start(OracleConnector connector, DriverFolder drivers) {
        GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse(IMAGE))
            .withEnv("ORACLE_PASSWORD", SYSTEM_PASSWORD)
            .withExposedPorts(1521)
            .waitingFor(Wait.forLogMessage(".*DATABASE IS READY TO USE!.*", 1).withStartupTimeout(Duration.ofMinutes(6)));
        c.start();
        return new Server(connector, drivers, c.getHost(), c.getMappedPort(1521));
    }
}

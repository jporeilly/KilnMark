package io.kilnmark.kiln.plugins.jdbc;

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

import io.kilnmark.kiln.connect.SourceConfig;

/**
 * The MySQL 8.4 LTS and MariaDB 11.4 LTS servers the integration tests run against, pinned, one
 * of each per test run (started on first use, stopped by Testcontainers when the JVM ends). Plain
 * containers rather than Testcontainers' MySQL module, which would need MySQL's own (GPL) driver:
 * every connection goes through MariaDB Connector/J, as Kilnmark's do.
 */
final class MySqlServers {

    /** MySQL 8.4 LTS. */
    static final String MYSQL_IMAGE = "docker.io/library/mysql:8.4.10";
    /** MariaDB 11.4 LTS. */
    static final String MARIADB_IMAGE = "docker.io/library/mariadb:11.4.12";

    static final String ROOT_PASSWORD = "Root-pw-2026";

    private static Server mysql;
    private static Server mariadb;

    private MySqlServers() {
    }

    /**
     * A server and how to reach it as an administrator.
     *
     * @param connector the connector of its kind
     * @param admin     a user with every privilege, and grant option
     */
    record Server(MySqlConnector connector, String host, int port, String admin, String adminPassword) {

        String kind() {
            return connector.kind();
        }

        boolean mariadb() {
            return connector instanceof MariaDbConnector;
        }

        /** A connection as the administrator, in auto-commit. */
        Connection connect() throws SQLException {
            Properties p = new Properties();
            p.setProperty("user", admin);
            p.setProperty("password", adminPassword);
            return MySqlConnector.connect(mariadb() ? MySqlConnector.Product.MARIADB : MySqlConnector.Product.MYSQL,
                "jdbc:mariadb://" + host + ":" + port + "/", p, "preferred", null);
        }

        /** Runs statements as the administrator. */
        void sql(String... statements) throws SQLException {
            try (Connection c = connect(); Statement s = c.createStatement()) {
                for (String st : statements) {
                    s.execute(st);
                }
            }
        }

        /** A source on this server as {@code user}, with further settings. */
        SourceConfig source(String name, String user, String password, Map<String, String> more) {
            Map<String, String> settings = new HashMap<>(Map.of("host", host, "port", Integer.toString(port),
                "username", user));
            settings.putAll(more);
            return new SourceConfig(name, kind(), settings, password);
        }

        /** A source on this server as the administrator. */
        SourceConfig source(String name, Map<String, String> more) {
            return source(name, admin, adminPassword, more);
        }
    }

    static synchronized Server mysql() {
        if (mysql == null) {
            mysql = start(new MySqlConnector(), MYSQL_IMAGE, "MYSQL_ROOT_PASSWORD");
        }
        return mysql;
    }

    static synchronized Server mariadb() {
        if (mariadb == null) {
            mariadb = start(new MariaDbConnector(), MARIADB_IMAGE, "MARIADB_ROOT_PASSWORD");
        }
        return mariadb;
    }

    /**
     * Starts a server with {@code local_infile} on (MySQL 8 has it off by default) and waits for
     * the real server on port 3306 (not MySQL's X plugin on 33060): both images first run a
     * temporary one, without networking, to initialise.
     */
    @SuppressWarnings("resource") // stopped by Testcontainers when the JVM ends
    private static Server start(MySqlConnector connector, String image, String passwordVariable) {
        GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse(image))
            .withEnv(passwordVariable, ROOT_PASSWORD)
            .withCommand("--local-infile=1", "--character-set-server=utf8mb4", "--max-connections=200")
            .withExposedPorts(3306)
            .waitingFor(Wait.forLogMessage(".*port: 3306\\s.*", 1).withStartupTimeout(Duration.ofMinutes(3)));
        c.start();
        return new Server(connector, c.getHost(), c.getMappedPort(3306), "root", ROOT_PASSWORD);
    }
}

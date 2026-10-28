package io.kilnmark.kiln.plugins.jdbc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import io.kilnmark.kiln.connect.ConnectorContext;
import io.kilnmark.kiln.connect.DriverFolder;
import io.kilnmark.kiln.connect.RegisteredDriver;
import io.kilnmark.kiln.connect.SourceConfig;

/**
 * H2 registered as an admin registers a customer's driver: the H2 jar on the test class path
 * (a test-only dependency, never shipped) copied into a fresh drivers folder, read and hashed by
 * {@link DriverFolder#inspect}, and registered as {@code h2}. Databases are in memory, one per
 * test class, created and filled through the registered driver itself.
 */
final class H2Drivers {

    static final String DRIVER_CLASS = "org.h2.Driver";

    /** A drivers folder with H2 registered, the connector using it, and an in-memory database's URL. */
    record H2(DriverFolder folder, RegisteredDriver registration, GenericJdbcConnector connector, String url) {

        /** A connection through the registered driver, to set up and check the database. */
        Connection connect() throws SQLException {
            Properties p = new Properties();
            p.setProperty("user", "sa");
            p.setProperty("password", "");
            return folder.driver(registration).connect(url, p);
        }

        void sql(String... statements) throws SQLException {
            try (Connection c = connect(); Statement s = c.createStatement()) {
                for (String sql : statements) {
                    s.execute(sql);
                }
            }
        }

        /** A source on this database, with {@code extra} settings. */
        SourceConfig source(String name, Map<String, String> extra) {
            Map<String, String> s = new java.util.HashMap<>(Map.of("driver", "h2", "url", url, "username", "sa"));
            s.putAll(extra);
            return new SourceConfig(name, "jdbc", s, "");
        }
    }

    private H2Drivers() {
    }

    /** The H2 jar on the test class path. */
    static Path jar() throws Exception {
        return Path.of(Class.forName(DRIVER_CLASS).getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    /** A fresh drivers folder holding the H2 jar, registered with URL prefix {@code prefix}. */
    static H2 registered(String prefix) throws Exception {
        Path dir = Files.createDirectories(Files.createTempDirectory("kilnmark-jdbc-test").resolve("drivers"));
        Path jar = jar();
        String fileName = jar.getFileName().toString();
        Files.copy(jar, dir.resolve(fileName));
        DriverFolder folder = new DriverFolder(dir);
        DriverFolder.Inspected i = folder.inspect(fileName, DRIVER_CLASS);
        RegisteredDriver r = new RegisteredDriver("h2", fileName, i.sha256(), DRIVER_CLASS, prefix, "admin@example.com",
            Instant.parse("2026-10-28T09:00:00Z"));
        folder.register(r);
        GenericJdbcConnector connector = (GenericJdbcConnector) new GenericJdbcConnector()
            .withContext(new ConnectorContext(Map.of(), folder));
        String url = "jdbc:h2:mem:kt_" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
        return new H2(folder, r, connector, url);
    }

    static H2 registered() throws Exception {
        return registered("jdbc:h2:");
    }
}

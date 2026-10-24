package io.kilnmark.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/** V5 on a database that already has sources: migrate a fresh database to V4, add sources, then migrate on. */
@QuarkusTest
class MigrationV5Test {

    @Inject
    AgroalDataSource db;

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String url;

    @ConfigProperty(name = "quarkus.datasource.username")
    String user;

    @ConfigProperty(name = "quarkus.datasource.password")
    String password;

    @Test
    void existingSourcesGetTheirNameAsDisplayName() throws Exception {
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS v5_upgrade");
            s.execute("CREATE DATABASE v5_upgrade");
        }
        String other = url.replaceFirst("/[^/?]+(\\?|$)", "/v5_upgrade$1");
        try {
            Flyway.configure().dataSource(other, user, password).locations("classpath:db/migration")
                .schemas("kilnmark").defaultSchema("kilnmark").target("4").load().migrate();
            try (Connection c = DriverManager.getConnection(other, user, password); Statement s = c.createStatement()) {
                s.execute("INSERT INTO kilnmark.data_source (name, kind, settings) VALUES "
                    + "('old-pg', 'postgresql', '{\"url\": \"jdbc:postgresql://h/db\"}'), "
                    + "('old-files', 'files', '{\"path\": \"/srv/files\"}')");
            }
            var result = Flyway.configure().dataSource(other, user, password).locations("classpath:db/migration")
                .schemas("kilnmark").defaultSchema("kilnmark").load().migrate();
            assertTrue(result.migrationsExecuted >= 1);
            try (Connection c = DriverManager.getConnection(other, user, password); Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT name, display_name FROM kilnmark.data_source ORDER BY name")) {
                rs.next();
                assertEquals("old-files", rs.getString(2));
                rs.next();
                assertEquals("old-pg", rs.getString(2));
            }
            try (Connection c = DriverManager.getConnection(other, user, password); Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT value::int FROM kilnmark.install_info WHERE key = 'schema_catalog'")) {
                rs.next();
                assertTrue(rs.getInt(1) >= 5);
            }
        } finally {
            try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
                s.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = 'v5_upgrade'");
                s.execute("DROP DATABASE IF EXISTS v5_upgrade");
            }
        }
    }
}

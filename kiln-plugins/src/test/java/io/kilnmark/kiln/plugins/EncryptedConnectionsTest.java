package io.kilnmark.kiln.plugins;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.plugins.files.FilesConnector;
import io.kilnmark.kiln.plugins.files.S3Connector;
import io.kilnmark.kiln.plugins.files.SampleDataConnector;
import io.kilnmark.kiln.plugins.jdbc.GenericJdbcConnector;
import io.kilnmark.kiln.plugins.jdbc.MariaDbConnector;
import io.kilnmark.kiln.plugins.jdbc.MySqlConnector;
import io.kilnmark.kiln.plugins.jdbc.OracleConnector;
import io.kilnmark.kiln.plugins.jdbc.PostgresConnector;
import io.kilnmark.kiln.plugins.jdbc.SqlServerConnector;

/**
 * Each connector says whether a source's connections could go unencrypted (ADR 0034), as servers
 * that require TLS to sources ask before saving a source and before every connection.
 */
class EncryptedConnectionsTest {

    private static SourceConfig source(String kind, Map<String, String> settings) {
        return new SourceConfig("s", kind, settings, null);
    }

    private static void encrypted(Connector c, Map<String, String> settings) {
        assertNull(c.unencryptedReason(source(c.kind(), settings)), c.kind() + " " + settings);
    }

    private static void mayBePlain(Connector c, Map<String, String> settings, String names) {
        String why = c.unencryptedReason(source(c.kind(), settings));
        assertNotNull(why, c.kind() + " " + settings + " should be refused");
        assertTrue(why.startsWith(names), "names the setting to change: " + why);
        assertTrue(!why.contains("db.example.com"), "never echoes the host: " + why);
    }

    /** As the PostgreSQL driver itself reads the URL: only require, verify-ca and verify-full (or ssl=true) always encrypt. */
    @Test
    void postgresqlByTheDriversOwnReadingOfSslmode() {
        PostgresConnector pg = new PostgresConnector();
        for (String ok : List.of("?sslmode=require", "?sslmode=verify-full", "?sslmode=verify-ca", "?ssl=true",
            "?ApplicationName=k&sslmode=require")) {
            encrypted(pg, Map.of("url", "jdbc:postgresql://db.example.com:5432/sales" + ok));
        }
        for (String bad : List.of("", "?sslmode=prefer", "?sslmode=allow", "?sslmode=disable", "?SSLMODE=require",
            "?ssl%6dode=require", "?sslmode=require&sslmode=prefer")) {
            mayBePlain(pg, Map.of("url", "jdbc:postgresql://db.example.com:5432/sales" + bad), "url:");
        }
    }

    /** The driver encrypts by default; encrypt=false or optional, however written, is refused. */
    @Test
    void sqlServerByTheDriversOwnReadingOfEncrypt() {
        SqlServerConnector sql = new SqlServerConnector();
        for (String ok : List.of("", ";encrypt=true", ";encrypt=strict", ";encrypt=mandatory",
            ";encrypt=true;trustServerCertificate=true")) {
            encrypted(sql, Map.of("url", "jdbc:sqlserver://db.example.com:1433;databaseName=sales" + ok));
        }
        encrypted(sql, Map.of("url", "jdbc:sqlserver://db.example.com:1433;databaseName=sales",
            "trust_server_certificate", "true"));
        for (String bad : List.of(";encrypt=false", ";encrypt=optional", ";ENCRYPT=false", ";encrypt={false}",
            ";encrypt=no")) {
            mayBePlain(sql, Map.of("url", "jdbc:sqlserver://db.example.com:1433;databaseName=sales" + bad), "url:");
        }
    }

    @Test
    void mysqlAndMariaDbByTlsMode() {
        for (Connector c : List.<Connector>of(new MySqlConnector(), new MariaDbConnector())) {
            for (String ok : List.of("required", "verify-ca", "verify-full", "REQUIRED")) {
                encrypted(c, Map.of("host", "db.example.com", "tls_mode", ok));
            }
            mayBePlain(c, Map.of("host", "db.example.com"), "tls_mode:");
            mayBePlain(c, Map.of("host", "db.example.com", "tls_mode", "preferred"), "tls_mode:");
            mayBePlain(c, Map.of("host", "db.example.com", "tls_mode", "disabled"), "tls_mode:");
        }
    }

    @Test
    void oracleByEncryption() {
        OracleConnector o = new OracleConnector();
        encrypted(o, Map.of("host", "db.example.com", "service_name", "sales", "encryption", "tls"));
        encrypted(o, Map.of("host", "db.example.com", "service_name", "sales", "encryption", "required"));
        mayBePlain(o, Map.of("host", "db.example.com", "service_name", "sales"), "encryption:");
        mayBePlain(o, Map.of("host", "db.example.com", "service_name", "sales", "encryption", "accepted"), "encryption:");
    }

    @Test
    void s3OverHttpsOnly() {
        S3Connector s3 = new S3Connector();
        encrypted(s3, Map.of("bucket", "b"));
        encrypted(s3, Map.of("bucket", "b", "endpoint", "https://storage.example.com:9000"));
        encrypted(s3, Map.of("bucket", "b", "endpoint", "HTTPS://storage.example.com"));
        mayBePlain(s3, Map.of("bucket", "b", "endpoint", "http://storage.example.com:9000"), "endpoint:");
    }

    /** Local folders and the bundled sample reach no network; Generic JDBC cannot be judged, so it is refused. */
    @Test
    void noNetworkIsFineAndUnknownDriversAreRefused() {
        encrypted(new FilesConnector(), Map.of("root", "/srv/files"));
        encrypted(new SampleDataConnector(), Map.of());
        String why = new GenericJdbcConnector().unencryptedReason(source("jdbc",
            Map.of("url", "jdbc:db2://db.example.com:50000/SALES", "properties", "sslConnection=true")));
        assertNotNull(why, "a driver Kilnmark does not know cannot be vouched for");
    }
}

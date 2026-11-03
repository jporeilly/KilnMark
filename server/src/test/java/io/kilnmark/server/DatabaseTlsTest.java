package io.kilnmark.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Kilnmark's own database is reached over TLS (ADR 0032). Every test run starts with the check on
 * (the test database has TLS, and its sslmode is require), so a start that got this far passed it;
 * here, what it accepts and refuses.
 */
@QuarkusTest
class DatabaseTlsTest {

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String url;

    @ConfigProperty(name = "quarkus.datasource.username")
    String user;

    @ConfigProperty(name = "quarkus.datasource.password")
    String password;

    /** Accepted exactly when the PostgreSQL driver itself would demand TLS (its own parser decides). */
    @Test
    void onlySettingsUnderWhichTheDriverDemandsTlsAreAccepted() {
        for (String ok : java.util.List.of("jdbc:postgresql://db:5432/kilnmark?sslmode=require",
            "jdbc:postgresql://db/kilnmark?sslmode=verify-full&sslrootcert=/etc/ca.pem",
            "jdbc:postgresql://db/kilnmark?ApplicationName=k&sslmode=verify-ca",
            // ssl=true alone is verify-full to the driver
            "jdbc:postgresql://db/kilnmark?ssl=true")) {
            assertNull(DatabaseTls.settingProblem(ok, null), ok);
        }
        for (String bad : java.util.List.of("jdbc:postgresql://db:5432/kilnmark",
            "jdbc:postgresql://db/kilnmark?sslmode=prefer", "jdbc:postgresql://db/kilnmark?sslmode=allow",
            "jdbc:postgresql://db/kilnmark?sslmode=disable",
            // the driver matches the key exactly and does not decode it: these leave it on prefer
            "jdbc:postgresql://db/kilnmark?SSLMODE=require", "jdbc:postgresql://db/kilnmark?ApplicationName=k&SSLMODE=Verify-CA",
            "jdbc:postgresql://db/kilnmark?ssl%6dode=require")) {
            String why = DatabaseTls.settingProblem(bad, null);
            assertTrue(why != null && why.contains("does not demand TLS"), bad + " gave " + why);
            assertFalse(why.contains("db:5432") || why.contains("//db"), "never echoes the URL: " + why);
        }
        // The driver property counts where the URL says nothing, and the URL wins where it does
        assertNull(DatabaseTls.settingProblem("jdbc:postgresql://db/kilnmark", "require"));
        assertTrue(DatabaseTls.settingProblem("jdbc:postgresql://db/kilnmark?sslmode=prefer", "require") != null,
            "the URL's prefer wins over the property's require, as in the driver");
        assertTrue(DatabaseTls.settingProblem("jdbc:mysql://db/kilnmark?sslMode=REQUIRED", null) != null);
    }

    @Test
    void theServerSaysWhetherASessionIsEncrypted() throws Exception {
        assertTrue(encrypted("require"), "the test database speaks TLS");
        assertFalse(encrypted("disable"), "and a session without it is seen as such");
    }

    private boolean encrypted(String sslmode) throws Exception {
        Properties p = new Properties();
        p.setProperty("user", user);
        p.setProperty("password", password);
        p.setProperty("sslmode", sslmode);
        try (Connection c = DriverManager.getConnection(url, p)) {
            return DatabaseTls.encrypted(c);
        }
    }
}

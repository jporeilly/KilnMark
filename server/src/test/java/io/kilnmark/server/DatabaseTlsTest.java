package io.kilnmark.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void onlySettingsThatDemandTlsAreAccepted() {
        for (String ok : java.util.List.of("jdbc:postgresql://db:5432/kilnmark?sslmode=require",
            "jdbc:postgresql://db/kilnmark?sslmode=verify-full&sslrootcert=/etc/ca.pem",
            "jdbc:postgresql://db/kilnmark?ApplicationName=k&SSLMODE=Verify-CA")) {
            assertNull(DatabaseTls.settingProblem(ok, null), ok);
        }
        for (String bad : java.util.List.of("jdbc:postgresql://db:5432/kilnmark",
            "jdbc:postgresql://db/kilnmark?sslmode=prefer", "jdbc:postgresql://db/kilnmark?sslmode=allow",
            "jdbc:postgresql://db/kilnmark?sslmode=disable",
            // the driver reads the last one
            "jdbc:postgresql://db/kilnmark?sslmode=require&sslmode=disable")) {
            String why = DatabaseTls.settingProblem(bad, null);
            assertTrue(why != null && why.contains("does not demand TLS"), bad);
            assertFalse(why.contains("db:5432") || why.contains("//db"), "never echoes the URL: " + why);
        }
        // A driver property wins over the URL, as the driver reads it last
        assertNull(DatabaseTls.settingProblem("jdbc:postgresql://db/kilnmark", "require"));
        assertTrue(DatabaseTls.settingProblem("jdbc:postgresql://db/kilnmark?sslmode=require", "prefer") != null);
        assertEquals("verify-full", DatabaseTls.urlParameter("jdbc:x://h/d?a=1&sslMode=verify-full", "sslmode"));
        assertNull(DatabaseTls.urlParameter("jdbc:x://h/d", "sslmode"));
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

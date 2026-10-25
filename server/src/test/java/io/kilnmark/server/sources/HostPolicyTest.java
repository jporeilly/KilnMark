package io.kilnmark.server.sources;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.plugins.files.S3Connector;
import io.kilnmark.kiln.plugins.jdbc.PostgresConnector;
import io.kilnmark.kiln.plugins.jdbc.SqlServerConnector;

class HostPolicyTest {

    private final HostPolicy policy = new HostPolicy(List.of("*.corp.example.com", "10.0.4.12", "[::1]"));

    @Test
    void allowsListedHostsAndDomains() {
        assertTrue(policy.allows("db.corp.example.com"));
        assertTrue(policy.allows("DB.Corp.Example.com"));
        assertTrue(policy.allows("10.0.4.12"));
        assertTrue(policy.allows("[::1]"));
        assertFalse(policy.allows("corp.example.com.evil.net"));
        assertFalse(policy.allows("10.0.4.13"));
        assertTrue(new HostPolicy(List.of()).allows("anything"), "no list allows any host");
    }

    @Test
    void checksEveryHostASourceReaches() {
        PostgresConnector pg = new PostgresConnector();
        policy.check(pg, new SourceConfig("s", "postgresql", Map.of("url", "jdbc:postgresql://db.corp.example.com/x"),
            null));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> policy.check(pg,
            new SourceConfig("s", "postgresql", Map.of("url", "jdbc:postgresql://db.corp.example.com,evil.net/x"), null)));
        assertTrue(e.getMessage().contains("evil.net") && e.getMessage().startsWith("url:"), e.getMessage());
        SqlServerConnector sql = new SqlServerConnector();
        assertThrows(IllegalArgumentException.class, () -> policy.check(sql, new SourceConfig("s", "sqlserver",
            Map.of("url", "jdbc:sqlserver://db.corp.example.com;serverName=evil.net"), null)));
        S3Connector s3 = new S3Connector();
        assertThrows(IllegalArgumentException.class, () -> policy.check(s3, new SourceConfig("s", "s3",
            Map.of("bucket", "b"), null)), "AWS itself must be listed too");
        policy.check(s3, new SourceConfig("s", "s3", Map.of("bucket", "b", "endpoint", "http://s3.corp.example.com"),
            null));
    }

    @Test
    void aSavedSourceIsCheckedAgainstTheCurrentListBeforeEveryConnection() {
        // Saved while the list allowed it; the list has since been narrowed.
        SourceConfig saved = new SourceConfig("s", "postgresql", Map.of("url", "jdbc:postgresql://old.example.net/x"),
            null);
        PostgresConnector pg = new PostgresConnector();
        assertNull(new HostPolicy(List.of()).refusal(pg, saved));
        String why = policy.refusal(pg, saved);
        assertTrue(why != null && why.contains("old.example.net") && why.contains(HostPolicy.SETTING), why);
        assertFalse(why.startsWith("url:"), "said in plain words for a job, not as a form field: " + why);
        assertNull(policy.refusal(pg, new SourceConfig("s", "postgresql",
            Map.of("url", "jdbc:postgresql://db.corp.example.com/x"), null)));
    }

    @Test
    void testsPreviewsAndListingsStopBeforeConnectingToAHostNoLongerAllowed() throws Exception {
        AtomicBoolean called = new AtomicBoolean();
        SourceChecks checks = new SourceChecks(Duration.ofSeconds(5), 2, policy);
        SourceConfig saved = new SourceConfig("s", "postgresql", Map.of("url", "jdbc:postgresql://old.example.net/x"),
            null);
        SourceChecks.TestResult r = checks.test(new PostgresConnector(), saved);
        assertFalse(r.ok());
        assertTrue(r.problem().contains("old.example.net"), r.problem());
        SourceChecks.CheckFailed e = assertThrows(SourceChecks.CheckFailed.class, () -> checks.forPerson(new PostgresConnector(),
            saved, "preview a table", () -> {
                called.set(true);
                return null;
            }));
        assertTrue(e.getMessage().contains(HostPolicy.SETTING) && !e.busy(), e.getMessage());
        assertFalse(called.get(), "the work never ran");
        assertThrows(SourceChecks.CheckFailed.class, () -> checks.containers(new PostgresConnector(), saved));
        assertFalse(checks.testInBackground(new PostgresConnector(), saved).ok());
    }
}

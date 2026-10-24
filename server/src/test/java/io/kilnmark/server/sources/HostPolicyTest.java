package io.kilnmark.server.sources;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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
}

package io.kilnmark.server.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class HostRemapTest {

    private final HostRemap map = HostRemap.parse(List.of("localhost=host.docker.internal",
        "127.0.0.1=host.docker.internal", "[::1]=host.docker.internal"));

    @Test
    void replacesTheHostOfJdbcAndHttpUrls() {
        assertEquals("jdbc:postgresql://host.docker.internal:5433/lab",
            map.apply("jdbc:postgresql://localhost:5433/lab"));
        assertEquals("jdbc:sqlserver://host.docker.internal:1433;databaseName=sales",
            map.apply("jdbc:sqlserver://LOCALHOST:1433;databaseName=sales"), "host names ignore case");
        assertEquals("jdbc:sqlserver://host.docker.internal\\SQLEXPRESS;databaseName=x",
            map.apply("jdbc:sqlserver://127.0.0.1\\SQLEXPRESS;databaseName=x"));
        assertEquals("http://host.docker.internal:9000", map.apply("http://localhost:9000"));
        assertEquals("jdbc:postgresql://host.docker.internal/db", map.apply("jdbc:postgresql://localhost/db"));
        assertEquals("jdbc:postgresql://host.docker.internal:5432/db", map.apply("jdbc:postgresql://[::1]:5432/db"));
    }

    @Test
    void leavesOtherHostsAndTextAlone() {
        assertEquals("jdbc:postgresql://mylocalhost:5432/localhost", map.apply("jdbc:postgresql://mylocalhost:5432/localhost"));
        assertEquals("jdbc:postgresql://localhost.example.com/db", map.apply("jdbc:postgresql://localhost.example.com/db"));
        assertEquals("localhost", map.apply("localhost"), "only URL hosts, not any text");
        assertEquals("jdbc:sqlserver://host.docker.internal;user=a@b;databaseName=x",
            map.apply("jdbc:sqlserver://localhost;user=a@b;databaseName=x"), "an @ after the host is not a user name");
        assertEquals("jdbc:sqlserver://example.com;user=a@localhost",
            map.apply("jdbc:sqlserver://example.com;user=a@localhost"));
        assertEquals("jdbc:postgresql://host.docker.internal:5432,db2:5432/x",
            map.apply("jdbc:postgresql://localhost:5432,db2:5432/x"), "every host of a list");
        assertEquals("http://u@host.docker.internal:9000", map.apply("http://u@localhost:9000"));
        assertEquals("db.internal", map.host("db.internal"));
        assertEquals("host.docker.internal", map.host("127.0.0.1"));
    }

    @Test
    void refusesPairsThatAreNotHostNames() {
        assertThrows(IllegalArgumentException.class, () -> HostRemap.parse(List.of("localhost")));
        assertThrows(IllegalArgumentException.class, () -> HostRemap.parse(List.of("localhost=evil;password=x")));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> HostRemap.parse(List.of("=x")));
        assertTrue(e.getMessage().contains(HostRemap.SETTING));
        assertTrue(HostRemap.parse(List.of()).isEmpty());
        assertTrue(HostRemap.validHost("lab_postgres"));
        assertFalse(HostRemap.validHost("a b"));
    }
}

package io.kilnmark.kiln.plugins.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.Connectors;
import io.kilnmark.kiln.connect.SourceConfig;

class ConnectorsTest {

    @Test
    void theRegistryFindsPostgresThroughServiceLoader() {
        Connector pg = Connectors.load().get("postgresql").orElseThrow();
        assertEquals("PostgreSQL", pg.displayName());
        assertEquals("url", pg.settings().get(0).key());
    }

    @Test
    void validationNamesTheSettingAtFault() {
        Connector pg = new PostgresConnector();
        assertTrue(pg.validate(new SourceConfig("x", "postgresql", Map.of("url", "jdbc:postgresql://h/db"), null))
            .isEmpty());
        assertTrue(pg.validate(new SourceConfig("x", "postgresql", Map.of("url", "jdbc:mysql://h/db"), null))
            .get(0).startsWith("url:"));
    }

    @Test
    void theSecretNeverAppearsInToString() {
        SourceConfig c = new SourceConfig("x", "postgresql", Map.of("url", "u"), "hunter2");
        assertTrue(!c.toString().contains("hunter2"));
    }
}

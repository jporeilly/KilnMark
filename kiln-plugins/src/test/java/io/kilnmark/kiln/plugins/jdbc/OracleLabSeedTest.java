package io.kilnmark.kiln.plugins.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.connect.CatalogInfo;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.StepSpec;
import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.plugins.write.Rows;

/**
 * The reference lab's Oracle data (lab/oracle/01-logistics.sql) loads on the server the tests pin
 * and holds what the lab's answer key says, read through the connector as the lab's reader. The
 * image runs the script with SQL*Plus as SYS; here it runs statement by statement as SYSTEM.
 */
@Tag("oracle")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OracleLabSeedTest {

    private OracleServers.Server db;
    private SourceConfig reader;
    private CatalogInfo catalog;

    @BeforeAll
    void seed() throws Exception {
        db = OracleServers.oracle();
        db.quietly("DROP USER freight CASCADE", "DROP USER fleet CASCADE", "DROP USER kiln_reader CASCADE",
            "DROP USER warehouse CASCADE");
        Path script = Path.of(System.getProperty("user.dir")).getParent().resolve("lab/oracle/01-logistics.sql");
        try (Connection c = db.connect(); Statement s = c.createStatement()) {
            for (String statement : statements(Files.readString(script))) {
                s.execute(statement);
            }
        }
        reader = db.source("lab", "KILN_READER", "Lab-Reader-2026", Map.of("schemas", "FLEET, FREIGHT"));
        catalog = db.connector().catalogWithRelationships(reader);
    }

    /**
     * The script's statements as JDBC runs them: SQL ending in {@code ;} at the end of a line, and
     * PL/SQL blocks ending in a line holding {@code /}. SQL*Plus commands and the switch to the
     * pluggable database (the test already connects to it) are left out.
     */
    static List<String> statements(String script) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean block = false;
        for (String line : script.split("\n")) {
            String t = line.strip();
            if (current.isEmpty() && (t.isEmpty() || t.startsWith("--") || t.startsWith("WHENEVER ")
                || t.startsWith("ALTER SESSION SET CONTAINER"))) {
                continue;
            }
            if (current.isEmpty() && (t.startsWith("BEGIN") || t.startsWith("DECLARE"))) {
                block = true;
            }
            if (block && t.equals("/")) {
                out.add(current.toString().strip());
                current.setLength(0);
                block = false;
                continue;
            }
            current.append(line).append('\n');
            if (!block && t.endsWith(";")) {
                String sql = current.toString().strip();
                out.add(sql.substring(0, sql.length() - 1));
                current.setLength(0);
            }
        }
        assertTrue(current.toString().isBlank(), "the script ends with a whole statement: " + current);
        return out;
    }

    private long count(String sql) throws Exception {
        try (Connection c = db.connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    @Test
    void everyTableIsThereForTheReader() {
        Map<String, String> kinds = new TreeMap<>();
        catalog.assets().forEach(a -> kinds.put(a.path(), a.kind()));
        assertEquals(Map.of("FLEET.DEPOTS", "table", "FLEET.DRIVERS", "table", "FLEET.VEHICLES", "table",
            "FREIGHT.CARRIERS", "table", "FREIGHT.SCAN_STAGING", "table", "FREIGHT.SHIPMENTS", "table",
            "FREIGHT.SHIPMENT_EVENTS", "table", "FREIGHT.LATE_SHIPMENTS", "view"), kinds);
        Map<String, String> rows = catalog.assets().stream().filter(a -> a.hints().containsKey("row_estimate"))
            .collect(Collectors.toMap(AssetInfo::path, a -> a.hints().get("row_estimate")));
        assertEquals("5000", rows.get("FREIGHT.SHIPMENTS"));
        assertEquals("20000", rows.get("FREIGHT.SHIPMENT_EVENTS"));
        assertEquals("1003", rows.get("FREIGHT.SCAN_STAGING"));
        assertEquals(List.of(), catalog.assets().stream().filter(a -> a.path().equals("FREIGHT.SCAN_STAGING"))
            .findFirst().orElseThrow().primaryKey(), "the staging table has no key");
    }

    @Test
    void declaredKeysIncludeOneAcrossSchemas() {
        Set<String> keys = catalog.relationships().stream().map(r -> r.fromPath() + r.fromFields() + " -> " + r.toPath())
            .collect(Collectors.toSet());
        assertTrue(keys.contains("FREIGHT.SHIPMENTS[VEHICLE_ID] -> FLEET.VEHICLES"), keys.toString());
        assertTrue(keys.contains("FREIGHT.SHIPMENTS[CARRIER_ID] -> FREIGHT.CARRIERS"), keys.toString());
        assertTrue(keys.contains("FLEET.DRIVERS[DEPOT_ID] -> FLEET.DEPOTS"), keys.toString());
        assertTrue(keys.stream().noneMatch(k -> k.startsWith("FREIGHT.SHIPMENT_EVENTS")), "that join is undeclared");
    }

    @Test
    void thePlantedProblemsAreThere() throws Exception {
        assertEquals(3, count("SELECT COUNT(*) FROM freight.carriers WHERE email IS NULL"));
        assertEquals(3, count("SELECT COUNT(*) FROM freight.shipments WHERE EXTRACT(YEAR FROM shipped_on) IN (1900, 2099)"));
        assertEquals(3, count("SELECT COUNT(*) FROM freight.shipments WHERE CAST(delivered_at AS DATE) < shipped_on "
            + "AND EXTRACT(YEAR FROM shipped_on) < 2099"));
        assertEquals(3, count("SELECT COUNT(*) FROM freight.shipments WHERE weight_kg < 0"));
        assertEquals(3, count("SELECT COUNT(*) FROM (SELECT tracking_number FROM freight.scan_staging "
            + "GROUP BY tracking_number HAVING COUNT(*) > 1)"));
        assertEquals(3, count("SELECT COUNT(DISTINCT postcode) FROM freight.shipments"));
        assertEquals(300, count("SELECT COUNT(*) FROM fleet.drivers WHERE licence_number LIKE 'QQ%'"));
        assertEquals(557, count("SELECT COUNT(*) FROM freight.late_shipments"));
    }

    @Test
    void theShipmentsReadThroughTheConnector() throws Exception {
        AssetInfo shipments = db.connector().asset(reader, "FREIGHT.SHIPMENTS");
        Map<String, String> kinds = new TreeMap<>();
        shipments.fields().forEach(f -> kinds.put(f.name(), f.kind()));
        assertEquals("text", kinds.get("DECLARED_VALUE"), "a NUMBER without precision is read as its exact digits");
        assertEquals("timestamp", kinds.get("SHIPPED_ON"));
        assertEquals("timestamp_tz", kinds.get("DELIVERED_AT"));
        assertEquals("decimal", kinds.get("WEIGHT_KG"));
        assertEquals("boolean", kinds.get("SIGNED_FOR"));
        Rows.Collected back = new Rows.Collected();
        StepSpec read = db.connector().reader(reader, shipments,
            new Connector.ReadOptions("urn:kilnmark:lab:FREIGHT.SHIPMENTS", 0, 1000));
        RunResult r = Rows.run(read, new StepSpec("collect", Rows.collector(back), Map.of()), Rows.events());
        assertTrue(r.succeeded(), r.error());
        assertEquals(5000, back.rows.size());
        int weight = back.columns.stream().map(c -> c.name()).toList().indexOf("WEIGHT_KG");
        assertEquals(3, back.rows.stream().filter(row -> row.get(weight) != null
            && ((BigDecimal) row.get(weight)).signum() < 0).count());
    }
}

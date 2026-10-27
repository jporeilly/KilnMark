package io.kilnmark.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.Connectors;
import io.kilnmark.server.KilnmarkConfig;

class CatalogueUnitTest {

    private static KilnmarkConfig config(boolean showPlanned) {
        KilnmarkConfig.ConnectorList list = (KilnmarkConfig.ConnectorList) Proxy.newProxyInstance(
            KilnmarkConfig.class.getClassLoader(), new Class<?>[] {KilnmarkConfig.ConnectorList.class},
            (proxy, method, args) -> showPlanned);
        return (KilnmarkConfig) Proxy.newProxyInstance(KilnmarkConfig.class.getClassLoader(),
            new Class<?>[] {KilnmarkConfig.class}, (proxy, method, args) -> {
                if (method.getName().equals("connectors")) {
                    return list;
                }
                throw new UnsupportedOperationException(method.getName());
            });
    }

    @Test
    void plannedConnectorsCanBeHidden() {
        Views.ConnectorCatalogue c = new CatalogResource(null, null, Connectors.load(), config(false)).catalogue();
        assertFalse(c.showPlanned());
        assertTrue(c.categories().stream().allMatch(x -> x.planned().isEmpty()));
        // Test-only connectors (kind test-...) are registered on the test class path; leave them out.
        assertEquals(4, c.categories().stream().flatMap(x -> x.connectors().stream())
            .filter(x -> !x.kind().startsWith("test-")).count());
        Views.ConnectorCatalogue shown = new CatalogResource(null, null, Connectors.load(), config(true)).catalogue();
        assertTrue(shown.categories().stream().mapToInt(x -> x.planned().size()).sum() > 30);
    }

    @Test
    void everyBuiltConnectorClaimsOnlyWhatItCanDoToday() {
        Set<String> writable = Set.of("postgresql", "sqlserver", "files", "s3");
        for (Connector c : Connectors.load().all()) {
            if (c.kind().startsWith("test-")) {
                continue; // test-only connectors (the pipeline tests' writer) are not shipped
            }
            Set<Connector.Capability> today = writable.contains(c.kind())
                ? Set.of(Connector.Capability.DISCOVER, Connector.Capability.READ, Connector.Capability.WRITE)
                : Set.of(Connector.Capability.DISCOVER, Connector.Capability.READ);
            assertEquals(today, c.capabilities(), c.kind());
            assertEquals(null, c.driver(), c.kind() + " needs nothing installed");
        }
        Connector files = Connectors.load().get("files").orElseThrow();
        assertEquals(List.of("csv", "tsv", "json", "jsonl", "parquet", "excel", "documents"), files.formats());
        assertEquals("databases", Connectors.load().get("sqlserver").orElseThrow().category());
        assertEquals("bucket", Connectors.load().get("s3").orElseThrow().icon());
    }

    @Test
    void productsHaveMonogramsAndPlannedConnectorsHaveIds() {
        assertEquals("Pg", Connectors.load().get("postgresql").orElseThrow().monogram());
        assertEquals(null, Connectors.load().get("files").orElseThrow().monogram(), "a folder shows its icon");
        Set<String> ids = new java.util.HashSet<>();
        for (Views.PlannedConnector p : PlannedConnectors.ALL) {
            assertTrue(p.id().matches("[a-z0-9][a-z0-9-]{0,63}"), p.id());
            assertTrue(ids.add(p.id()), "unique: " + p.id());
            assertTrue(p.monogram() == null || p.monogram().length() <= 3, p.name());
        }
        for (Connector c : Connectors.load().all()) {
            assertFalse(ids.contains(c.kind()), "a built connector is not also planned: " + c.kind());
        }
    }

    @Test
    void theConnectorViewCarriesEachSettingsKindChoicesAndDefault() {
        Views.Connector sql = CatalogResource.view(Connectors.load().get("sqlserver").orElseThrow());
        Views.Setting trust = sql.settings().stream().filter(s -> s.key().equals("trust_server_certificate"))
            .findFirst().orElseThrow();
        assertEquals("boolean", trust.kind());
        assertEquals("false", trust.defaultValue());
        assertEquals(List.of(), trust.choices());
        Views.Setting isolation = sql.settings().stream().filter(s -> s.key().equals("isolation")).findFirst()
            .orElseThrow();
        assertEquals("choice", isolation.kind());
        assertEquals(List.of("read-uncommitted", "snapshot", "read-committed"), isolation.choices());
        assertEquals("read-uncommitted", isolation.defaultValue());
        assertEquals("url", sql.settings().get(0).kind());
        Views.Connector files = CatalogResource.view(Connectors.load().get("files").orElseThrow());
        Views.Setting containers = files.settings().stream().filter(s -> s.key().equals("containers")).findFirst()
            .orElseThrow();
        assertEquals("text", containers.kind(), "the added container setting is a list of text");
        assertTrue(containers.multiple());
    }
}

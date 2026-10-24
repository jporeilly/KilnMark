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
        assertEquals(4, c.categories().stream().mapToInt(x -> x.connectors().size()).sum());
        Views.ConnectorCatalogue shown = new CatalogResource(null, null, Connectors.load(), config(true)).catalogue();
        assertTrue(shown.categories().stream().mapToInt(x -> x.planned().size()).sum() > 30);
    }

    @Test
    void everyBuiltConnectorClaimsOnlyWhatItCanDoToday() {
        for (Connector c : Connectors.load().all()) {
            assertEquals(Set.of(Connector.Capability.DISCOVER, Connector.Capability.READ), c.capabilities(), c.kind());
            assertEquals(null, c.driver(), c.kind() + " needs nothing installed");
        }
        Connector files = Connectors.load().get("files").orElseThrow();
        assertEquals(List.of("csv", "tsv", "json", "jsonl", "parquet", "documents"), files.formats());
        assertEquals("databases", Connectors.load().get("sqlserver").orElseThrow().category());
        assertEquals("bucket", Connectors.load().get("s3").orElseThrow().icon());
    }
}

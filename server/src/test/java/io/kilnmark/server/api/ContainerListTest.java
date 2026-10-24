package io.kilnmark.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.SourceConfig;

class ContainerListTest {

    private static final Connector.ContainerKind SCHEMAS = new Connector.ContainerKind("schemas", "Schemas",
        "tables and views");
    private static final List<Connector.ContainerInfo> FOUND = List.of(new Connector.ContainerInfo("crm", 3),
        new Connector.ContainerInfo("empty", 0), new Connector.ContainerInfo("sales", 2));

    private static Views.ContainerList list(Map<String, String> settings, List<Connector.ContainerInfo> found) {
        return SourceService.containerList(SCHEMAS, new SourceConfig("s", "postgresql", settings, null), found);
    }

    @Test
    void noChoiceMeansEverything() {
        Views.ContainerList l = list(Map.of(), FOUND);
        assertEquals(5, l.total());
        assertFalse(l.nothingToScan());
        assertTrue(l.warnings().isEmpty());
        assertTrue(l.containers().stream().noneMatch(Views.ContainerItem::selected));
    }

    @Test
    void aChoiceCountsOnlyWhatIsChosen() {
        Views.ContainerList l = list(Map.of("schemas", "CRM"), FOUND);
        assertEquals(3, l.total());
        assertEquals(List.of(true, false, false), l.containers().stream().map(Views.ContainerItem::selected).toList());
    }

    @Test
    void warnsBeforeScanningNothing() {
        Views.ContainerList empty = list(Map.of("schemas", "empty"), FOUND);
        assertTrue(empty.nothingToScan());
        assertTrue(empty.warnings().get(0).contains("nothing"), empty.warnings().toString());

        Views.ContainerList gone = list(Map.of("schemas", "old"), FOUND);
        assertTrue(gone.nothingToScan());
        assertEquals(List.of("old"), gone.missing());
        assertEquals(2, gone.warnings().size());

        Views.ContainerList none = list(Map.of(), List.of());
        assertTrue(none.nothingToScan());
        assertTrue(none.warnings().get(0).contains("holds no schemas"));

        Views.ContainerList partly = list(Map.of("schemas", "crm,old"), FOUND);
        assertFalse(partly.nothingToScan());
        assertEquals(1, partly.warnings().size(), "a missing choice is still worth saying");
    }
}

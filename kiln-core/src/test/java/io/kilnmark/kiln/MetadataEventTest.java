package io.kilnmark.kiln;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MetadataEventTest {

    @Test
    void rejectsNonKilnmarkUrn() {
        assertThrows(IllegalArgumentException.class,
                () -> new MetadataEvent("urn:other:x", MetadataEvent.Kind.SCHEMA, Map.of(), Instant.now()));
    }

    @Test
    void copiesAttributesSoLaterChangesDoNotLeakIn() {
        Map<String, String> attributes = new HashMap<>();
        attributes.put("rows", "10");
        MetadataEvent event = new MetadataEvent(
                "urn:kilnmark:pg:crm.customers", MetadataEvent.Kind.ROW_COUNT, attributes, Instant.now());
        attributes.put("rows", "20");
        assertEquals("10", event.attributes().get("rows"));
    }
}

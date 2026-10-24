package io.kilnmark.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class UrnTest {

    @Test
    void roundTrips() {
        String text = "urn:kilnmark:pg:crm.customers";
        Urn urn = Urn.parse(text);
        assertEquals("pg", urn.source());
        assertEquals("crm.customers", urn.path());
        assertEquals(text, urn.toString());
    }

    @Test
    void keepsColonsInsideThePath() {
        assertEquals("bucket:raw/a.csv", Urn.parse("urn:kilnmark:s3:bucket:raw/a.csv").path());
    }

    @Test
    void rejectsOtherSchemes() {
        assertThrows(IllegalArgumentException.class, () -> Urn.parse("urn:other:pg:x"));
        assertThrows(IllegalArgumentException.class, () -> Urn.parse("urn:kilnmark:Bad Source:x"));
    }
}

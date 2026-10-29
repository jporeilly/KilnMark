package io.kilnmark.kiln.yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class SuggestTest {

    @Test
    void suggestsCloseOptions() {
        assertEquals("filter", Suggest.closest("filtr", List.of("filter", "lookup")));
        assertEquals("nodes", Suggest.closest("ndoes", List.of("name", "nodes")));
        assertNull(Suggest.closest("zzzzzz", List.of("filter")));
    }

    @Test
    @Timeout(5)
    void skipsOptionsWhoseLengthDiffersByMoreThanItsAllowance() {
        // Measuring these would need a 200,001 by 200,004 table (about 160 GB): only the length check
        // lets this finish, and an option exactly at the allowance is still measured.
        String typed = "a".repeat(200_000);
        assertNull(Suggest.closest(typed, List.of("b".repeat(200_003), "filter")));
        assertNull(Suggest.closest("x".repeat(999_000), List.of("filter", "lookup", "union")));
        assertEquals("filters", Suggest.closest("filter", List.of("filters")));
        assertNull(Suggest.closest("filter", List.of("filterxyz")), "three longer than a two-edit allowance");
    }
}

package io.kilnmark.server.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Only citations of sections that were sent survive. */
class CitationsTest {

    @Test
    void validCitationsAreKeptAndOthersDropped() {
        Citations.Checked c = Citations.check("Fail above is the limit [1]. Warnings come first [2, 7]. "
            + "See also [9].", 3);
        assertEquals("Fail above is the limit [1]. Warnings come first [2]. See also.", c.text());
        assertEquals(List.of(1, 2), c.cited());
    }

    @Test
    void citationVariantsBecomePlainNumbers() {
        Citations.Checked c = Citations.check("Yes [Section 3] and [1][3] and [2 and 1].", 3);
        assertEquals("Yes [3] and [1][3] and [2][1].", c.text());
        assertEquals(List.of(3, 1, 2), c.cited());
    }

    @Test
    void bracketsThatAreNotCitationsStay() {
        Citations.Checked c = Citations.check("Use ^[A-Z]{2}[0-9]$ or [a-z] here [0].", 2);
        assertEquals("Use ^[A-Z]{2}[0-9]$ or [a-z] here.", c.text());
        assertEquals(List.of(), c.cited());
    }
}

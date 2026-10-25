package io.kilnmark.server.pipelines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Pipeline names, If-Match versions and finding a key's line. */
class PipelineRulesTest {

    @Test
    void namesAreSmallLettersDigitsAndDashes() {
        assertNull(PipelineService.nameProblem("customer-billing"));
        assertNull(PipelineService.nameProblem("a"));
        assertNull(PipelineService.nameProblem("a" + "b".repeat(62)));
        assertTrue(PipelineService.nameProblem("a" + "b".repeat(63)).startsWith("name: "));
        assertTrue(PipelineService.nameProblem("Customer").startsWith("name: 'Customer' cannot name a pipeline"));
        assertTrue(PipelineService.nameProblem("1st").startsWith("name: "));
        assertTrue(PipelineService.nameProblem("-x").startsWith("name: "));
        assertTrue(PipelineService.nameProblem("a_b").startsWith("name: "));
        assertTrue(PipelineService.nameProblem("a.b").startsWith("name: "));
        assertTrue(PipelineService.nameProblem("").startsWith("name: give the pipeline a name"));
        assertTrue(PipelineService.nameProblem(null).startsWith("name: give the pipeline a name"));
    }

    @Test
    void ifMatchCarriesTheLatestVersion() {
        assertNull(PipelineService.readVersion(null));
        assertNull(PipelineService.readVersion(" "));
        assertNull(PipelineService.readVersion("*"));
        assertEquals(3, PipelineService.readVersion("\"3\""));
        assertEquals(3, PipelineService.readVersion("W/\"3\""));
        assertEquals(12, PipelineService.readVersion("12"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> PipelineService.readVersion("\"0\""))
            .getMessage().startsWith("If-Match:"));
        assertThrows(IllegalArgumentException.class, () -> PipelineService.readVersion("2026-10-25T09:30:00Z"));
    }

    @Test
    void theLineOfATopLevelKey() {
        String yaml = "kilnmark: pipeline/v1\n# a comment\nname: x\nsteps: []\n";
        assertEquals(3, PipelineService.lineOf(yaml, "name"));
        assertEquals(1, PipelineService.lineOf(yaml, "kilnmark"));
        assertEquals(0, PipelineService.lineOf(yaml, "description"));
    }
}

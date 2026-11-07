package io.kilnmark.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * What a failed Starter Pack step shows (ADR 0051): the pack's own words as they are; anything
 * else, which can carry an address or a value, only in the server's log.
 */
class StarterPackMessagesTest {

    /** Whether a team's own pipeline or flow names a source, in any of YAML's styles (the pack keeps it then). */
    @Test
    void aSourceIsFoundInEveryStyle() {
        for (String yaml : java.util.List.of("source: sample-data", "  - { id: r, type: read, source: sample-data }",
            "{\"nodes\": [{\"id\": \"r\", \"source\": \"sample-data\"}]}", "source : 'sample-data'",
            "{'source':'sample-data'}")) {
            org.junit.jupiter.api.Assertions.assertTrue(StarterPack.names(yaml, "sample-data"), yaml);
        }
        for (String yaml : java.util.List.of("datasource: sample-data", "source: sample-data-2", "# sample-data")) {
            org.junit.jupiter.api.Assertions.assertFalse(StarterPack.names(yaml, "sample-data"), yaml);
        }
    }

    @Test
    void onlyThePacksOwnWordsAreShown() {
        assertEquals("A source called x is here that the Starter Pack did not make",
            StarterPack.shown("files source", new StarterPack.Problem("A source called x is here that the Starter Pack did not make")));
        assertEquals("it could not be done: the server's log says why", StarterPack.shown("database source",
            new IllegalStateException("Connection to db.internal.example:5432 refused")));
        assertEquals("it could not be done: the server's log says why", StarterPack.shown("samples",
            new IllegalArgumentException("host: 'db.internal.example' is not on the allowed hosts")));
    }
}

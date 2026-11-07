package io.kilnmark.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * What a failed Starter Pack step shows (ADR 0051): the pack's own words as they are; anything
 * else, which can carry an address or a value, only in the server's log.
 */
class StarterPackMessagesTest {

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

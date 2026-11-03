package io.kilnmark.server.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Requests signed in by a provider session must come from Kilnmark's own pages to change anything (ADR 0036). */
class SessionRequestsTest {

    @Test
    void changesWithASessionNeedTheHeader() {
        assertTrue(SessionRequests.refused("POST", true, false, null));
        assertTrue(SessionRequests.refused("delete", true, false, "XMLHttpRequest"));
        assertFalse(SessionRequests.refused("PUT", true, false, "JavaScript"));
        assertFalse(SessionRequests.refused("PATCH", true, false, " javascript "));
    }

    @Test
    void readsTokensAndNoSessionAreLeftAlone() {
        assertFalse(SessionRequests.refused("GET", true, false, null));
        assertFalse(SessionRequests.refused("HEAD", true, false, null));
        assertFalse(SessionRequests.refused("POST", false, false, null), "no session cookie");
        assertFalse(SessionRequests.refused("POST", true, true, null), "signed in by a bearer token");
    }
}

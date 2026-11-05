package io.kilnmark.server.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** No API token may be named so it reads as a provider sign-in, sso:... (ADR 0036). */
class ProviderNameTest {

    @Test
    void namesThatReadAsProviderSignInsAreRefusedHoweverWritten() {
        for (String name : List.of("sso:ada", "SSO:ada", " sso:ada", " sso:ada", "ㅤsso:ada",
            "ｓｓｏ:ada", "sso：ada", "s̶so:ada", "  sso:ada", "-sso:ada")) {
            assertTrue(ApiTokens.looksLikeProviderName(name), name);
        }
    }

    @Test
    void otherNamesAreFine() {
        for (String name : List.of("nightly-ci", "sso-report", "ssoreport", "my sso:token", "ss o:x", "assoc:1")) {
            assertFalse(ApiTokens.looksLikeProviderName(name), name);
        }
    }
}

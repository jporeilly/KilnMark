package io.kilnmark.server;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.kilnmark.server.KilnmarkConfig.Hsts;
import io.kilnmark.server.KilnmarkConfig.Plain;
import io.quarkus.test.junit.QuarkusTest;

/** Every response carries the headers a bank's scanner looks for (ADR 0031). */
@QuarkusTest
class SecurityHeadersTest {

    @Test
    void everyResponseSaysHowABrowserMayUseIt() {
        // The page, the API, a refusal for want of sign-in, a page that is not there, and health
        record Seen(String path, int status) {
        }
        for (Seen s : java.util.List.of(new Seen("/", 200), new Seen("/api/v1/info", 200),
            new Seen("/api/v1/sources", 401), new Seen("/no-such-file.js", 404), new Seen("/q/health/ready", 200))) {
            given().get(s.path()).then().statusCode(s.status())
                .header("Content-Security-Policy", equalTo(SecurityHeaders.CSP))
                .header("X-Content-Type-Options", equalTo("nosniff"))
                .header("X-Frame-Options", equalTo("DENY"))
                .header("Referrer-Policy", equalTo("no-referrer"))
                .header("Cross-Origin-Opener-Policy", equalTo("same-origin"))
                .header("Permissions-Policy", containsString("camera=()"))
                // Plain HTTP: no HSTS, which browsers ignore there anyway
                .header("Strict-Transport-Security", nullValue());
        }
        // No inline script or style on Kilnmark's own pages, and no framing by anyone
        assertFalse(SecurityHeaders.CSP.contains("unsafe-inline"));
        assertTrue(SecurityHeaders.CSP.contains("frame-ancestors 'none'"));
    }

    @Test
    void onlyTheApiPageMayRunItsInlineStart() {
        given().get("/api/docs/").then()
            .header("Content-Security-Policy", equalTo(SecurityHeaders.API_DOCS_CSP))
            .header("Content-Security-Policy", containsString("script-src 'self' 'unsafe-inline'"));
        given().get("/api/v1/info").then()
            .header("Content-Security-Policy", not(containsString("unsafe-inline")));
        // Exactly the page and its files, not anything whose name starts the same way
        assertTrue(SecurityHeaders.apiDocs("/api/docs"));
        assertTrue(SecurityHeaders.apiDocs("/api/docs/index.html"));
        assertFalse(SecurityHeaders.apiDocs("/api/docsx"));
        assertFalse(SecurityHeaders.apiDocs("/api/docs-anything"));
    }

    @Test
    void plainHttpFromOtherMachinesFollowsTheSettingAndTheHealthCheckIsAlwaysServed() {
        assertEquals("serve", SecurityHeaders.plainAnswer(false, false, Plain.ENABLED));
        assertEquals("redirect", SecurityHeaders.plainAnswer(false, false, Plain.REDIRECT));
        assertEquals("refuse", SecurityHeaders.plainAnswer(false, false, Plain.DISABLED));
        // The container's health check
        assertEquals("serve", SecurityHeaders.plainAnswer(false, true, Plain.DISABLED));
        assertEquals("serve", SecurityHeaders.plainAnswer(false, true, Plain.REDIRECT));
        // HTTPS is always served
        assertEquals("serve", SecurityHeaders.plainAnswer(true, false, Plain.DISABLED));
        for (String local : java.util.List.of("127.0.0.1", "127.0.0.2", "::1", "0:0:0:0:0:0:0:1")) {
            assertTrue(SecurityHeaders.local(local), local);
        }
        for (String remote : java.util.List.of("10.88.0.1", "192.168.1.5", "fe80::1", "128.0.0.1")) {
            assertFalse(SecurityHeaders.local(remote), remote);
        }
        assertFalse(SecurityHeaders.local(null));
        // A redirect keeps the host asked for and its path, on the HTTPS port
        assertEquals("https:" + "/" + "/kilnmark.example.com:8443/api/v1/info?x=1",
            SecurityHeaders.httpsUrl("kilnmark.example.com", 8443, "/api/v1/info?x=1"));
        assertEquals("https:" + "/" + "/kilnmark.example.com/", SecurityHeaders.httpsUrl("kilnmark.example.com", 443, ""));
        assertEquals("https:" + "/" + "/[::1]:8443/", SecurityHeaders.httpsUrl("::1", 8443, "/"));
    }

    @Test
    void hstsFollowsTheSetting() {
        assertTrue(SecurityHeaders.sendsHsts(true, Hsts.WHEN_HTTPS));
        assertFalse(SecurityHeaders.sendsHsts(false, Hsts.WHEN_HTTPS));
        assertTrue(SecurityHeaders.sendsHsts(false, Hsts.ALWAYS), "behind a proxy that ends TLS");
        assertFalse(SecurityHeaders.sendsHsts(true, Hsts.NEVER));
        // Never every name under a shared domain
        assertFalse(SecurityHeaders.HSTS.contains("includeSubDomains"));
    }
}

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

import io.quarkus.test.junit.QuarkusTest;

/** Every response carries the headers a bank's scanner looks for (ADR 0031). */
@QuarkusTest
class SecurityHeadersTest {

    @Test
    void everyResponseSaysHowABrowserMayUseIt() {
        for (String path : java.util.List.of("/", "/api/v1/info", "/api/v1/sources", "/q/health/ready")) {
            given().get(path).then()
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
    }

    @Test
    void plainHttpFromOtherMachinesFollowsTheSettingAndThisMachineIsAlwaysServed() {
        assertEquals("serve", SecurityHeaders.plainAnswer(false, false, "enabled"));
        assertEquals("redirect", SecurityHeaders.plainAnswer(false, false, "redirect"));
        assertEquals("refuse", SecurityHeaders.plainAnswer(false, false, "disabled"));
        // The container's health check and a proxy beside Kilnmark
        assertEquals("serve", SecurityHeaders.plainAnswer(false, true, "disabled"));
        assertEquals("serve", SecurityHeaders.plainAnswer(false, true, "redirect"));
        // HTTPS is always served
        assertEquals("serve", SecurityHeaders.plainAnswer(true, false, "disabled"));
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
        assertTrue(SecurityHeaders.sendsHsts(true, "when-https"));
        assertFalse(SecurityHeaders.sendsHsts(false, "when-https"));
        assertTrue(SecurityHeaders.sendsHsts(false, "always"), "behind a proxy that ends TLS");
        assertFalse(SecurityHeaders.sendsHsts(true, "never"));
        assertTrue(SecurityHeaders.sendsHsts(true, "anything else"), "an unknown value is the default");
    }
}

package io.kilnmark.server.branding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jakarta.ws.rs.core.Response;

/** The branding folder serves only what the manifest lists, only images, and nothing that can run. */
class BrandingUnitTest {

    private static final String SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\">"
        + "<rect width=\"10\" height=\"10\" style=\"fill:#336791\"/></svg>";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13};

    @TempDir
    Path dir;

    private void write(String name, String text) throws IOException {
        Files.writeString(dir.resolve(name), text, StandardCharsets.UTF_8);
    }

    @Test
    void noFolderMeansNoBranding() {
        BrandingFolder f = new BrandingFolder(dir.resolve("missing"), 256 * 1024, 0);
        assertNull(f.view().productName());
        assertNull(f.view().logo());
        assertTrue(f.view().connectors().isEmpty());
        assertTrue(f.image("anything.svg").isEmpty());
    }

    @Test
    void readsTheManifestAndServesListedImages() throws IOException {
        write("acme.svg", SVG);
        Files.write(dir.resolve("pg.png"), PNG);
        write("manifest.json", """
            {"product": {"name": "  Acme Data\\u0007 ", "logo": "acme.svg"},
             "connectors": {
               "postgresql": {"file": "pg.png", "color": "#336791", "label": "PostgreSQL"},
               "oracle": {"color": "#C74634"}
             }}""");
        BrandingFolder f = new BrandingFolder(dir, 256 * 1024, 0);
        BrandingFolder.View v = f.view();
        assertEquals("Acme Data", v.productName());
        assertTrue(v.logo().startsWith("/api/v1/branding/files/acme.svg?v="), v.logo());
        assertTrue(v.connectors().get("postgresql").image().startsWith("/api/v1/branding/files/pg.png?v="));
        assertEquals("#336791", v.connectors().get("postgresql").color());
        assertEquals("PostgreSQL", v.connectors().get("postgresql").label());
        assertNull(v.connectors().get("oracle").image());
        assertEquals("#c74634", v.connectors().get("oracle").color());
        assertEquals("image/svg+xml", f.image("acme.svg").orElseThrow().contentType());
        assertEquals("image/png", f.image("pg.png").orElseThrow().contentType());
    }

    @Test
    void servesNothingTheManifestDoesNotList() throws IOException {
        write("listed.svg", SVG);
        write("other.svg", SVG);
        write("secret.txt", "not for you");
        write("manifest.json", "{\"product\": {\"logo\": \"listed.svg\"}}");
        BrandingFolder f = new BrandingFolder(dir, 256 * 1024, 0);
        assertTrue(f.image("listed.svg").isPresent());
        assertTrue(f.image("other.svg").isEmpty());
        assertTrue(f.image("secret.txt").isEmpty());
        assertTrue(f.image("manifest.json").isEmpty());
        assertTrue(f.image("../listed.svg").isEmpty());
    }

    @Test
    void refusesPathsTypesAndOversizedFiles() throws IOException {
        Path outside = dir.resolve("outside");
        Files.createDirectories(outside);
        Path inside = dir.resolve("brand");
        Files.createDirectories(inside.resolve("sub"));
        Files.writeString(outside.resolve("x.svg"), SVG);
        Files.writeString(inside.resolve("sub/y.svg"), SVG);
        Files.writeString(inside.resolve("page.html"), "<html></html>");
        Files.writeString(inside.resolve("big.svg"), SVG + " ".repeat(2048));
        Files.writeString(inside.resolve("fake.png"), "<svg></svg>");
        Files.write(inside.resolve("fake.svg"), PNG);
        Files.writeString(inside.resolve("script.svg"),
            "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>");
        Files.writeString(inside.resolve("entity.svg"),
            "<?xml version=\"1.0\"?><!DOCTYPE svg [<!ENTITY a \"aaaa\">]><svg>&a;</svg>");
        Files.createSymbolicLink(inside.resolve("link.svg"), outside.resolve("x.svg"));
        Files.writeString(inside.resolve("manifest.json"), """
            {"connectors": {
               "a": {"file": "../outside/x.svg"},
               "b": {"file": "sub/y.svg"},
               "c": {"file": "page.html"},
               "d": {"file": "big.svg"},
               "e": {"file": "fake.png"},
               "f": {"file": "fake.svg"},
               "g": {"file": "script.svg"},
               "h": {"file": "entity.svg"},
               "i": {"file": "link.svg"},
               "j": {"file": "/etc/passwd.svg"},
               "k": {"file": "missing.svg"},
               "Bad Id": {"color": "#000000"},
               "l": {"color": "red", "label": 42}
             }}""");
        BrandingFolder f = new BrandingFolder(inside, 1024, 0);
        assertTrue(f.view().connectors().isEmpty(), f.view().connectors().toString());
        for (String n : new String[] {"x.svg", "y.svg", "page.html", "big.svg", "fake.png", "fake.svg", "script.svg",
            "entity.svg", "link.svg"}) {
            assertTrue(f.image(n).isEmpty(), n);
        }
    }

    @Test
    void invalidManifestFallsBackToNoBranding() throws IOException {
        write("manifest.json", "{ not json");
        assertTrue(new BrandingFolder(dir, 1024, 0).view().connectors().isEmpty());
        write("manifest.json", "[1, 2]");
        assertNull(new BrandingFolder(dir, 1024, 0).view().productName());
    }

    @Test
    void picksUpChangesWithoutARestart() throws IOException {
        write("manifest.json", "{\"connectors\": {\"kafka\": {\"file\": \"kafka.svg\"}}}");
        BrandingFolder f = new BrandingFolder(dir, 256 * 1024, 0);
        assertTrue(f.view().connectors().isEmpty());
        // The file named but missing at first is watched, so adding it is noticed.
        write("kafka.svg", SVG);
        String first = f.view().connectors().get("kafka").image();
        write("kafka.svg", SVG.replace("336791", "000000"));
        Files.setLastModifiedTime(dir.resolve("kafka.svg"), FileTime.from(Instant.now().plusSeconds(5)));
        assertNotEquals(first, f.view().connectors().get("kafka").image());
    }

    @Test
    void imagesAreServedSoNothingInThemCanRun() throws IOException {
        write("logo.svg", SVG);
        write("manifest.json", "{\"product\": {\"logo\": \"logo.svg\"}}");
        BrandingFolder.Image i = new BrandingFolder(dir, 1024, 0).image("logo.svg").orElseThrow();
        Response r = BrandingResource.image(i, null);
        assertEquals(200, r.getStatus());
        assertEquals("image/svg+xml", r.getMediaType().toString());
        assertEquals("nosniff", r.getHeaderString("X-Content-Type-Options"));
        String csp = r.getHeaderString("Content-Security-Policy");
        assertTrue(csp.startsWith("default-src 'none'"), csp);
        assertTrue(csp.contains("sandbox"), csp);
        assertFalse(csp.contains("script-src"), csp);
        assertEquals(304, BrandingResource.image(i, "\"" + i.etag() + "\"").getStatus());
    }

    @Test
    void checksForChangesAtMostOncePerInterval() throws IOException {
        write("logo.svg", SVG);
        write("manifest.json", "{\"product\": {\"name\": \"First\"}}");
        BrandingFolder f = new BrandingFolder(dir, 1024, java.util.concurrent.TimeUnit.HOURS.toNanos(1));
        assertEquals("First", f.view().productName());
        write("manifest.json", "{\"product\": {\"name\": \"Second, longer\"}}");
        assertEquals("First", f.view().productName(), "not rechecked within the interval");
        assertEquals("Second, longer", new BrandingFolder(dir, 1024, 0).view().productName());
    }

    @Test
    void everyEntryCountsAgainstTheCap() throws IOException {
        StringBuilder b = new StringBuilder("{\"connectors\": {");
        for (int i = 0; i < BrandingFolder.MAX_ENTRIES; i++) {
            b.append("\"Bad").append(i).append("\": {\"color\": \"#000000\"}, ");
        }
        b.append("\"late\": {\"color\": \"#000000\"}}}");
        write("manifest.json", b.toString());
        assertTrue(new BrandingFolder(dir, 1024, 0).view().connectors().isEmpty(), "entries past the cap are ignored");
    }

    @Test
    void svgsWithActiveContentAreRefused() {
        for (String bad : new String[] {
            "<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\"/>",
            "<svg><a href=\"javascript:alert(1)\"><rect/></a></svg>",
            "<svg><foreignObject><div/></foreignObject></svg>",
            "<svg><SCRIPT>x</SCRIPT></svg>"}) {
            assertNull(BrandingFolder.contentType("a.svg", bad.getBytes(StandardCharsets.UTF_8)), bad);
        }
    }

    @Test
    void oversizedCapIsClampedNotOverflowed() throws IOException {
        write("logo.svg", SVG);
        write("manifest.json", "{\"product\": {\"logo\": \"logo.svg\"}}");
        assertTrue(new BrandingFolder(dir, Long.MAX_VALUE, 0).image("logo.svg").isPresent());
    }

    @Test
    void contentMustMatchTheExtension() {
        assertEquals("image/png", BrandingFolder.contentType("a.png", PNG));
        assertNull(BrandingFolder.contentType("a.webp", PNG));
        byte[] webp = "RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1);
        assertEquals("image/webp", BrandingFolder.contentType("a.WEBP", webp));
        assertEquals("image/svg+xml", BrandingFolder.contentType("a.svg",
            ("﻿<?xml version=\"1.0\"?>\n" + SVG).getBytes(StandardCharsets.UTF_8)));
        assertNull(BrandingFolder.contentType("a.svg", "<html><svg></svg></html>".getBytes(StandardCharsets.UTF_8)));
    }
}

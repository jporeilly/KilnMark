package io.kilnmark.server.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** Which folder and bucket sources read some of the same files, for marks that follow the data. */
class OverMarkedTest {

    @Test
    void foldersOverlapWhenOneHoldsTheOther() {
        Map<String, String> data = Map.of("path", "/srv/data");
        assertTrue(SourceService.overlaps("files", Map.of("path", "/srv/data"), data));
        assertTrue(SourceService.overlaps("files", Map.of("path", "/srv/data/hr"), data), "inside it");
        assertTrue(SourceService.overlaps("files", Map.of("path", "/srv"), data), "holding it");
        assertTrue(SourceService.overlaps("files", Map.of("path", "/srv/data/../data/x"), data), "normalised");
        assertFalse(SourceService.overlaps("files", Map.of("path", "/srv/database"), data), "a name that only starts the same");
        assertFalse(SourceService.overlaps("files", Map.of("path", "/srv/other"), data));
    }

    @Test
    void bucketsOverlapOnTheServersCredentialsOnly() {
        Map<String, String> raw = Map.of("bucket", "lake", "prefix", "raw/");
        assertTrue(SourceService.overlaps("s3", Map.of("bucket", "lake"), raw), "the whole bucket");
        assertTrue(SourceService.overlaps("s3", Map.of("bucket", "lake", "prefix", "/raw/hr/"), raw));
        assertFalse(SourceService.overlaps("s3", Map.of("bucket", "lake", "prefix", "curated/"), raw));
        assertFalse(SourceService.overlaps("s3", Map.of("bucket", "other"), raw));
        assertFalse(SourceService.overlaps("s3", Map.of("bucket", "lake", "access_key_id", "AKIA1"), raw),
            "their own credentials already reach the files");
        assertFalse(SourceService.overlaps("postgresql", Map.of("url", "x"), Map.of("url", "x")), "databases need their own");
    }
}

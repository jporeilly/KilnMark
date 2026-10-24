package io.kilnmark.kiln.plugins.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.hardwood.InputFile;

/** Shared copies of remote workbooks: a scan's end drops only its own source's idle copies. */
class LocalCopyTest {

    /** A remote store of one bucket, seen through a prefix. */
    record Remote(String bucket, String prefix) implements FileStore {
        @Override
        public List<Entry> list() {
            return List.of();
        }

        @Override
        public InputStream open(String path) {
            return new ByteArrayInputStream(("synthetic " + path).getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public byte[] head(String path, int bytes) {
            return new byte[0];
        }

        @Override
        public InputFile parquet(String path, long size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String copyScope() {
            return copyKey(prefix);
        }

        @Override
        public String describe(String path) {
            return "remote://" + bucket + "/" + path;
        }
    }

    @Test
    void aScanEndDropsOnlyItsOwnSourcesIdleCopies() throws Exception {
        LocalCopy.dropIdle();
        Remote depot = new Remote("lab", "depot/");
        Remote finance = new Remote("lab", "finance/");
        Path a;
        Path b;
        Path c;
        try (LocalCopy x = LocalCopy.of(depot, "depot/a.xlsx", 1 << 20, "1|t|e");
             LocalCopy y = LocalCopy.of(finance, "finance/b.xlsx", 1 << 20, "1|t|e")) {
            a = x.path();
            b = y.path();
        }
        LocalCopy busy = LocalCopy.of(depot, "depot/c.xlsx", 1 << 20, "1|t|e");
        c = busy.path();
        assertEquals(3, LocalCopy.kept());

        LocalCopy.dropIdle(depot.copyScope());
        assertFalse(Files.exists(a), "the ended source's idle copy is dropped");
        assertTrue(Files.exists(b), "another source's copy stays for its own scan");
        assertTrue(Files.exists(c), "a copy in use stays");
        assertEquals(2, LocalCopy.kept());

        busy.close();
        LocalCopy.dropIdle();
        assertEquals(0, LocalCopy.kept());
        assertFalse(Files.exists(b) || Files.exists(c));
    }
}

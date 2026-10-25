package io.kilnmark.kiln.plugins.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.kilnmark.kiln.connect.ConnectorException;

class FileTargetTest {

    @Test
    void acceptsRelativePathsAndTakesTheFormatFromTheName() throws Exception {
        assertEquals(new FileTarget("exports/customers.csv", "csv"), FileTarget.of("exports/customers.csv", null));
        assertEquals(new FileTarget("a/b/c d_1-2.parquet", "parquet"), FileTarget.of("a/b/c d_1-2.parquet", "Parquet"));
        assertEquals("csv", FileTarget.of("out.CSV", "csv").format());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/etc/passwd.csv", "C:/data/x.csv", "c:x.csv", "~/x.csv", "../x.csv", "a/../../x.csv",
        "a/..x.csv", "a//b.csv", "a\\b.csv", "a/b/", ".hidden.csv", "a/.git/x.csv", "a/\u0000.csv", "a/b\n.csv",
        "a/\u007f.csv", "a/\u0085.csv", " a.csv", "a /b.csv", ""})
    void refusesPathsThatCouldLeaveTheSourceOrHide(String path) {
        ConnectorException e = assertThrows(ConnectorException.class, () -> FileTarget.of(path, "csv"));
        assertTrue(e.getMessage().startsWith("path: "), e.getMessage());
    }

    @Test
    void theFormatMustMatchTheName() {
        assertTrue(assertThrows(ConnectorException.class, () -> FileTarget.of("out.csv", "parquet")).getMessage()
            .contains("should end in .parquet"));
        assertTrue(assertThrows(ConnectorException.class, () -> FileTarget.of("out.txt", null)).getMessage()
            .startsWith("format: csv or parquet"));
        assertTrue(assertThrows(ConnectorException.class, () -> FileTarget.of("out.json", "json")).getMessage()
            .startsWith("format: csv or parquet, not 'json'"));
    }
}

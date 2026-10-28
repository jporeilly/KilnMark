package io.kilnmark.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.connect.Connectors;
import io.kilnmark.server.sources.CommonSettings;

/** lab/sources.csv: only the warehouse bucket lets pipelines write; the synthetic sources stay read-only. */
class LabSourcesFileTest {

    private static Map<String, SourceImporter.Row> rows() throws Exception {
        String csv = Files.readString(Path.of(System.getProperty("user.dir")).getParent().resolve("lab/sources.csv"));
        List<SourceImporter.Row> rows = SourceImporter.fromCsv(csv, SourceImporter.listKeys(Connectors.load().all()));
        return rows.stream().collect(Collectors.toMap(SourceImporter.Row::name, Function.identity()));
    }

    @Test
    void onlyTheWarehouseAllowsWriting() throws Exception {
        Map<String, SourceImporter.Row> rows = rows();
        assertEquals(List.of("lab-bucket", "lab-db", "lab-mariadb", "lab-share", "lab-sqlserver", "lab-warehouse"), rows.keySet().stream().sorted().toList());
        assertTrue(CommonSettings.allowsWriting(rows.get("lab-warehouse").settings()));
        assertTrue(CommonSettings.problems(rows.get("lab-warehouse").settings()).isEmpty());
        for (String read : List.of("lab-db", "lab-bucket", "lab-share", "lab-sqlserver", "lab-mariadb")) {
            assertFalse(CommonSettings.allowsWriting(rows.get(read).settings()), read);
        }
    }

    @Test
    void theWarehouseIsTheLabBucketsServerWithItsOwnBucket() throws Exception {
        Map<String, SourceImporter.Row> rows = rows();
        SourceImporter.Row raw = rows.get("lab-bucket");
        SourceImporter.Row warehouse = rows.get("lab-warehouse");
        assertEquals("s3", warehouse.kind());
        assertEquals(raw.password(), warehouse.password());
        for (String key : List.of("endpoint", "access_key_id", "path_style")) {
            assertEquals(raw.settings().get(key), warehouse.settings().get(key), key);
        }
        assertEquals("lab-raw", raw.settings().get("bucket"));
        assertEquals("lab-warehouse", warehouse.settings().get("bucket"));
    }

    @Test
    void importingAgainMakesTheReadSourcesReadOnly() throws Exception {
        // The column is in the file, so its empty cell removes a stored allow_writing on update.
        SourceImporter.Row db = rows().get("lab-db");
        Map<String, String> merged = SourceImporter.merge(Map.of("allow_writing", "true", "url", "x"), db.settings(),
            db.present(), new ArrayList<>());
        assertFalse(merged.containsKey("allow_writing"));
    }
}

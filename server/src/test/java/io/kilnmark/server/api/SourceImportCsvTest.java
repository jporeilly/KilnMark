package io.kilnmark.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.connect.Connectors;

/** Reading a bulk import CSV, without a server: which values are lists, and the lab's own file. */
class SourceImportCsvTest {

    private static final Set<String> LISTS = SourceImporter.listKeys(Connectors.load().all());

    @Test
    void onlyListSettingsAreLists() {
        assertTrue(LISTS.contains("schemas"), LISTS.toString());
        assertTrue(LISTS.contains("containers"), LISTS.toString());
        for (String text : List.of("url", "username", "description", "delimiter", "endpoint", "prefix", "path",
            "include", "exclude", "trust_server_certificate")) {
            assertFalse(LISTS.contains(text), text);
        }
    }

    @Test
    void semicolonsBecomeCommasInListsOnly() {
        String csv = """
            name,kind,url,username,password,schemas,description,include,delimiter
            mssql,sqlserver,jdbc:sqlserver://db:1433;databaseName=sales;encrypt=true,ro,"pa;ss",sales;hr,"One; two",crm.*;tmp_{a;b},
            files,files,,,,,,,;
            """;
        Map<String, SourceImporter.Row> rows = byName(SourceImporter.fromCsv(csv, LISTS));
        SourceImporter.Row mssql = rows.get("mssql");
        assertEquals("jdbc:sqlserver://db:1433;databaseName=sales;encrypt=true", mssql.settings().get("url"));
        assertEquals("pa;ss", mssql.password());
        assertEquals("sales,hr", mssql.settings().get("schemas"));
        assertEquals("One; two", mssql.settings().get("description"));
        assertEquals("crm.*;tmp_{a;b}", mssql.settings().get("include"));
        assertEquals(";", rows.get("files").settings().get("delimiter"));
    }

    @Test
    void theLabFileImportsSqlServerWithTheCertificateTrusted() throws Exception {
        Path file = Path.of("..", "lab", "sources.csv");
        Map<String, SourceImporter.Row> rows = byName(
            SourceImporter.fromCsv(Files.readString(file, StandardCharsets.UTF_8), LISTS));
        SourceImporter.Row lab = rows.get("lab-sqlserver");
        assertEquals("sqlserver", lab.kind());
        assertEquals("localhost", lab.host());
        assertEquals("14330", lab.port());
        assertEquals("labdb", lab.database());
        assertEquals("kiln_reader", lab.settings().get("username"));
        assertEquals("sales,hr,ops", lab.settings().get("schemas"));
        assertEquals("true", lab.settings().get("trust_server_certificate"));
        assertNull(lab.settings().get("url"), "the URL is built from host, port and database");
        // rows written before the column existed are shorter: the setting is simply absent
        assertNull(rows.get("lab-db").settings().get("trust_server_certificate"));
        assertEquals("crm,billing", rows.get("lab-db").settings().get("schemas"));
        assertTrue(lab.present().contains("trust_server_certificate"));
    }

    private static Map<String, SourceImporter.Row> byName(List<SourceImporter.Row> rows) {
        return rows.stream().collect(Collectors.toMap(SourceImporter.Row::name, Function.identity()));
    }
}

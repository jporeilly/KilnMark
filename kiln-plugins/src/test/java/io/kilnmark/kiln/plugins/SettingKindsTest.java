package io.kilnmark.kiln.plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.Connectors;

/**
 * Every built-in connector declares the kind of each of its settings, so the connection form
 * shows switches, lists and numbers without guessing from hints (docs/design/connectors.md).
 */
class SettingKindsTest {

    private static Map<String, String> kinds(String kind) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Connector.Setting s : Connectors.load().get(kind).orElseThrow().settings()) {
            out.put(s.key(), s.kind() + (s.defaultValue() == null ? "" : "=" + s.defaultValue()));
        }
        return out;
    }

    @Test
    void databases() {
        assertEquals(Map.of("url", "URL", "username", "TEXT", "schemas", "TEXT"), kinds("postgresql"));
        assertEquals(Map.of("url", "URL", "username", "TEXT", "schemas", "TEXT",
            "trust_server_certificate", "BOOLEAN=false", "isolation", "CHOICE=read-uncommitted"), kinds("sqlserver"));
        Connector.Setting isolation = Connectors.load().get("sqlserver").orElseThrow().settings().stream()
            .filter(s -> s.key().equals("isolation")).findFirst().orElseThrow();
        assertEquals(List.of("read-uncommitted", "snapshot", "read-committed"), isolation.choices());
    }

    @Test
    void mySqlAndMariaDb() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("host", "TEXT");
        expected.put("port", "NUMBER=3306");
        expected.put("database", "TEXT");
        expected.put("username", "TEXT");
        expected.put("tls_mode", "CHOICE=preferred");
        expected.put("server_ca", "TEXT");
        expected.put("tinyint1_as_boolean", "BOOLEAN=true");
        expected.put("schemas", "TEXT");
        assertEquals(expected, kinds("mysql"));
        assertEquals(expected, kinds("mariadb"));
        Connector.Setting tls = Connectors.load().get("mariadb").orElseThrow().settings().stream()
            .filter(s -> s.key().equals("tls_mode")).findFirst().orElseThrow();
        assertEquals(List.of("preferred", "disabled", "required", "verify-ca", "verify-full"), tls.choices());
    }

    @Test
    void filesAndBuckets() {
        Map<String, String> documents = Map.of("keep_text", "BOOLEAN=true", "read_text_on_demand", "BOOLEAN=false");
        Map<String, String> reading = Map.of("encoding", "TEXT", "delimiter", "TEXT", "header", "BOOLEAN=true");
        Map<String, String> files = new LinkedHashMap<>(Map.of("path", "TEXT", "recursive", "BOOLEAN=true"));
        files.putAll(reading);
        files.putAll(documents);
        assertEquals(files, kinds("files"));
        Map<String, String> s3 = new LinkedHashMap<>(Map.of("endpoint", "URL", "region", "TEXT", "bucket", "TEXT",
            "prefix", "TEXT", "access_key_id", "TEXT", "path_style", "BOOLEAN=false"));
        s3.putAll(reading);
        s3.putAll(documents);
        assertEquals(s3, kinds("s3"));
    }

    @Test
    void aSettingWhoseHintOffersTrueOrFalseIsDeclaredBoolean() {
        for (Connector c : Connectors.load().all()) {
            for (Connector.Setting s : c.settings()) {
                if (s.hint().matches("(?s)^\\s*(true|false)\\b.*")) {
                    assertEquals("BOOLEAN", s.kind().name(), c.kind() + "." + s.key());
                }
                assertTrue(Connector.Setting.KINDS.contains(s.kind()), c.kind() + "." + s.key());
                if (s.defaultValue() != null) {
                    assertNull(s.problem(s.defaultValue()), c.kind() + "." + s.key());
                }
            }
        }
    }
}

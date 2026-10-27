package io.kilnmark.kiln.connect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.SettingSpec.Kind;
import io.kilnmark.kiln.connect.Connector.Setting;

class ConnectorSettingTest {

    @Test
    void theOldConstructorStillGivesFreeTextWithNoDefault() {
        Setting s = new Setting("region", "Region", false, "For example eu-west-2", false);
        assertEquals(Kind.TEXT, s.kind());
        assertEquals(List.of(), s.choices());
        assertNull(s.defaultValue());
        assertNull(s.problem("anything at all"));
        assertEquals(Kind.TEXT, new Setting("k", "K", false, null, false, null, null, null).kind());
    }

    @Test
    void booleansAreTrueOrFalseInAnyCase() {
        Setting s = Setting.bool("header", "Header row", true, "hint");
        assertEquals(Kind.BOOLEAN, s.kind());
        assertEquals("true", s.defaultValue());
        assertNull(s.problem("true"));
        assertNull(s.problem(" FALSE "));
        assertNull(s.problem(null));
        assertNull(s.problem(""), "an empty value means the default");
        assertEquals("header: true or false, not 'yes'", s.problem("yes"));
        assertEquals("header: true or false, not '1'", s.problem("1"));
    }

    @Test
    void numbersAndChoicesAndAddresses() {
        Setting n = Setting.number("sample", "Sample", "1000", "hint");
        assertNull(n.problem("250"));
        assertNull(n.problem("2.5"));
        assertNull(n.problem("-1"));
        assertEquals("sample: a number, not 'lots'", n.problem("lots"));
        assertEquals("sample: a number, not '1e9'", n.problem("1e9"));

        Setting c = Setting.choice("isolation", "Isolation", List.of("read-uncommitted", "snapshot"),
            "read-uncommitted", "hint");
        assertEquals(Kind.CHOICE, c.kind());
        assertNull(c.problem("snapshot"));
        assertNull(c.problem("SNAPSHOT"), "choices match ignoring case, as connectors read them");
        assertEquals("isolation: one of read-uncommitted, snapshot, not 'dirty'", c.problem("dirty"));

        Setting u = Setting.url("endpoint", "Endpoint", false, "hint");
        assertEquals(Kind.URL, u.kind());
        assertNull(u.problem("http://storage.example.com:9000"));
        assertNull(u.problem("jdbc:postgresql://db.example.com:5432/sales"));
        assertEquals("endpoint", u.problem("storage example com").split(":")[0]);
    }

    @Test
    void severalValuesAreCheckedOneByOne() {
        Setting s = new Setting("ports", "Ports", false, "hint", true, Kind.NUMBER, List.of(), null);
        assertNull(s.problem("80, 443"));
        assertEquals("ports: a number, not 'web'", s.problem("80,web"));
    }

    @Test
    void declarationsMustBeConsistent() {
        assertThrows(IllegalArgumentException.class,
            () -> new Setting("k", "K", false, "", false, Kind.CHOICE, List.of(), null), "a choice needs choices");
        assertThrows(IllegalArgumentException.class,
            () -> new Setting("k", "K", false, "", false, Kind.TEXT, List.of("a"), null), "only choices have them");
        assertThrows(IllegalArgumentException.class,
            () -> new Setting("k", "K", false, "", false, Kind.COLUMNS, List.of(), null), "not a connector kind");
        assertThrows(IllegalArgumentException.class, () -> Setting.number("k", "K", "many", ""), "default of its kind");
        assertThrows(IllegalArgumentException.class,
            () -> Setting.choice("k", "K", List.of("a", "b"), "c", ""), "default among the choices");
    }

    @Test
    void problemsOfASourceNameEachSetting() {
        List<Setting> settings = List.of(Setting.bool("header", "Header", true, ""),
            Setting.number("rows", "Rows", null, ""), Setting.text("path", "Path", true, ""));
        SourceConfig source = new SourceConfig("s", "files", Map.of("header", "yes", "rows", "ten", "path", "x"), null);
        assertEquals(List.of("header: true or false, not 'yes'", "rows: a number, not 'ten'"),
            Setting.problems(settings, source));
    }
}

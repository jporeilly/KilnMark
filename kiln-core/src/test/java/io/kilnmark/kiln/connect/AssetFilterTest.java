package io.kilnmark.kiln.connect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.StepSpec;

class AssetFilterTest {

    private static AssetFilter patterns(String include, String exclude) {
        return AssetFilter.of(List.of(), include, exclude);
    }

    /** As a database source matches: paths are schema.table. */
    private static AssetFilter tables(String include, String exclude) {
        return AssetFilter.of(List.of(), include, exclude, ".");
    }

    @Test
    void noPatternsKeepEverything() {
        assertTrue(AssetFilter.ALL.patterns("crm.customers", "customers"));
        assertTrue(patterns(null, "").patterns("x", "x"));
    }

    @Test
    void forTablesAPatternWithoutADotMatchesTheNameAndOneWithADotTheSchemaAndName() {
        AssetFilter f = tables("CUST*", null);
        assertTrue(f.patterns("crm.customers", "customers"));
        assertFalse(f.patterns("crm.orders", "orders"));
        assertFalse(tables("crm*", null).patterns("crm.orders", "orders"), "no dot: the table name only");
        assertTrue(tables("crm.*", null).patterns("crm.orders", "orders"), "schema.table paths");
        assertFalse(tables("crm.*", null).patterns("sales.orders", "orders"));
        assertTrue(tables("*.orders", null).patterns("sales.orders", "orders"));
    }

    @Test
    void forFilesAPatternWithoutASlashMatchesTheFileNameOnly() {
        assertTrue(patterns("*.csv", null).patterns("raw/2025/orders.CSV", "orders.CSV"), "file names");
        assertFalse(patterns("raw*", null).patterns("raw/orders.csv", "orders.csv"));
    }

    @Test
    void aPatternWithASlashMatchesPathsAndStarStopsAtASlash() {
        assertTrue(patterns("raw/*", null).patterns("raw/a.csv", "a.csv"));
        assertFalse(patterns("raw/*", null).patterns("raw/2025/a.csv", "a.csv"));
        assertTrue(patterns("raw/**", null).patterns("raw/2025/a.csv", "a.csv"));
        assertTrue(patterns("**/*.csv", null).patterns("a.csv", "a.csv"), "**/ also matches no folder");
        assertTrue(patterns("**/*.csv", null).patterns("x/y/a.csv", "a.csv"));
        assertFalse(patterns("raw/**", null).patterns("rawdata/a.csv", "a.csv"));
    }

    @Test
    void questionMarksSetsAndBraces() {
        assertTrue(patterns("t?", null).patterns("s.t1", "t1"));
        assertFalse(patterns("t?", null).patterns("s.t12", "t12"));
        assertTrue(patterns("t[0-9]", null).patterns("s.t7", "t7"));
        assertFalse(patterns("t[!0-9]", null).patterns("s.t7", "t7"));
        assertTrue(patterns("*.{csv,parquet}", null).patterns("a.parquet", "a.parquet"));
        assertFalse(patterns("*.{csv,parquet}", null).patterns("a.json", "a.json"));
    }

    @Test
    void regexCharactersAreLiteral() {
        assertTrue(patterns("a+b(1)$", null).patterns("a+b(1)$", "a+b(1)$"));
        assertFalse(patterns("a.b", null).patterns("axb", "axb"));
    }

    @Test
    void includeThenExclude() {
        AssetFilter f = tables("crm.*;sales.*", "*_backup, tmp_*");
        assertTrue(f.patterns("crm.customers", "customers"));
        assertTrue(f.patterns("sales.orders", "orders"));
        assertFalse(f.patterns("crm.customers_backup", "customers_backup"));
        assertFalse(f.patterns("sales.tmp_load", "tmp_load"));
        assertFalse(f.patterns("hr.people", "people"));
    }

    @Test
    void aPathologicalPatternFinishesAtOnce() {
        String name = "a".repeat(63);
        long start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            assertFalse(patterns("*a*a*a*a*a*a*a*a*b", null).patterns(name, name));
            assertFalse(patterns("**a**a**a**a**a**a**a**b", null).patterns(name + "/" + name, name));
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertTrue(ms < 2000, "200 matches took " + ms + " ms");
        assertTrue(patterns("*a*a*a*a*a*a*a*a*b", null).patterns(name + "b", name + "b"));
    }

    @Test
    void patternsAndAlternativesAreCapped() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> patterns("x".repeat(201), null)).getMessage()
            .contains("at most 200"));
        String many = String.join(",", java.util.Collections.nCopies(101, "a*"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> patterns(many, null)).getMessage()
            .contains("at most 100"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> patterns("{a,b}{c,d}{e,f}{g,h}{i,j}{k,l}{m,n}",
            null)).getMessage().contains("too many"));
        assertTrue(patterns("{a,b}{c,d}{e,f}{g,h}{i,j}{k,l}", null).patterns("bcfgjl", "bcfgjl"), "64 alternatives");
        assertTrue(patterns("x{a,{b,c}}y", null).patterns("xcy", "xcy"), "nested braces");
        assertTrue(patterns("a\\*", null).patterns("a*", "a*"));
        assertFalse(patterns("a\\*", null).patterns("ab", "ab"));
    }

    @Test
    void listsSplitOutsideBraces() {
        assertEquals(List.of("*.{csv,tsv}", "raw/**", "x"), AssetFilter.split("*.{csv,tsv}, raw/** ;x;"));
    }

    @Test
    void badGlobsNameTheSetting() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> patterns("*.{csv", null));
        assertTrue(e.getMessage().startsWith("include:"), e.getMessage());
        assertTrue(AssetFilter.problems(new SourceConfig("s", "k", Map.of("exclude", "[abc"), null)).get(0)
            .startsWith("exclude:"));
        assertTrue(AssetFilter.problems(new SourceConfig("s", "k", Map.of("include", "a}"), null)).get(0)
            .startsWith("include:"));
        assertTrue(AssetFilter.problems(new SourceConfig("s", "k", Map.of("include", "ok*"), null)).isEmpty());
    }

    @Test
    void containersMatchExactlyOrIgnoringCase() {
        AssetFilter f = AssetFilter.of(List.of("Sales", "crm"), null, null);
        assertTrue(f.container("Sales"));
        assertTrue(f.container("CRM"));
        assertFalse(f.container("hr"));
        assertTrue(f.allContainers().container("hr"));
    }

    @Test
    void applyUsesTheConnectorsContainerSetting() throws Exception {
        Connector c = new Fake();
        SourceConfig s = new SourceConfig("s", "fake", Map.of("containers", "a", "exclude", "*2"), null);
        List<AssetInfo> kept = AssetFilter.of(c, s).apply(c, s, List.of(asset("a", "x1"), asset("a", "x2"),
            asset("b", "x3")));
        assertEquals(List.of("a.x1"), kept.stream().map(AssetInfo::path).toList());
        // the default listing counts from the catalogue, ignoring the choice but not the patterns
        assertEquals(List.of(new Connector.ContainerInfo("a", 1), new Connector.ContainerInfo("b", 1)),
            c.containers(s));
    }

    private static AssetInfo asset(String container, String name) {
        return new AssetInfo(container + "." + name, "table", container, name, null, List.of(), List.of(), false, true,
            Map.of());
    }

    private static final class Fake implements Connector {
        @Override
        public String kind() {
            return "fake";
        }

        @Override
        public String displayName() {
            return "Fake";
        }

        @Override
        public List<Setting> settings() {
            return List.of();
        }

        @Override
        public List<String> validate(SourceConfig source) {
            return List.of();
        }

        @Override
        public List<AssetInfo> catalog(SourceConfig source) {
            assertFalse(source.settings().containsKey("containers"), "the default listing asks for everything");
            return List.of(AssetFilterTest.asset("a", "x1"), AssetFilterTest.asset("a", "x2"),
                AssetFilterTest.asset("b", "x3"));
        }

        @Override
        public StepSpec reader(SourceConfig source, AssetInfo asset, ReadOptions read) {
            throw new UnsupportedOperationException();
        }
    }
}

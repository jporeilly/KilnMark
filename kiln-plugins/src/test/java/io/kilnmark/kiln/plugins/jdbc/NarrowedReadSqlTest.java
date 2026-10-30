package io.kilnmark.kiln.plugins.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.connect.ReadFilter;

/**
 * The SQL each database's reader writes for a narrowed read (docs/design/pipelines.md, "The read
 * node's columns and where"): its own quoting, its own row limit, every value a bound {@code ?}.
 */
class NarrowedReadSqlTest {

    private static AssetInfo orders(String... columns) {
        List<AssetInfo.FieldInfo> fields = new ArrayList<>();
        for (int i = 0; i < columns.length; i++) {
            fields.add(new AssetInfo.FieldInfo(columns[i], i + 1, "varchar", "text", true, null));
        }
        return new AssetInfo("shop.orders", "table", "shop", "orders", null, fields, List.of(), false, true,
            Map.of("schema", "shop", "table", "orders"));
    }

    private static final ReadFilter FILTER = new ReadFilter.And(List.of(
        new ReadFilter.In("status", List.of("delivered", "shipped"), false),
        new ReadFilter.Compare("shipping_fee", ">=", new BigDecimal("5")),
        new ReadFilter.Not(new ReadFilter.Between("ordered_on", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), false)),
        new ReadFilter.Like("note", "gift%"),
        new ReadFilter.Compare("gift_wrap", "=", true)));

    private static String where(JdbcConnector c, List<Object> values) {
        return c.whereSql(FILTER, values);
    }

    @Test
    void eachDatabaseWritesItsOwnSqlWithEveryValueBound() {
        List<Object> values = new ArrayList<>();
        String w = where(new PostgresConnector(), values);
        assertEquals("(\"status\" IN (?, ?) AND \"shipping_fee\" >= ? AND (NOT \"ordered_on\" BETWEEN ? AND ?) AND "
            + "\"note\" LIKE ? AND \"gift_wrap\" = ?)", w);
        assertEquals(List.of("delivered", "shipped", new BigDecimal("5"), LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 31), "gift%", true), values);
        assertEquals("SELECT \"order_id\", \"status\" FROM \"shop\".\"orders\" WHERE " + w + " LIMIT 10",
            new PostgresConnector().selectSql(orders("order_id", "status"), 10, true, w));

        String my = new MySqlConnector().whereSql(FILTER, new ArrayList<>());
        assertTrue(my.startsWith("(`status` IN (?, ?)"), my);
        assertEquals("SELECT `order_id` FROM `shop`.`orders` WHERE x = ? LIMIT 5",
            new MySqlConnector().selectSql(orders("order_id"), 5, true, "x = ?"));

        assertEquals("SELECT TOP (5) [order_id] FROM [shop].[orders] WHERE x = ?",
            new SqlServerConnector().selectSql(orders("order_id"), 5, true, "x = ?"));
        assertEquals("SELECT * FROM [shop].[orders] WHERE x = ?",
            new SqlServerConnector().selectSql(orders("order_id"), 0, false, "x = ?"));

        assertEquals("SELECT \"order_id\" FROM \"shop\".\"orders\" WHERE x = ? FETCH FIRST 5 ROWS ONLY",
            new OracleConnector().selectSql(orders("order_id"), 5, true, "x = ?"));
    }

    /** Oracle binds no true/false: that part is left to the read node, the rest still goes to Oracle. */
    @Test
    void whatADatabaseCannotTakeIsLeftOutOfAnAndOnly() {
        List<Object> values = new ArrayList<>();
        String w = new OracleConnector().whereSql(FILTER, values);
        assertFalse(w.contains("gift_wrap"), w);
        assertFalse(values.contains(true), "its value is not bound either");
        assertEquals(6, values.size());
        assertNull(new OracleConnector().whereSql(new ReadFilter.Or(List.of(new ReadFilter.Compare("a", "=", 1L),
            new ReadFilter.Compare("b", "=", true))), new ArrayList<>()), "half of an or cannot go");
        assertNull(new OracleConnector().whereSql(new ReadFilter.Not(new ReadFilter.Compare("b", "=", true)),
            new ArrayList<>()));
        // Under not, leaving part of an and out would let fewer rows through: all of it or nothing.
        List<Object> none = new ArrayList<>();
        assertNull(new OracleConnector().whereSql(new ReadFilter.Not(new ReadFilter.And(List.of(
            new ReadFilter.Compare("b", "=", true), new ReadFilter.Compare("n", ">", 5L)))), none));
        assertEquals(List.of(), none, "nothing bound");
        assertEquals("(NOT (\"b\" = ? AND \"n\" > ?))", new PostgresConnector().whereSql(new ReadFilter.Not(
            new ReadFilter.And(List.of(new ReadFilter.Compare("b", "=", true), new ReadFilter.Compare("n", ">", 5L)))),
            new ArrayList<>()));
    }

    /** Only columns stored as Kilnmark reads them are compared by the database. */
    @Test
    void theDatabaseComparesOnlyColumnsStoredAsTheyAreRead() {
        PostgresConnector pg = new PostgresConnector();
        assertTrue(pg.comparesAsRead("text", "varchar(20)"));
        assertTrue(pg.comparesAsRead("text", "text"));
        assertTrue(pg.comparesAsRead("text", "bpchar"));
        assertFalse(pg.comparesAsRead("text", "uuid"));
        assertFalse(pg.comparesAsRead("text", "jsonb"));
        assertFalse(pg.comparesAsRead("text", "numeric"), "a number without a precision, read as text");
        assertTrue(pg.comparesAsRead("boolean", "bool"));
        assertTrue(pg.comparesAsRead("int64", "int8"));
        MySqlConnector my = new MySqlConnector();
        assertTrue(my.comparesAsRead("text", "enum('placed','paid')"));
        assertTrue(my.comparesAsRead("text", "mediumtext"));
        assertFalse(my.comparesAsRead("text", "json"));
        assertFalse(my.comparesAsRead("text", "geometry"));
        assertFalse(my.comparesAsRead("boolean", "tinyint(1)"), "2 reads as true, but = 1 would drop it");
        assertTrue(new OracleConnector().comparesAsRead("text", "VARCHAR2(100 CHAR)"));
        assertFalse(new OracleConnector().comparesAsRead("text", "CLOB"));
        assertFalse(new OracleConnector().comparesAsRead("text", "NUMBER"));
        assertFalse(new SqlServerConnector().comparesAsRead("text", "ntext"));
        assertTrue(new SqlServerConnector().comparesAsRead("text", "nvarchar"));
    }

    @Test
    void namesAreQuotedAsEachDatabaseQuotes() {
        ReadFilter odd = new ReadFilter.Compare("we\"ird]`col", "=", 1L);
        assertEquals("\"we\"\"ird]`col\" = ?", new PostgresConnector().whereSql(odd, new ArrayList<>()));
        assertEquals("[we\"ird]]`col] = ?", new SqlServerConnector().whereSql(odd, new ArrayList<>()));
        assertTrue(new MySqlConnector().whereSql(odd, new ArrayList<>()).startsWith("`we\"ird]``col`"));
    }

    @Test
    void theValuesTravelInTheSettingsWithTheirTypes() {
        Map<String, String> s = new HashMap<>();
        List<Object> values = List.of(5L, new BigDecimal("2.50"), 1.5, true, LocalDate.of(2026, 10, 30), "a:b'c");
        JdbcTableInput.putValues(s, values);
        assertEquals("text:a:b'c", s.get("value.6"));
        assertEquals(values, JdbcTableInput.values(s));
    }

    @Test
    void aGenericDatabaseDoesNotNarrowItsReads() {
        assertNull(new GenericJdbcConnector().selectSql(orders("order_id"), 0, true, null));
        assertFalse(new GenericJdbcConnector().narrowsReads(null, orders("order_id")));
        assertTrue(new PostgresConnector().narrowsReads(null, orders("order_id")));
    }
}

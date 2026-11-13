package io.kilnmark.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.connect.TableDetail;
import io.kilnmark.kiln.plugins.jdbc.DdlWriter.Dialect;

/** The SQL generator (ADR 0045, D2) from a table as Explore reads it: text only, nothing taken as text from the catalogue. */
class SqlGeneratorTest {

    private static final TableDetail ORDERS = new TableDetail("sales.orders", "sales", "orders", "table", List.of(
        new TableDetail.Column("order_id", 1, "int8", null, null, false, false, true, "int64"),
        new TableDetail.Column("customer", 2, "int4", null, null, false, false, false, "int32"),
        new TableDetail.Column("total", 3, "numeric", 12, 2, false, true, false, "decimal"),
        new TableDetail.Column("status", 4, "varchar", 12, null, false, true, false, "text"),
        new TableDetail.Column("doc", 5, "jsonb", null, null, true, false, false, "text"),
        new TableDetail.Column("amount", 6, "numeric", 131089, 0, true, false, false, "decimal")),
        List.of("order_id"),
        List.of(new TableDetail.ForeignKey("orders_customer", "sales.orders", List.of("customer"), "sales.customers",
            List.of("id"))),
        List.of(),
        List.of(new TableDetail.Index("orders_pkey", List.of("order_id"), true, true, null, false),
            new TableDetail.Index("orders_customer_ix", List.of("customer"), false, false, null, false),
            new TableDetail.Index("orders_lower", List.of("(expression)"), false, false, null, false),
            new TableDetail.Index("orders_open", List.of("status"), false, false, null, true)),
        126000L);

    private static SqlGenerator.Request ask(String statement) {
        return new SqlGenerator.Request(statement, "sales.orders", null, null, null);
    }

    @Test
    void createTableKeepsColumnsKeyAndPlainIndexesAndSaysWhatItLeftOut() {
        Views.GeneratedSql g = SqlGenerator.generate(ask("create-table"), ORDERS, Dialect.POSTGRESQL, Dialect.POSTGRESQL);
        assertEquals("postgresql", g.dialect());
        String create = g.sql().get(0);
        assertTrue(create.contains("\"order_id\" bigint NOT NULL"), create);
        assertTrue(create.contains("\"total\" numeric(12,2) NOT NULL"), create);
        assertTrue(create.contains("\"status\" varchar(12) NOT NULL"), create);
        assertTrue(create.contains("\"doc\" pg_catalog.text"), "a type without a kind of its own is written as text: " + create);
        assertTrue(create.contains("\"amount\" numeric,"), "PostgreSQL keeps a numeric without precision: " + create);
        assertTrue(create.contains("PRIMARY KEY (\"order_id\")"), create);
        assertFalse(create.contains("jsonb") || create.contains("DEFAULT"), create);
        assertEquals(List.of(create, "CREATE INDEX \"orders_customer_ix\" ON \"sales\".\"orders\" (\"customer\")"), g.sql());
        String leftOut = String.join("\n", g.leftOut());
        assertTrue(leftOut.contains("doc (jsonb)"), leftOut);
        assertTrue(leftOut.contains("orders_lower") && leftOut.contains("orders_open"), leftOut);
        assertTrue(leftOut.contains("Foreign keys are not written"), leftOut);
        assertTrue(leftOut.contains("Defaults and values the database fills (order_id, total, status)"), leftOut);
    }

    @Test
    void keepsTextKeysIndexableAndNumbersAsNumbers() {
        TableDetail oracle = new TableDetail("APP.T", "APP", "T", "table", List.of(
            new TableDetail.Column("ID", 1, "NUMBER", null, null, false, false, true, "text"),
            new TableDetail.Column("CODE", 2, "VARCHAR2", 20, null, false, false, false, "text")),
            List.of("ID"), List.of(), List.of(), List.of(), null);
        String o = SqlGenerator.generate(ask("create-table"), oracle, Dialect.ORACLE, Dialect.ORACLE).sql().get(0);
        assertTrue(o.contains("\"ID\" NUMBER NOT NULL"), "an Oracle NUMBER key stays a number: " + o);

        TableDetail uuid = new TableDetail("s.t", "s", "t", "table", List.of(
            new TableDetail.Column("id", 1, "uuid", null, null, false, false, false, "text")),
            List.of("id"), List.of(), List.of(), List.of(), null);
        Views.GeneratedSql m = SqlGenerator.generate(ask("create-table"), uuid, Dialect.MYSQL, Dialect.POSTGRESQL);
        assertTrue(m.sql().get(0).contains("`id` varchar(768) NOT NULL"), m.sql().get(0));
        assertTrue(String.join(" ", m.leftOut()).contains("cannot build on text"), m.leftOut().toString());

        TableDetail longKey = new TableDetail("s.t", "s", "t", "table", List.of(
            new TableDetail.Column("code", 1, "varchar", 5000, null, false, false, false, "text")),
            List.of("code"), List.of(), List.of(), List.of(), null);
        String ss = SqlGenerator.generate(ask("create-table"), longKey, Dialect.SQLSERVER, Dialect.POSTGRESQL).sql().get(0);
        assertTrue(ss.contains("[code] nvarchar(450) NOT NULL"), "a long key column gets a length SQL Server can index: " + ss);

        TableDetail parted = new TableDetail("s.p", "s", "p", "partitioned table", ORDERS.columns(), List.of(), List.of(),
            List.of(), List.of(), null);
        assertThrows(IllegalArgumentException.class, () -> SqlGenerator.generate(ask("truncate"), parted,
            Dialect.POSTGRESQL, Dialect.POSTGRESQL));
    }

    @Test
    void writesForAnotherDatabaseWhenAsked() {
        Views.GeneratedSql g = SqlGenerator.generate(new SqlGenerator.Request("create-table", "sales.orders", "sqlserver",
            null, null), ORDERS, SqlGenerator.dialect("sqlserver", "postgresql"), Dialect.POSTGRESQL);
        assertEquals("sqlserver", g.dialect());
        assertTrue(g.sql().get(0).startsWith("CREATE TABLE [sales].[orders]"), g.sql().get(0));
        assertTrue(g.sql().get(0).contains("[status] nvarchar(12) NOT NULL"), g.sql().get(0));
    }

    @Test
    void addsColumnsAndIndexesOnlyWithNamesThatFit() {
        Views.GeneratedSql add = SqlGenerator.generate(new SqlGenerator.Request("add-columns", "sales.orders", null,
            List.of(new SqlGenerator.NewColumn("shipped_on", "date", null, null, null, true)), null), ORDERS,
            Dialect.POSTGRESQL, Dialect.POSTGRESQL);
        assertEquals(List.of("ALTER TABLE \"sales\".\"orders\" ADD COLUMN \"shipped_on\" pg_catalog.date"), add.sql());
        assertThrows(IllegalArgumentException.class, () -> SqlGenerator.generate(new SqlGenerator.Request("add-columns",
            "sales.orders", null, List.of(new SqlGenerator.NewColumn("TOTAL", "text", null, null, null, true)), null),
            ORDERS, Dialect.POSTGRESQL, Dialect.POSTGRESQL), "a column of that name is there");
        assertThrows(IllegalArgumentException.class, () -> SqlGenerator.generate(new SqlGenerator.Request("add-columns",
            "sales.orders", null, List.of(new SqlGenerator.NewColumn("x", "jsonb", null, null, null, true)), null),
            ORDERS, Dialect.POSTGRESQL, Dialect.POSTGRESQL), "only Kilnmark's kinds");

        Views.GeneratedSql ix = SqlGenerator.generate(new SqlGenerator.Request("create-index", "sales.orders", null, null,
            new SqlGenerator.NewIndex("orders_total", List.of("total", "status"), true)), ORDERS, Dialect.MYSQL, Dialect.POSTGRESQL);
        assertEquals(List.of("CREATE UNIQUE INDEX `orders_total` ON `sales`.`orders` (`total`, `status`)"), ix.sql());
        assertThrows(IllegalArgumentException.class, () -> SqlGenerator.generate(new SqlGenerator.Request("create-index",
            "sales.orders", null, null, new SqlGenerator.NewIndex("x", List.of("nope"), false)), ORDERS,
            Dialect.MYSQL, Dialect.POSTGRESQL));
    }

    @Test
    void dropsAndTruncatesOnlyWhatIsThere() {
        assertEquals(List.of("DROP INDEX \"sales\".\"orders_customer_ix\""), SqlGenerator.generate(new SqlGenerator.Request(
            "drop-index", "sales.orders", null, null, new SqlGenerator.NewIndex("orders_customer_ix", null, null)), ORDERS,
            Dialect.POSTGRESQL, Dialect.POSTGRESQL).sql());
        assertThrows(IllegalArgumentException.class, () -> SqlGenerator.generate(new SqlGenerator.Request("drop-index",
            "sales.orders", null, null, new SqlGenerator.NewIndex("orders_pkey", null, null)), ORDERS,
            Dialect.POSTGRESQL, Dialect.POSTGRESQL), "the key's index is not dropped here");
        assertEquals(List.of("TRUNCATE ONLY \"sales\".\"orders\""),
            SqlGenerator.generate(ask("truncate"), ORDERS, Dialect.POSTGRESQL, Dialect.POSTGRESQL).sql());
        TableDetail view = new TableDetail("sales.v", "sales", "v", "view", ORDERS.columns(), List.of(), List.of(),
            List.of(), List.of(), null);
        assertThrows(IllegalArgumentException.class, () -> SqlGenerator.generate(ask("drop-table"), view,
            Dialect.POSTGRESQL, Dialect.POSTGRESQL));
        assertThrows(IllegalArgumentException.class, () -> SqlGenerator.generate(ask("delete"), ORDERS,
            Dialect.POSTGRESQL, Dialect.POSTGRESQL));
        assertThrows(IllegalArgumentException.class, () -> SqlGenerator.dialect(null, "jdbc"));
    }
}

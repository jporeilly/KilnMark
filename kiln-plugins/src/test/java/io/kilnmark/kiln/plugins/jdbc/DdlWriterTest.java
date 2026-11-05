package io.kilnmark.kiln.plugins.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.kiln.plugins.jdbc.DdlWriter.Column;
import io.kilnmark.kiln.plugins.jdbc.DdlWriter.Dialect;
import io.kilnmark.kiln.plugins.jdbc.DdlWriter.Index;
import io.kilnmark.kiln.plugins.jdbc.JdbcColumn.Kind;

/** The SQL generator (ADR 0045): each statement in each dialect, written as the write node writes types. */
class DdlWriterTest {

    private static final List<Column> ORDERS = List.of(
        new Column("order_id", Kind.INT64, 0, 0, 0, false),
        new Column("customer", Kind.INT32, 0, 0, 0, false),
        new Column("total", Kind.DECIMAL, 0, 12, 2, true),
        new Column("note", Kind.TEXT, 200, 0, 0, true),
        new Column("placed at", Kind.TIMESTAMP_TZ, 0, 0, 0, false));

    private static DdlWriter.Generated create(Dialect d) throws ConnectorException {
        return DdlWriter.of(d).createTable("sales", "orders", ORDERS, List.of("order_id"),
            List.of(new Index("orders_customer", List.of("customer", "placed at"), false)));
    }

    @Test
    void createsAPostgresTableWithNullsKeyAndIndexes() throws Exception {
        DdlWriter.Generated g = create(Dialect.POSTGRESQL);
        assertEquals(List.of("""
            CREATE TABLE "sales"."orders" (
              "order_id" bigint NOT NULL,
              "customer" integer NOT NULL,
              "total" numeric(12,2),
              "note" varchar(200),
              "placed at" timestamptz NOT NULL,
              PRIMARY KEY ("order_id")
            )""",
            "CREATE INDEX \"orders_customer\" ON \"sales\".\"orders\" (\"customer\", \"placed at\")"), g.statements());
        assertTrue(g.leftOut().isEmpty());
    }

    @Test
    void writesEachDatabasesOwnTypesAndQuoting() throws Exception {
        String sqlServer = create(Dialect.SQLSERVER).statements().get(0);
        assertTrue(sqlServer.startsWith("CREATE TABLE [sales].[orders] ("), sqlServer);
        assertTrue(sqlServer.contains("[note] nvarchar(200)"), sqlServer);
        assertTrue(sqlServer.contains("[placed at] datetimeoffset(6) NOT NULL"), sqlServer);

        String mysql = create(Dialect.MYSQL).statements().get(0);
        assertTrue(mysql.startsWith("CREATE TABLE `sales`.`orders` ("), mysql);
        assertTrue(mysql.contains("`total` decimal(12,2)"), mysql);
        assertTrue(mysql.endsWith(") DEFAULT CHARACTER SET utf8mb4"), "as create writes it: " + mysql);

        // names read from Oracle's own catalogue are written exactly as stored
        DdlWriter.Generated oracle = DdlWriter.of(Dialect.ORACLE).createTable("SALES", "ORDERS",
            List.of(new Column("ID", Kind.INT64, 0, 0, 0, false), new Column("note", Kind.TEXT, 0, 0, 0, true)),
            List.of("ID"), List.of(new Index("ix_note", List.of("note"), false)));
        assertTrue(oracle.statements().get(0).contains("\"note\" CLOB"), oracle.statements().get(0));
        // Oracle puts an unqualified index in the connected user's schema: the name is qualified
        assertEquals("CREATE INDEX \"SALES\".\"ix_note\" ON \"SALES\".\"ORDERS\" (\"note\")",
            oracle.statements().get(1));
        assertEquals("DROP INDEX \"SALES\".\"ix_note\"",
            DdlWriter.of(Dialect.ORACLE).dropIndex("SALES", "ORDERS", "ix_note").statements().get(0));

        // written for Oracle from another database, every plain name is stored in upper case
        DdlWriter.Generated converted = DdlWriter.of(Dialect.ORACLE, Dialect.POSTGRESQL).createTable("sales", "orders",
            ORDERS, List.of("order_id"), List.of(new Index("orders_customer", List.of("customer"), false)));
        assertTrue(converted.statements().get(0).startsWith("CREATE TABLE \"SALES\".\"ORDERS\""),
            converted.statements().get(0));
        assertTrue(converted.statements().get(0).contains("\"ORDER_ID\" NUMBER(19) NOT NULL"));
        assertEquals("CREATE INDEX \"SALES\".\"ORDERS_CUSTOMER\" ON \"SALES\".\"ORDERS\" (\"CUSTOMER\")",
            converted.statements().get(1));
    }

    @Test
    void quotesNamesThatHoldQuotes() throws Exception {
        String s = DdlWriter.of(Dialect.POSTGRESQL).dropTable("odd\"schema", "t\"; DROP TABLE x; --").statements().get(0);
        assertEquals("DROP TABLE \"odd\"\"schema\".\"t\"\"; DROP TABLE x; --\"", s);
        String m = DdlWriter.of(Dialect.MYSQL).truncate("a`b", "c").statements().get(0);
        assertEquals("TRUNCATE TABLE `a``b`.`c`", m);
        String q = DdlWriter.of(Dialect.SQLSERVER).dropTable("dbo", "x]y").statements().get(0);
        assertEquals("DROP TABLE [dbo].[x]]y]", q);
    }

    @Test
    void altersDropsAndTruncatesInEachForm() throws Exception {
        assertEquals(List.of("ALTER TABLE \"s\".\"t\" ADD COLUMN \"flag\" boolean NOT NULL"),
            DdlWriter.of(Dialect.POSTGRESQL).addColumns("s", "t", List.of(new Column("flag", Kind.BOOLEAN, 0, 0, 0, false)))
                .statements());
        assertEquals(List.of("ALTER TABLE [s].[t] ADD [flag] bit"),
            DdlWriter.of(Dialect.SQLSERVER).addColumns("s", "t", List.of(new Column("flag", Kind.BOOLEAN, 0, 0, 0, true)))
                .statements());
        assertEquals("DROP INDEX \"s\".\"ix\"", DdlWriter.of(Dialect.POSTGRESQL).dropIndex("s", "t", "ix").statements().get(0));
        assertEquals("DROP INDEX [ix] ON [s].[t]", DdlWriter.of(Dialect.SQLSERVER).dropIndex("s", "t", "ix").statements().get(0));
        assertEquals("DROP INDEX `ix` ON `s`.`t`", DdlWriter.of(Dialect.MARIADB).dropIndex("s", "t", "ix").statements().get(0));
        assertEquals("TRUNCATE ONLY \"s\".\"t\"", DdlWriter.of(Dialect.POSTGRESQL).truncate("s", "t").statements().get(0));
        assertEquals("DROP TABLE \"s\".\"t\"", DdlWriter.of(Dialect.ORACLE).dropTable("s", "t").statements().get(0),
            "no PURGE: the table goes to the recycle bin");
        assertEquals("CREATE UNIQUE INDEX \"ux\" ON \"s\".\"t\" (\"a\")",
            DdlWriter.of(Dialect.POSTGRESQL).createIndex("s", "t", new Index("ux", List.of("a"), true)).statements().get(0));
    }

    @Test
    void saysWhatItLeftOutAndRefusesWhatItCannotWrite() throws Exception {
        DdlWriter.Generated g = DdlWriter.of(Dialect.SQLSERVER).createTable("s", "t",
            List.of(new Column("long_text", Kind.TEXT, 9000, 0, 0, true)), List.of(), List.of());
        assertTrue(g.statements().get(0).contains("[long_text] nvarchar(max)"), g.statements().get(0));
        assertEquals(1, g.leftOut().size());

        DdlWriter pg = DdlWriter.of(Dialect.POSTGRESQL);
        assertThrows(ConnectorException.class, () -> pg.createTable("s", "t", List.of(), List.of(), List.of()));
        assertThrows(ConnectorException.class, () -> pg.createTable("s", "t", ORDERS, List.of("nope"), List.of()));
        assertThrows(ConnectorException.class, () -> pg.createTable("s", "t", ORDERS, List.of(),
            List.of(new Index("ix", List.of("missing"), false))));
        assertThrows(ConnectorException.class, () -> DdlWriter.of(Dialect.SQLSERVER).addColumns("s", "t",
            List.of(new Column("d", Kind.DECIMAL, 0, 60, 2, true))), "SQL Server keeps 38 digits");
        assertTrue(DdlWriter.of(Dialect.MYSQL).addColumns("s", "t", List.of(new Column("d", Kind.DECIMAL, 0, 60, 2, true)))
            .statements().get(0).contains("decimal(60,2)"), "MySQL keeps 65");
        assertThrows(ConnectorException.class, () -> DdlWriter.of(Dialect.MYSQL).addColumns("s", "t",
            List.of(new Column("d", Kind.DECIMAL, 0, 40, 35, true))), "MySQL keeps 30 after the point");
        assertThrows(ConnectorException.class, () -> DdlWriter.of(Dialect.ORACLE, Dialect.POSTGRESQL).createTable("s", "t",
            List.of(new Column("id", Kind.INT32, 0, 0, 0, true), new Column("ID", Kind.INT32, 0, 0, 0, true)), List.of(),
            List.of()), "id and ID are one column in Oracle");
        assertThrows(ConnectorException.class, () -> pg.addColumns("s", "t",
            List.of(new Column("a", Kind.INT32, 0, 0, 0, true), new Column("A", Kind.INT32, 0, 0, 0, true))));
        assertThrows(ConnectorException.class, () -> pg.dropTable("s", "x".repeat(64)));
        assertThrows(ConnectorException.class, () -> pg.dropTable("s", "line\nbreak"));
        assertThrows(ConnectorException.class, () -> pg.createTable("s", "t",
            List.of(new Column("a", Kind.INT32, 0, 0, 0, true), new Column("a", Kind.TEXT, 0, 0, 0, true)), List.of(),
            List.of()));
    }

    @Test
    void writesADecimalWithoutPrecisionAsEachDatabaseCan() throws Exception {
        Column any = new Column("amount", Kind.DECIMAL, 0, 0, 4, true);
        assertTrue(DdlWriter.of(Dialect.POSTGRESQL).addColumns("s", "t", List.of(any)).statements().get(0)
            .endsWith("\"amount\" numeric"));
        assertTrue(DdlWriter.of(Dialect.ORACLE).addColumns("S", "T", List.of(any)).statements().get(0)
            .endsWith("NUMBER"));
        DdlWriter.Generated m = DdlWriter.of(Dialect.MYSQL).addColumns("s", "t", List.of(any));
        assertTrue(m.statements().get(0).endsWith("`amount` decimal(65,10)"), m.statements().get(0));
        assertTrue(m.leftOut().stream().anyMatch(l -> l.contains("no precision of its own")));
        assertTrue(DdlWriter.of(Dialect.POSTGRESQL).addColumns("s", "t",
            List.of(new Column("n", Kind.INT32, 0, 0, 0, false))).leftOut().get(0).contains("already has rows"));
    }

    @Test
    void knowsEachSourceKindsDialectAndEachKindByName() {
        assertEquals(Optional.of(Dialect.MARIADB), Dialect.forKind("mariadb"));
        assertEquals(Optional.empty(), Dialect.forKind("jdbc"));
        assertEquals(Kind.INT32, DdlWriter.kind("int32"));
        assertNull(DdlWriter.kind("jsonb"));
        assertNull(DdlWriter.kind(null));
    }
}

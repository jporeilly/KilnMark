package io.kilnmark.kiln.plugins.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.connect.TableDetail;

/** Explore (ADR 0045) on PostgreSQL: columns, keys both ways, indexes, estimates and exact counts. */
@Testcontainers
class PostgresExploreTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer(
        DockerImageName.parse("docker.io/library/postgres:18").asCompatibleSubstituteFor("postgres"));

    private static final PostgresConnector CONNECTOR = new PostgresConnector();
    private static final Duration LIMIT = Duration.ofSeconds(30);

    @BeforeAll
    static void load() throws Exception {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA shop");
            s.execute("""
                CREATE TABLE shop.customers (
                    region  varchar(8) NOT NULL,
                    id      integer NOT NULL,
                    email   varchar(200),
                    balance numeric(12,2) DEFAULT 0,
                    code    integer GENERATED ALWAYS AS IDENTITY,
                    PRIMARY KEY (region, id)
                )""");
            s.execute("""
                CREATE TABLE shop.orders (
                    order_id integer PRIMARY KEY,
                    region   varchar(8) NOT NULL,
                    customer integer NOT NULL,
                    CONSTRAINT orders_customer FOREIGN KEY (region, customer)
                        REFERENCES shop.customers (region, id)
                )""");
            s.execute("CREATE UNIQUE INDEX customers_email ON shop.customers (email)");
            s.execute("CREATE INDEX orders_customer_ix ON shop.orders (customer, region)");
            // an expression and a partial index can quote values: neither may show its text
            s.execute("CREATE INDEX customers_lower ON shop.customers (lower(email))");
            s.execute("CREATE INDEX customers_secret ON shop.customers (id) WHERE email = 'ceo@example.com'");
            s.execute("CREATE VIEW shop.big_spenders AS SELECT * FROM shop.customers WHERE balance > 1000");
            s.execute("INSERT INTO shop.customers (region, id, email) SELECT 'uk', n, 'p' || n || '@example.com' "
                + "FROM generate_series(1, 300) n");
            s.execute("ANALYZE shop.customers");
            // _ is a pattern wildcard: my_table must not pick up myxtable's columns
            s.execute("CREATE TABLE shop.my_table (a integer)");
            s.execute("CREATE TABLE shop.myxtable (b integer, c integer)");
        }
    }

    private static SourceConfig source() {
        return new SourceConfig("shopdb", "postgresql", Map.of("url", PG.getJdbcUrl(), "username", PG.getUsername()),
            PG.getPassword());
    }

    @Test
    void showsColumnsWithoutTheTextOfDefaults() throws Exception {
        TableDetail t = CONNECTOR.explore(source(), "shop.customers", LIMIT);
        assertEquals("shop.customers", t.path());
        assertEquals("table", t.kind());
        assertEquals(List.of("region", "id", "email", "balance", "code"),
            t.columns().stream().map(TableDetail.Column::name).toList());
        TableDetail.Column region = t.columns().get(0);
        assertEquals(8, region.size());
        assertFalse(region.nullable());
        TableDetail.Column balance = t.columns().get(3);
        assertEquals(12, balance.size());
        assertEquals(2, balance.scale());
        assertTrue(balance.hasDefault());
        assertTrue(t.columns().get(4).generated());
        assertFalse(t.columns().get(2).hasDefault());
        assertEquals(List.of("region", "id"), t.primaryKey());
    }

    @Test
    void showsForeignKeysBothWays() throws Exception {
        TableDetail orders = CONNECTOR.explore(source(), "shop.orders", LIMIT);
        assertEquals(1, orders.foreignKeys().size());
        TableDetail.ForeignKey fk = orders.foreignKeys().get(0);
        assertEquals("orders_customer", fk.name());
        assertEquals("shop.orders", fk.table());
        assertEquals(List.of("region", "customer"), fk.columns());
        assertEquals("shop.customers", fk.toTable());
        assertEquals(List.of("region", "id"), fk.toColumns());

        TableDetail customers = CONNECTOR.explore(source(), "shop.customers", LIMIT);
        assertEquals(List.of("shop.orders"), customers.referencedBy().stream().map(TableDetail.ForeignKey::table).toList());
    }

    @Test
    void showsIndexesWithoutExpressionsOrConditions() throws Exception {
        TableDetail t = CONNECTOR.explore(source(), "shop.customers", LIMIT);
        Map<String, TableDetail.Index> byName = new java.util.HashMap<>();
        t.indexes().forEach(i -> byName.put(i.name(), i));
        TableDetail.Index pk = byName.get("customers_pkey");
        assertTrue(pk.primaryKey());
        assertTrue(pk.unique());
        assertEquals(List.of("region", "id"), pk.columns());
        TableDetail.Index email = byName.get("customers_email");
        assertTrue(email.unique());
        assertFalse(email.primaryKey());
        assertEquals(List.of("email"), email.columns());
        assertEquals(List.of(JdbcExplorer.EXPRESSION), byName.get("customers_lower").columns());
        String everything = t.toString();
        assertFalse(everything.contains("lower(") || everything.contains("ceo@example.com"), everything);

        TableDetail orders = CONNECTOR.explore(source(), "shop.orders", LIMIT);
        assertTrue(orders.indexes().stream().anyMatch(i -> i.name().equals("orders_customer_ix")
            && i.columns().equals(List.of("customer", "region")) && !i.unique()));
    }

    @Test
    void estimatesAndCountsRows() throws Exception {
        TableDetail t = CONNECTOR.explore(source(), "shop.customers", LIMIT);
        assertEquals(300L, t.estimatedRows());
        assertEquals(300L, CONNECTOR.countRows(source(), "shop.customers", LIMIT));
        assertEquals(0L, CONNECTOR.countRows(source(), "shop.my_table", LIMIT));
    }

    @Test
    void aViewHasColumnsButNoKeysOrEstimate() throws Exception {
        TableDetail v = CONNECTOR.explore(source(), "shop.big_spenders", LIMIT);
        assertEquals("view", v.kind());
        assertEquals(5, v.columns().size());
        assertTrue(v.indexes().isEmpty());
        assertTrue(v.foreignKeys().isEmpty());
        assertNull(v.estimatedRows());
    }

    @Test
    void anUnderscoreMatchesOnlyItself() throws Exception {
        TableDetail t = CONNECTOR.explore(source(), "shop.my_table", LIMIT);
        assertEquals(List.of("a"), t.columns().stream().map(TableDetail.Column::name).toList());
    }

    @Test
    void aTableThatIsNotThereIsRefusedBeforeAnyQuery() {
        ConnectorException e = assertThrows(ConnectorException.class,
            () -> CONNECTOR.explore(source(), "shop.nothing; DROP TABLE shop.orders", LIMIT));
        assertTrue(e.getMessage().contains("no table"), e.getMessage());
        assertThrows(ConnectorException.class, () -> CONNECTOR.countRows(source(), "shop.\"orders\"", LIMIT));
    }
}

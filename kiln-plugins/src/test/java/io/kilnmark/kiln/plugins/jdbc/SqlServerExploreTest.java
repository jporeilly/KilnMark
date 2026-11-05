package io.kilnmark.kiln.plugins.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mssqlserver.MSSQLServerContainer;
import org.testcontainers.utility.DockerImageName;

import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.connect.TableDetail;

/** Explore (ADR 0045) on SQL Server: columns, keys both ways, indexes, estimates and counts. Tagged {@code sqlserver}. */
@Tag("sqlserver")
@Testcontainers
class SqlServerExploreTest {

    @Container
    static final MSSQLServerContainer DB = new MSSQLServerContainer(
        DockerImageName.parse("mcr.microsoft.com/mssql/server:2022-CU27-GDR1-ubuntu-22.04"))
        .acceptLicense()
        .withEnv("MSSQL_MEMORY_LIMIT_MB", "2048");

    private static final SqlServerConnector CONNECTOR = new SqlServerConnector();
    private static final Duration LIMIT = Duration.ofSeconds(60);
    private static SourceConfig source;

    @BeforeAll
    static void load() throws Exception {
        try (Connection c = DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
             Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE shop");
        }
        String url = DB.getJdbcUrl() + ";databaseName=shop";
        try (Connection c = DriverManager.getConnection(url, DB.getUsername(), DB.getPassword());
             Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA sales");
            s.execute("""
                CREATE TABLE sales.customers (
                    region  nvarchar(8)   NOT NULL,
                    id      int           NOT NULL,
                    email   nvarchar(200) NULL,
                    balance decimal(12,2) NULL DEFAULT 0,
                    code    int           IDENTITY(1,1),
                    CONSTRAINT customers_pk PRIMARY KEY (region, id)
                )""");
            s.execute("""
                CREATE TABLE sales.orders (
                    order_id int NOT NULL CONSTRAINT orders_pk PRIMARY KEY,
                    region   nvarchar(8) NOT NULL,
                    customer int NOT NULL,
                    CONSTRAINT orders_customer FOREIGN KEY (region, customer) REFERENCES sales.customers (region, id)
                )""");
            s.execute("CREATE UNIQUE INDEX customers_email ON sales.customers (email) WHERE email IS NOT NULL");
            s.execute("CREATE INDEX orders_customer_ix ON sales.orders (customer, region)");
            s.execute("CREATE VIEW sales.uk AS SELECT * FROM sales.customers WHERE region = N'uk'");
            s.execute("INSERT INTO sales.customers (region, id, email) VALUES (N'uk', 1, N'a@example.com'), "
                + "(N'uk', 2, N'b@example.com'), (N'fr', 1, NULL)");
        }
        source = new SourceConfig("shopdb", "sqlserver", Map.of("url", url, "username", DB.getUsername()),
            DB.getPassword());
    }

    @Test
    void showsColumnsKeysAndIndexes() throws Exception {
        TableDetail t = CONNECTOR.explore(source, "sales.customers", LIMIT);
        assertEquals(List.of("region", "id", "email", "balance", "code"),
            t.columns().stream().map(TableDetail.Column::name).toList());
        assertEquals(8, t.columns().get(0).size());
        assertTrue(t.columns().get(3).hasDefault());
        assertTrue(t.columns().get(4).generated());
        assertEquals(List.of("region", "id"), t.primaryKey());
        assertTrue(t.indexes().stream().anyMatch(i -> i.name().equals("customers_pk") && i.primaryKey()));
        assertTrue(t.indexes().stream().anyMatch(i -> i.name().equals("customers_email") && i.unique()
            && !i.primaryKey()));
        assertEquals(List.of("sales.orders"), t.referencedBy().stream().map(TableDetail.ForeignKey::table).toList());
    }

    @Test
    void showsForeignKeysAndCountsOrdinaryTablesOnly() throws Exception {
        TableDetail o = CONNECTOR.explore(source, "sales.orders", LIMIT);
        TableDetail.ForeignKey fk = o.foreignKeys().get(0);
        assertEquals("orders_customer", fk.name());
        assertEquals(List.of("region", "customer"), fk.columns());
        assertEquals("sales.customers", fk.toTable());
        assertTrue(o.indexes().stream().anyMatch(i -> i.name().equals("orders_customer_ix")
            && i.columns().equals(List.of("customer", "region"))));
        assertEquals(3L, CONNECTOR.countRows(source, "sales.customers", LIMIT));
        assertThrows(ConnectorException.class, () -> CONNECTOR.countRows(source, "sales.uk", LIMIT));
        assertFalse(CONNECTOR.explore(source, "sales.uk", LIMIT).columns().isEmpty());
    }
}

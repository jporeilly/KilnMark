package io.kilnmark.kiln.plugins.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.connect.TableDetail;

/**
 * Explore (ADR 0045) on MySQL and MariaDB, whose drivers keep each database at the catalog level of
 * metadata calls: columns, keys both ways, indexes, estimates and exact counts.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class MySqlFamilyExploreTest {

    private static final Duration LIMIT = Duration.ofSeconds(30);

    /** The server to test against. */
    abstract MySqlServers.Server server();

    private MySqlServers.Server db;
    private SourceConfig source;

    @BeforeAll
    void load() throws Exception {
        db = server();
        db.sql("DROP DATABASE IF EXISTS ke_shop", "CREATE DATABASE ke_shop CHARACTER SET utf8mb4",
            """
            CREATE TABLE ke_shop.customers (
                region  VARCHAR(8) NOT NULL,
                id      INT NOT NULL,
                email   VARCHAR(200),
                balance DECIMAL(12,2) DEFAULT 0,
                PRIMARY KEY (region, id),
                UNIQUE KEY customers_email (email)
            )""",
            """
            CREATE TABLE ke_shop.orders (
                order_id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                region   VARCHAR(8) NOT NULL,
                customer INT NOT NULL,
                KEY orders_customer_ix (customer, region),
                CONSTRAINT orders_customer FOREIGN KEY (region, customer) REFERENCES ke_shop.customers (region, id)
            )""",
            "INSERT INTO ke_shop.customers (region, id, email) VALUES ('uk', 1, 'a@example.com'), "
                + "('uk', 2, 'b@example.com'), ('fr', 1, 'c@example.com')",
            "ANALYZE TABLE ke_shop.customers",
            "CREATE TABLE ke_shop.my_table (a INT)", "CREATE TABLE ke_shop.myxtable (b INT, c INT)",
            "CREATE VIEW ke_shop.uk_customers AS SELECT * FROM ke_shop.customers WHERE region = 'uk'");
        source = db.source("shopdb", Map.of());
    }

    @Test
    void showsColumnsKeysAndIndexes() throws Exception {
        TableDetail t = db.connector().explore(source, "ke_shop.customers", LIMIT);
        assertEquals(List.of("region", "id", "email", "balance"),
            t.columns().stream().map(TableDetail.Column::name).toList());
        assertTrue(t.columns().get(3).hasDefault());
        assertEquals(List.of("region", "id"), t.primaryKey());
        assertTrue(t.indexes().stream().anyMatch(i -> i.primaryKey() && i.columns().equals(List.of("region", "id"))));
        assertTrue(t.indexes().stream().anyMatch(i -> i.name().equals("customers_email") && i.unique()
            && !i.primaryKey()));
        assertEquals(List.of("ke_shop.orders"), t.referencedBy().stream().map(TableDetail.ForeignKey::table).toList());
        assertNotNull(t.estimatedRows());
    }

    @Test
    void showsForeignKeysAndGeneratedColumns() throws Exception {
        TableDetail t = db.connector().explore(source, "ke_shop.orders", LIMIT);
        assertTrue(t.columns().get(0).generated());
        TableDetail.ForeignKey fk = t.foreignKeys().get(0);
        assertEquals("orders_customer", fk.name());
        assertEquals(List.of("region", "customer"), fk.columns());
        assertEquals("ke_shop.customers", fk.toTable());
        assertEquals(List.of("region", "id"), fk.toColumns());
        assertTrue(t.indexes().stream().anyMatch(i -> i.name().equals("orders_customer_ix")
            && i.columns().equals(List.of("customer", "region"))));
    }

    @Test
    void neverShowsTheServersOwnSchemasEvenWhenNamed() {
        // performance_schema keeps other sessions' statements, values and all
        for (String path : List.of("performance_schema.events_statements_history", "mysql.user")) {
            io.kilnmark.kiln.connect.ConnectorException e = org.junit.jupiter.api.Assertions.assertThrows(
                io.kilnmark.kiln.connect.ConnectorException.class, () -> db.connector().explore(source, path, LIMIT), path);
            assertTrue(e.getMessage().contains("database's own schemas") || e.getMessage().contains("no table"),
                e.getMessage());
            org.junit.jupiter.api.Assertions.assertThrows(io.kilnmark.kiln.connect.ConnectorException.class,
                () -> db.connector().preview(source, db.connector().asset(source, path), 5), path);
        }
    }

    @Test
    void countsRowsAndMatchesAnUnderscoreOnlyItself() throws Exception {
        assertEquals(3L, db.connector().countRows(source, "ke_shop.customers", LIMIT));
        org.junit.jupiter.api.Assertions.assertThrows(io.kilnmark.kiln.connect.ConnectorException.class,
            () -> db.connector().countRows(source, "ke_shop.uk_customers", LIMIT), "a view is counted when read");
        TableDetail t = db.connector().explore(source, "ke_shop.my_table", LIMIT);
        assertEquals(List.of("a"), t.columns().stream().map(TableDetail.Column::name).toList());
        assertFalse(t.columns().get(0).generated());
    }
}

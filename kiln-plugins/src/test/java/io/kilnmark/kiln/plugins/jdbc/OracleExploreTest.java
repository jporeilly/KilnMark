package io.kilnmark.kiln.plugins.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.connect.TableDetail;

/**
 * Explore (ADR 0045) on Oracle, through {@code getColumns} (whose {@code COLUMN_DEF} the driver
 * streams, so it must be read in column order), keys, indexes and counts. Tagged {@code oracle}.
 */
@Tag("oracle")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OracleExploreTest {

    private static final String PASSWORD = "Kt-explore-2026";
    private static final Duration LIMIT = Duration.ofSeconds(60);

    private OracleServers.Server db;
    private SourceConfig source;

    @BeforeAll
    void load() throws Exception {
        db = OracleServers.oracle();
        db.quietly("DROP USER kt_explore CASCADE");
        db.sql("CREATE USER kt_explore IDENTIFIED BY \"" + PASSWORD + "\" QUOTA UNLIMITED ON USERS",
            """
            CREATE TABLE kt_explore.customers (
                region  VARCHAR2(8)  NOT NULL,
                id      NUMBER(10)   NOT NULL,
                email   VARCHAR2(200),
                balance NUMBER(12,2) DEFAULT 0,
                CONSTRAINT customers_pk PRIMARY KEY (region, id)
            )""",
            """
            CREATE TABLE kt_explore.orders (
                order_id NUMBER(10) GENERATED ALWAYS AS IDENTITY CONSTRAINT orders_pk PRIMARY KEY,
                region   VARCHAR2(8) NOT NULL,
                customer NUMBER(10)  NOT NULL,
                CONSTRAINT orders_customer FOREIGN KEY (region, customer) REFERENCES kt_explore.customers (region, id)
            )""",
            "CREATE UNIQUE INDEX kt_explore.customers_email ON kt_explore.customers (email)",
            "CREATE INDEX kt_explore.customers_lower ON kt_explore.customers (LOWER(email))",
            "INSERT INTO kt_explore.customers (region, id, email) VALUES ('uk', 1, 'a@example.com')",
            "INSERT INTO kt_explore.customers (region, id, email) VALUES ('uk', 2, 'b@example.com')");
        source = db.source("explore", Map.of("schemas", "KT_EXPLORE"));
    }

    @Test
    void readsColumnsWithDefaultsInColumnOrder() throws Exception {
        TableDetail t = db.connector().explore(source, "KT_EXPLORE.CUSTOMERS", LIMIT);
        assertEquals(List.of("REGION", "ID", "EMAIL", "BALANCE"),
            t.columns().stream().map(TableDetail.Column::name).toList());
        assertTrue(t.columns().get(3).hasDefault(), "COLUMN_DEF read without closing its stream");
        assertEquals(List.of("REGION", "ID"), t.primaryKey());
        assertTrue(t.indexes().stream().anyMatch(i -> i.name().equals("CUSTOMERS_PK") && i.primaryKey()));
        assertTrue(t.indexes().stream().anyMatch(i -> i.columns().contains(JdbcExplorer.EXPRESSION)),
            "an index on LOWER(email) shows no expression text");
    }

    @Test
    void showsForeignKeysCountsAndRefusesOraclesOwnSchemas() throws Exception {
        TableDetail o = db.connector().explore(source, "KT_EXPLORE.ORDERS", LIMIT);
        assertEquals("KT_EXPLORE.CUSTOMERS", o.foreignKeys().get(0).toTable());
        assertEquals(2L, db.connector().countRows(source, "KT_EXPLORE.CUSTOMERS", LIMIT));
        ConnectorException e = assertThrows(ConnectorException.class,
            () -> db.connector().explore(source, "SYS.DUAL", LIMIT));
        assertTrue(e.getMessage().contains("belongs to the database itself") || e.getMessage().contains("no table"),
            e.getMessage());
    }
}

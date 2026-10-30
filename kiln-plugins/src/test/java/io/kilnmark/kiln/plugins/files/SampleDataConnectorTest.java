package io.kilnmark.kiln.plugins.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.kiln.connect.Connectors;
import io.kilnmark.kiln.connect.SourceConfig;

/**
 * Kilnmark's sample data, as the bundled source reads it: every table, in its group's folder,
 * with columns of the kinds the samples need (the dates, decimals, booleans and timestamps they
 * compare and compute with), and nothing to write to.
 */
class SampleDataConnectorTest {

    private final Connector connector = Connectors.load().get("sample-data").orElseThrow();
    private final SourceConfig source = new SourceConfig("sample-data", "sample-data", Map.of(), null);

    private Map<String, String> kinds(AssetInfo a) {
        return a.fields().stream().collect(Collectors.toMap(AssetInfo.FieldInfo::name, AssetInfo.FieldInfo::kind));
    }

    @Test
    void everyTableIsThereInItsGroup() throws Exception {
        List<AssetInfo> assets = connector.catalog(source);
        assertEquals(List.of("billing/invoices.csv", "billing/transactions.csv", "crm/accounts.csv",
            "crm/customers.csv", "crm/customers_archive_2023.csv", "shop/customers.csv", "shop/order_lines.csv",
            "shop/orders.csv", "stock/products.csv", "stock/supplier_feed.csv"),
            assets.stream().map(AssetInfo::path).sorted().toList());
        assertEquals(List.of("billing", "crm", "shop", "stock"),
            connector.containers(source).stream().map(Connector.ContainerInfo::name).sorted().toList());
        assertEquals("files", connector.category());
        assertEquals(List.of(), connector.validate(source));
    }

    @Test
    void columnsHaveTheKindsTheSamplesUse() throws Exception {
        Map<String, AssetInfo> byPath = connector.catalog(source).stream()
            .collect(Collectors.toMap(AssetInfo::path, a -> a));
        Map<String, String> customers = kinds(byPath.get("crm/customers.csv"));
        assertEquals("int64", customers.get("customer_id"));
        assertEquals("date", customers.get("date_of_birth"));
        assertEquals("boolean", customers.get("marketing_opt_in"));
        assertEquals("timestamp_tz", customers.get("created_at"));
        assertEquals("text", customers.get("ni_number"));
        Map<String, String> txns = kinds(byPath.get("billing/transactions.csv"));
        assertTrue(txns.get("amount").startsWith("decimal"), txns.toString());
        assertEquals("text", txns.get("card_last4"), "kept as text, with its leading zeros");
        Map<String, String> orders = kinds(byPath.get("shop/orders.csv"));
        assertEquals("timestamp", orders.get("ordered_at"));
        assertTrue(orders.get("shipping_fee").startsWith("decimal"), orders.toString());
        assertEquals("boolean", kinds(byPath.get("shop/customers.csv")).get("is_active"));
        assertTrue(kinds(byPath.get("stock/products.csv")).get("list_price").startsWith("decimal"));
        assertEquals("date", kinds(byPath.get("stock/supplier_feed.csv")).get("received_on"));
    }

    @Test
    void itCannotBeWrittenTo() {
        assertTrue(!connector.capabilities().contains(Connector.Capability.WRITE));
        assertThrows(ConnectorException.class, () -> connector.writer(source,
            new Connector.WriteOptions("crm/x.csv", "create", List.of(), "csv", Map.of(), "urn:x")));
    }
}

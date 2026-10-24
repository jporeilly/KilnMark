package io.kilnmark.server.api;

import java.util.List;

/**
 * Connectors planned but not built, from the connector catalogue design
 * (docs/design/connector-catalogue.md), so people can see what is coming. Built ones come from
 * the connectors themselves. Capabilities: discover, read, write, stream.
 */
final class PlannedConnectors {

    private PlannedConnectors() {
    }

    private static Views.PlannedConnector p(String name, String category, String family, String caps, String wave) {
        List<String> c = new java.util.ArrayList<>();
        for (char x : caps.toCharArray()) {
            c.add(switch (x) {
                case 'D' -> "discover";
                case 'R' -> "read";
                case 'W' -> "write";
                default -> "stream";
            });
        }
        return new Views.PlannedConnector(name, category, family, List.copyOf(c), wave);
    }

    static final List<Views.PlannedConnector> ALL = List.of(
        p("MySQL and MariaDB", "databases", "relational", "DRW", "1"),
        p("Oracle", "databases", "relational", "DRW", "1"),
        p("Generic JDBC", "databases", "relational", "DRW", "1"),
        p("IBM Db2", "databases", "relational", "DRW", "2"),
        p("SQLite", "databases", "relational", "DRW", "2"),
        p("Snowflake", "databases", "cloud warehouse", "DRW", "2"),
        p("Amazon Redshift", "databases", "cloud warehouse", "DRW", "2"),
        p("Google BigQuery", "databases", "cloud warehouse", "DRW", "3"),
        p("Databricks SQL", "databases", "cloud warehouse", "DRW", "3"),
        p("SAP HANA", "databases", "relational", "DRW", "3"),
        p("Teradata", "databases", "relational", "DRW", "3"),
        p("MongoDB", "nosql", "document", "DRW", "2"),
        p("Elasticsearch and OpenSearch", "nosql", "search", "DRW", "2"),
        p("Apache Cassandra", "nosql", "wide column", "DRW", "3"),
        p("Redis", "nosql", "key-value", "RW", "3"),
        p("Neo4j", "nosql", "graph", "DRW", "3"),
        p("Azure Cosmos DB and Amazon DynamoDB", "nosql", "document", "DRW", "3"),
        p("SMB / Windows share", "files", "local or network folder", "DRW", "1"),
        p("SFTP and FTP(S)", "files", "file transfer", "DRW", "1"),
        p("Azure Blob Storage and ADLS Gen2", "files", "object storage", "DRW", "1"),
        p("Google Cloud Storage", "files", "object storage", "DRW", "2"),
        p("HTTP(S) download", "files", "file transfer", "DR", "2"),
        p("HDFS", "files", "distributed file system", "DRW", "3"),
        p("Apache Kafka", "streaming", "message broker", "DRWS", "round 3"),
        p("MQTT", "streaming", "IoT", "DRWS", "round 3"),
        p("AMQP / RabbitMQ", "streaming", "message broker", "RWS", "2"),
        p("JMS", "streaming", "message broker", "RWS", "2"),
        p("Change data capture (PostgreSQL, SQL Server)", "streaming", "change data capture", "RS", "2"),
        p("Azure Event Hubs, Amazon Kinesis, Google Pub/Sub", "streaming", "cloud streams", "RWS", "3"),
        p("OPC UA", "streaming", "industrial", "RS", "3"),
        p("REST / HTTP JSON", "applications", "APIs", "DRW", "1"),
        p("GraphQL", "applications", "APIs", "DR", "2"),
        p("OData", "applications", "APIs", "DRW", "2"),
        p("Email mailbox (IMAP)", "applications", "email", "DR", "2"),
        p("SharePoint and OneDrive", "applications", "office", "DRW", "2"),
        p("Google Sheets", "applications", "office", "DRW", "2"),
        p("Salesforce", "applications", "business applications", "DRW", "3"),
        p("ServiceNow", "applications", "business applications", "DRW", "3"),
        p("SAP (OData and RFC)", "applications", "business applications", "DR", "3"),
        p("Apache Iceberg", "lakehouse", "table formats", "DRW", "2"),
        p("Delta Lake", "lakehouse", "table formats", "DRW", "3"));

    /** The categories in the order the gallery shows them, with their labels. */
    static final List<String[]> CATEGORIES = List.of(
        new String[] {"databases", "Databases"},
        new String[] {"nosql", "NoSQL"},
        new String[] {"files", "Files"},
        new String[] {"streaming", "Streaming"},
        new String[] {"applications", "Applications"},
        new String[] {"lakehouse", "Lakehouse"},
        new String[] {"other", "Other"});
}

package io.kilnmark.server.api;

import java.util.List;

/**
 * Connectors planned but not built, from the connector catalogue design
 * (docs/design/connector-catalogue.md), so people can see what is coming. Built ones come from
 * the connectors themselves. Capabilities: discover, read, write, stream. Each has the kind it is
 * expected to have and, for a product, the letters on its tile (never a vendor logo).
 */
final class PlannedConnectors {

    private PlannedConnectors() {
    }

    private static Views.PlannedConnector p(String id, String monogram, String name, String category, String family,
                                            String caps, String wave) {
        List<String> c = new java.util.ArrayList<>();
        for (char x : caps.toCharArray()) {
            c.add(switch (x) {
                case 'D' -> "discover";
                case 'R' -> "read";
                case 'W' -> "write";
                default -> "stream";
            });
        }
        return new Views.PlannedConnector(id, name, category, family, List.copyOf(c), wave, monogram);
    }

    static final List<Views.PlannedConnector> ALL = List.of(
        p("oracle", "Ora", "Oracle", "databases", "relational", "DRW", "1"),
        p("jdbc", null, "Generic JDBC", "databases", "relational", "DRW", "1"),
        p("db2", "Db2", "IBM Db2", "databases", "relational", "DRW", "2"),
        p("sqlite", "SL", "SQLite", "databases", "relational", "DRW", "2"),
        p("snowflake", "Sn", "Snowflake", "databases", "cloud warehouse", "DRW", "2"),
        p("redshift", "Rs", "Amazon Redshift", "databases", "cloud warehouse", "DRW", "2"),
        p("bigquery", "BQ", "Google BigQuery", "databases", "cloud warehouse", "DRW", "3"),
        p("databricks", "Dbx", "Databricks SQL", "databases", "cloud warehouse", "DRW", "3"),
        p("hana", "HA", "SAP HANA", "databases", "relational", "DRW", "3"),
        p("teradata", "Td", "Teradata", "databases", "relational", "DRW", "3"),
        p("mongodb", "Mg", "MongoDB", "nosql", "document", "DRW", "2"),
        p("elasticsearch", "Es", "Elasticsearch and OpenSearch", "nosql", "search", "DRW", "2"),
        p("cassandra", "Cs", "Apache Cassandra", "nosql", "wide column", "DRW", "3"),
        p("redis", "Rd", "Redis", "nosql", "key-value", "RW", "3"),
        p("neo4j", "N4j", "Neo4j", "nosql", "graph", "DRW", "3"),
        p("cosmos-dynamo", "CD", "Azure Cosmos DB and Amazon DynamoDB", "nosql", "document", "DRW", "3"),
        p("smb", null, "SMB / Windows share", "files", "local or network folder", "DRW", "1"),
        p("sftp", null, "SFTP and FTP(S)", "files", "file transfer", "DRW", "1"),
        p("azure-blob", "Az", "Azure Blob Storage and ADLS Gen2", "files", "object storage", "DRW", "1"),
        p("gcs", "GCS", "Google Cloud Storage", "files", "object storage", "DRW", "2"),
        p("http", null, "HTTP(S) download", "files", "file transfer", "DR", "2"),
        p("hdfs", "HD", "HDFS", "files", "distributed file system", "DRW", "3"),
        p("kafka", "Kf", "Apache Kafka", "streaming", "message broker", "DRWS", "round 3"),
        p("mqtt", "MQ", "MQTT", "streaming", "IoT", "DRWS", "round 3"),
        p("amqp", "AM", "AMQP / RabbitMQ", "streaming", "message broker", "RWS", "2"),
        p("jms", "JMS", "JMS", "streaming", "message broker", "RWS", "2"),
        p("cdc", null, "Change data capture (PostgreSQL, SQL Server)", "streaming", "change data capture", "RS", "2"),
        p("cloud-streams", null, "Azure Event Hubs, Amazon Kinesis, Google Pub/Sub", "streaming", "cloud streams",
            "RWS", "3"),
        p("opcua", "OPC", "OPC UA", "streaming", "industrial", "RS", "3"),
        p("rest", null, "REST / HTTP JSON", "applications", "APIs", "DRW", "1"),
        p("graphql", "GQL", "GraphQL", "applications", "APIs", "DR", "2"),
        p("odata", "OD", "OData", "applications", "APIs", "DRW", "2"),
        p("imap", null, "Email mailbox (IMAP)", "applications", "email", "DR", "2"),
        p("sharepoint", "SP", "SharePoint and OneDrive", "applications", "office", "DRW", "2"),
        p("google-sheets", "GS", "Google Sheets", "applications", "office", "DRW", "2"),
        p("salesforce", "SF", "Salesforce", "applications", "business applications", "DRW", "3"),
        p("servicenow", "SN", "ServiceNow", "applications", "business applications", "DRW", "3"),
        p("sap", "SAP", "SAP (OData and RFC)", "applications", "business applications", "DR", "3"),
        p("iceberg", "Ice", "Apache Iceberg", "lakehouse", "table formats", "DRW", "2"),
        p("delta", "DL", "Delta Lake", "lakehouse", "table formats", "DRW", "3"));

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

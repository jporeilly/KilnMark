# Connector catalogue

Status: design. Categories and the "Add connection" gallery are built in round 2; connectors arrive
in waves (below).

Connections are grouped the way people think about them, by category. Each connector says what it
can do, so the same catalogue drives the "Add connection" gallery, discovery and profiling, and
from round 3 the pipeline palette of inputs and outputs.

## Two ideas kept apart

- **Where the data lives** is the connection: a database server, a folder, a bucket, a Kafka
  cluster, an API.
- **What shape it has** is the format, for files and messages: CSV, JSON, XML, Parquet, Avro and
  so on. One folder or bucket connection reads every supported format inside it. A source can be
  limited to some formats, and discovery shows how many files of each format it found.

Pipelines (round 3) use both: an input step names a connection and, for files and streams, a
format with its options (delimiter, record path, schema).

## Categories

Each entry lists what it supports: **D** discover and profile, **R** read in pipelines, **W**
write in pipelines, **S** stream continuously. ✓ means built now. Everything else is planned, and
the wave says when.

### Databases

| Connector | Supports | Wave |
| --- | --- | --- |
| PostgreSQL ✓ | D R W | built (W in round 3) |
| SQL Server ✓ | D R W | built (W in round 3) |
| MySQL and MariaDB | D R W | 1 |
| Oracle | D R W | 1 |
| Generic JDBC (any driver the customer supplies) | D R W | 1 |
| IBM Db2 | D R W | 2 |
| SQLite | D R W | 2 |
| Snowflake | D R W | 2 |
| Amazon Redshift | D R W | 2 |
| Google BigQuery | D R W | 3 |
| Databricks SQL | D R W | 3 |
| SAP HANA | D R W | 3 |
| Teradata | D R W | 3 |

Drivers whose licences we cannot bundle (Oracle's, MySQL's GPL driver) are supplied by the
customer: drop the jar into the drivers folder and the connector finds it. ADR 0007's
licence rules stay intact.

### NoSQL and search

| Connector | Supports | Wave |
| --- | --- | --- |
| MongoDB | D R W | 2 |
| Elasticsearch and OpenSearch | D R W | 2 |
| Apache Cassandra | D R W | 3 |
| Redis | R W | 3 |
| Neo4j | D R W | 3 |
| Azure Cosmos DB, Amazon DynamoDB | D R W | 3 |

### Files: locations

| Connector | Supports | Wave |
| --- | --- | --- |
| Local or network folder ✓ | D R W | built (W in round 3) |
| S3-compatible object storage ✓ | D R W | built (W in round 3) |
| SMB / Windows share | D R W | 1 |
| SFTP and FTP(S) | D R W | 1 |
| Azure Blob Storage and ADLS Gen2 | D R W | 1 |
| Google Cloud Storage | D R W | 2 |
| HTTP(S) download | D R | 2 |
| HDFS | D R W | 3 |

### Files: formats

| Format | Supports | Wave |
| --- | --- | --- |
| Delimited: CSV, TSV, pipe ✓ | D R W | built (W in round 3) |
| JSON and JSON Lines ✓ | D R W | built (W in round 3) |
| Parquet ✓ | D R W | built (W in round 3) |
| Excel (.xlsx, .xls) | D R W | round 2 (stream C), W in 1 |
| XML (repeated elements as rows, XPath record paths) | D R W | 1 |
| Fixed width (with a layout) | D R W | 1 |
| Avro | D R W | 1 |
| YAML | D R | 2 |
| ORC | D R | 3 |
| Compressed: gzip, zip, bzip2 (read through) | D R | 1 |
| Documents: PDF, Word, PowerPoint, OpenDocument, RTF, HTML, email, Markdown | D R (text) | round 2 (stream C) |

### Streaming and messaging

| Connector | Supports | Wave |
| --- | --- | --- |
| Apache Kafka | D R W S | round 3 |
| MQTT (IoT) | D R W S | round 3 |
| AMQP / RabbitMQ | R W S | 2 |
| JMS (ActiveMQ and others) | R W S | 2 |
| Change data capture from PostgreSQL and SQL Server | R S | 2 |
| Azure Event Hubs, Amazon Kinesis, Google Pub/Sub | R W S | 3 |
| OPC UA (industrial) | R S | 3 |

Streaming discovery samples a window of messages to infer the schema and profile it.

### Applications and APIs

| Connector | Supports | Wave |
| --- | --- | --- |
| REST / HTTP JSON (paging, auth) | D R W | 1 |
| GraphQL | D R | 2 |
| OData | D R W | 2 |
| Email mailbox (IMAP) | D R | 2 |
| SharePoint and OneDrive | D R W | 2 |
| Google Sheets | D R W | 2 |
| Salesforce | D R W | 3 |
| ServiceNow | D R W | 3 |
| SAP (OData and RFC) | D R | 3 |

### Lakehouse tables

| Connector | Supports | Wave |
| --- | --- | --- |
| Apache Iceberg | D R W | 2 |
| Delta Lake | D R W | 3 |

## How a connector declares itself

The connector SPI adds default methods, so existing connectors keep compiling:

- `category()`: databases, nosql, files, streaming, applications or lakehouse.
- `family()`: a sub-group within the category, for example relational, cloud warehouse, object
  storage or message broker.
- `capabilities()`: the set from discover, read, write and stream.
- `formats()`: for file and stream connectors, the formats it reads.
- `icon()`: a name from the UI's icon set, never a vendor logo.
- `docsAnchor()`: the section of the connector docs.

`GET /api/v1/connectors` returns built connectors grouped by category. Planned connectors come from
a small static list, shown greyed out with "Planned" and their wave, so people can see what is
coming. An admin setting hides the planned list.

## The "Add connection" gallery

- Category tabs (All, Databases, NoSQL, Files, Streaming, Applications, Lakehouse) and a search
  box.
- Cards show a generic icon for the category or format, the name, and capability badges with text,
  not colour alone. The family appears as a small heading within the tab.
- Choosing a card opens the existing dynamic form for that connector.
- The sources list can be filtered and grouped by category, and file sources show the formats they
  found with counts.

## Waves

- **Round 2:** categories, capabilities and the gallery for the connectors that exist; Excel and
  documents from stream C.
- **Wave 1 (with round 3 pipelines, which add W):**
  - databases: MySQL and MariaDB, Oracle, generic JDBC with customer-supplied drivers;
  - file locations: SMB, SFTP and FTP, Azure Blob and ADLS;
  - formats: XML, fixed width, Avro, compressed files;
  - REST;
  - streaming: Kafka and MQTT.
- **Wave 2 and 3:** as the tables say, ordered by what design partners ask for.

Connectors are independent plug-ins, so a wave can be built by several developers in parallel,
each with a reviewer.

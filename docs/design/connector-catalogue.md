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
| MySQL and MariaDB ✓ | D R W | built (wave 1; driver bundled) |
| Oracle ✓ | D R W | built (wave 1; the customer installs the driver) |
| Generic JDBC ✓ (any driver an admin registers) | D R W | built (wave 1; append and create only) |
| IBM Db2 | D R W | 2 |
| SQLite | D R W | 2 |
| Snowflake | D R W | 2 |
| Amazon Redshift | D R W | 2 |
| Google BigQuery | D R W | 3 |
| Databricks SQL | D R W | 3 |
| SAP HANA | D R W | 3 |
| Teradata | D R W | 3 |

Drivers whose licences we cannot bundle (Oracle's) are supplied by the customer: an admin
accepts the licence under Settings › Drivers and the server downloads the pinned version, or the
admin drops the jar into the drivers folder (see [Drivers](#drivers)). MySQL's GPL driver is not
used at all. MySQL and MariaDB need
neither: both are read through MariaDB Connector/J (LGPL 2.1), which ships unmodified as its own
jar ([ADR 0007](../adr/0007-dependency-policy.md)). ADR 0007's
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
- `icon()`: a name from the UI's icon set (Tabler Icons), never a vendor logo.
- `monogram()`: for a product, the two or three letters on its tile (`Pg`, `SQL`); none for
  generic connectors such as a folder, whose tile shows the icon. Planned connectors carry an
  `id` and a monogram too.
- `docsAnchor()`: the section of the connector docs.

`GET /api/v1/connectors` returns built connectors grouped by category. Planned connectors come from
a small static list, shown greyed out with "Planned" and their wave, so people can see what is
coming. An admin setting hides the planned list.

## The "Add connection" gallery

- Category tabs (All, Databases, NoSQL, Files, Streaming, Applications, Lakehouse) and a search
  box.
- Cards show the connector's **tile**, the name, its family as a small label, and capability
  badges with text, not colour alone. Within a category the cards flow in one responsive grid,
  available connectors first and planned ones after, lighter and dashed.
- **Tiles:** letters on a tint for products, the family icon for generic connectors. The tint
  comes from the connector id (eight tints, each checked for 4.5:1 letter contrast in every
  theme); large tiles add the family icon in a corner. The same tile is used in the sources list,
  a source's page and the browse tree. An operator can replace a tile with a logo they are
  licensed to use, or give it a brand colour, through the branding folder
  ([branding guide](../guide/branding.md)); anything missing or invalid falls back to the tile.
  Kilnmark itself ships no vendor logos.
- **Icons** for categories, families, file formats and the browse tree are Tabler Icons, mapped
  by name in one module (`ui/src/components/icons.tsx`).
- Choosing a card opens the existing dynamic form for that connector.
- The sources list can be filtered and grouped by category, and file sources show the formats they
  found with counts.

## From tile to scanning: the connection panel

Clicking a tile opens that connector's connection panel beside the gallery, and the gallery
stays visible. The panel guides the person through four steps, each of which can be revisited:

1. **Connect:** the connector's fields, with help text and an example for each. Database
   connectors offer host, port and database fields and build the URL, with the full URL as an
   advanced option.
2. **Test:** test the connection before anything is saved, with a plain-English result.
3. **Choose what to scan:** the schema or folder picker with counts, plus include and exclude
   patterns and, for files, formats.
   Any table or file can be previewed here: its first rows (100 by default, at most 1,000),
   with columns and inferred types, read live and never stored. For files, the preview also
   shows what was detected (format, delimiter, header, encoding, JSON record path), and the
   person can override it for the scan.
4. **Save:** save only, or save and scan now. Common settings (display name, description, AI
   rule) are set here.

If the connector needs a driver that is not installed, the panel says so first, with a link to
Settings › Drivers for admins.

## Drivers

Some database drivers cannot ship with Kilnmark because their licences do not allow it
(Oracle's), or are copyleft (MySQL Connector/J is GPL; MySQL and MariaDB use the LGPL MariaDB
Connector/J instead, which ships). The customer installs the first kind, as
[drivers.md](drivers.md) describes ([ADR 0007](../adr/0007-dependency-policy.md)). Built so far:

- **Each connector declares its driver:** Maven coordinates, a pinned version, its SHA-256, the
  licence name and link, whether Kilnmark may bundle it, and the driver class. Bundled drivers
  (PostgreSQL, SQL Server, MariaDB Connector/J) need nothing.
- **Download (admin, online servers):** Settings › Drivers shows the driver's licence and asks
  the admin to accept it on the organisation's behalf. The server then downloads that exact
  version from Maven Central, or from the organisation's own Maven mirror if configured, and
  refuses the file unless the checksum matches. It never downloads from an address typed by a
  person. The acceptance is recorded: who, when, which licence and version.
- **Offline servers:** the page names the file, its checksum and the drivers folder; the admin
  copies the file in. Only the pinned checksum is accepted.
- **Where drivers live:** the drivers folder (`kilnmark.drivers.dir`), inside the server's data
  volume in the container. Each driver loads from a verified private copy in its own class loader,
  which sees only the JDK, so a driver cannot replace Kilnmark's own classes; nothing else in the
  folder is read. A driver copied in while the server runs is used at once.
- **In the gallery:** a connector whose driver is missing says "Needs its driver", with a link
  to Settings › Drivers.

- **Registered drivers (generic JDBC):** an admin uploads a jar or picks one in the drivers folder
  under Settings › JDBC drivers, names its class and URL prefix, and Kilnmark records its SHA-256,
  who and when; it loads only while the checksum matches, isolated like a pinned driver. The page
  shows which sources use each driver and an audit of registrations and removals; a driver in use
  cannot be removed. Nothing is downloaded for these ([drivers.md](drivers.md#registered-drivers)).

Still to come: updates to newer pinned versions, and an offline install bundle with every driver
whose licence allows redistribution.

## Waves

- **Round 2:** categories, capabilities and the gallery for the connectors that exist; Excel and
  documents from stream C.
- **Wave 1 (with round 3 pipelines, which add W):**
  - databases: MySQL and MariaDB (built), Oracle (built), generic JDBC with drivers an admin registers (built);
  - file locations: SMB, SFTP and FTP, Azure Blob and ADLS;
  - formats: XML, fixed width, Avro, compressed files;
  - REST;
  - streaming: Kafka and MQTT.
- **Wave 2 and 3:** as the tables say, ordered by what design partners ask for.

Connectors are independent plug-ins, so a wave can be built by several developers in parallel,
each with a reviewer.

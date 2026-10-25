# 0015 File formats: Parquet through Hardwood, S3 through the AWS SDK

Status: Accepted (24 October 2026)

## Decision

The `files` and `s3` connectors read CSV, TSV, JSON, JSON lines and Parquet, and record every other
file as a document. They use:

| Need | Library | Licence | Jar size |
| --- | --- | --- | --- |
| Parquet | `dev.hardwood:hardwood-core` 1.0.1.Final, Java with no required dependencies | Apache-2.0 | 0.66 MB |
| Parquet's ZSTD codec | `com.github.luben:zstd-jni` 1.5.7-16 (native code, JNI) | BSD-2-Clause | 6.5 MB |
| Parquet's Snappy codec | `org.xerial.snappy:snappy-java` 1.1.10.8 (native code, JNI) | Apache-2.0 | 2.4 MB |
| Parquet's LZ4 and LZ4_RAW codecs | `at.yawk.lz4:lz4-java` 1.11.1, the maintained fork Hardwood names (native code through JNI, with a pure-Java fallback; it unpacks its library into the system temporary folder) | Apache-2.0 | 0.9 MB |
| S3 | AWS SDK for Java v2 2.55.16, `s3` module and its core, with the JDK-based `url-connection-client` (Apache HttpClient and Netty excluded) | Apache-2.0 | 8.8 MB |
| JSON | `jackson-core` streaming parser, already on the class path through Arrow | Apache-2.0 | none added |
| CSV | Our own lenient parser (about 150 lines) | | none |

In all, 19.3 MB of jars. Codec versions match the Quarkus platform, which pins them in the server.

## Why

**Parquet without Hadoop.** We tried four ways:

- **parquet-java 1.18.1** with its own `InputFile` and `PlainParquetConfiguration`. It does not work
  without Hadoop: the reader's overloads need `hadoop-common` to compile, and at run time
  `ParquetReadOptions.builder()` loads Hadoop's compression classes
  (`NoClassDefFoundError: org/apache/hadoop/io/compress/zlib/ZlibCompressor$CompressionLevel`).
  Avoiding that means building the reader from internal classes. With Hadoop it is
  `hadoop-common` and its dependency tree on top of 18 MB of Parquet and codec jars; ADR 0007 keeps
  the Hadoop client for optional plug-ins only.
- **Arrow Dataset** (JNI over Arrow C++): an 81 MB jar of native libraries, one per platform, whose
  memory sits outside the Java allocator's accounting, and a native crash takes the server down.
- **DuckDB JDBC**: 81 MB of native code to read one format.
- **Hardwood**: a Parquet reader written in Java 21+ with no required dependencies. It is not
  pure Java in use: it can call the system's libdeflate for gzip through the foreign-function API
  (we turn that off, below) and its codecs other than gzip come from the native libraries above.
  It reads through
  a small `InputFile` interface (`readRange(offset, length)`), which maps directly onto S3 ranged
  GETs and positional reads of local files. It read the lab's pyarrow-written, ZSTD-compressed,
  dictionary-encoded invoices file and our generated test files on Java 25.

Hardwood is the lightest by far and works reliably on Java 25, so we chose it. It decompresses
gzip with the JDK; Snappy, ZSTD and LZ4 need the codec jars above. We added lz4-java (0.9 MB)
because pyarrow's `lz4` option writes LZ4_RAW. Brotli and LZO are not supported: the catalogue
checks every column chunk's codec in the footer and records such a file as not readable, naming
the codec, rather than failing later when it is read.

**How Hardwood runs here.**

- One Hardwood context (a bounded pool of decoding threads, `kilnmark.files.parquet-threads`,
  default 2) serves every Parquet read in the server, rather than a pool per file.
- Hardwood fetches at most `kilnmark.files.parquet-chunk-mb` (default 8 MiB; its own default is
  128 MiB) per request, through the system property `hardwood.internal.sequentialChunkSize`.
- `hardwood.uselibdeflate` is set to false unless given on the command line, so gzip uses the
  JDK's inflater and no system library is loaded.
- snappy-java unpacks its native library into `kilnmark.files.native-dir` (default
  `~/.kilnmark/native`, readable only by the server's user) instead of the shared temporary
  folder, through `org.xerial.snappy.tempdir`.
- Before Hardwood reads a footer, the catalogue checks the file's trailer: a footer length larger
  than the file or 64 MiB is refused, so a corrupt file cannot exhaust memory. A schema nested too
  deeply for Hardwood's recursive reader (a stack overflow) marks that file unreadable too.
- When a read stops early, the reader cancels the store before closing Hardwood: S3 range requests
  still downloading are aborted, and new ones refused.

**S3.** The AWS SDK is the reference implementation of the S3 protocol and works with any
S3-compatible endpoint (tested against VersityGW). With only the `s3` module and the JDK's HTTP
client it is 8.8 MB. MinIO's Java client 9.0.3 was rejected: it brings Guava, OkHttp 5 with the
Kotlin standard library, Bouncy Castle (8 MB alone), Commons Compress, snappy-java and
simple-xml, about 16 MB, and ties the connector to one vendor's client.

**Tests.** The S3 integration test runs VersityGW (Apache-2.0) with Testcontainers. It serves a
folder as buckets, so test files are copied in rather than uploaded. MinIO images are no longer
publicly pullable.

## Cost

- Hardwood is young (first release 2026). All Parquet use is in three places
  (`ParquetFields`, `FileInspector.parquet`, `FileInput.ParquetRows`); replacing it is a day's
  work, and the lab regression would show a difference.
- zstd-jni and snappy-java load native libraries (zstd-jni unpacks into `java.io.tmpdir`, snappy
  into the folder above), which must be writable. The server jar's manifest and the tests allow
  native access, so Java 25 does not warn.
- Hardwood's tuning goes through JVM-wide system properties, set once when the first Parquet file
  is read: one server, one setting.
- Hardwood logs one INFO line through `java.util.logging` saying whether SIMD is in use.

## Amendment: writing Parquet (25 October 2026)

Pipelines write Parquet (docs/design/pipelines.md, section 4). Hardwood 1.0.1 only reads, and
parquet-java's writer needs Hadoop on the class path as its reader does (above), so Kilnmark
writes Parquet with its own encoder, `ParquetEncoder` (about 700 lines, from the Apache Parquet
format specification; the Thrift compact encoding follows the test writer already in the repository).

- **What it writes:** format version 1, flat `OPTIONAL` columns, data pages v1 with `PLAIN` values
  and `RLE` definition levels, pages of about 1 MiB and row groups of about 64 MiB, no
  dictionaries, statistics or page indexes. Types follow what `ParquetFields` reads back: integer
  widths, `DECIMAL` on `INT32`, `INT64` or a fixed-length array, `STRING`, `DATE`,
  `TIME(MICROS)`, `TIMESTAMP(MICROS)` adjusted to UTC or not. Nested values are written as text.
- **Codecs:** Snappy by default, ZSTD, GZIP (the JDK) or none. snappy-java and zstd-jni were
  already shipped for reading; they are now compiled against as well. No dependency is added.
- **Streaming:** row groups are written as they fill and the footer at the end, so the same code
  writes a local temporary file and an S3 multipart upload.
- **Checked** by reading back through Hardwood in the tests, for every type and codec, several
  row groups and pages, and by pyarrow 26 during development.
- **Cost:** files are larger than a dictionary-encoding writer's for repetitive text, and carry no
  statistics for readers to skip row groups. Either can be added to the encoder without changing
  its callers; if a Hadoop-free writer library appears, `ParquetEncoder` is the one class to replace.

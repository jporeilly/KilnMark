# 0015 File formats: Parquet through Hardwood, S3 through the AWS SDK

Status: Accepted (24 October 2026)

## Decision

The `files` and `s3` connectors read CSV, TSV, JSON, JSON lines and Parquet, and record every other
file as a document. They use:

| Need | Library | Licence | Jar size |
| --- | --- | --- | --- |
| Parquet | `dev.hardwood:hardwood-core` 1.0.1.Final, pure Java, no dependencies | Apache-2.0 | 0.66 MB |
| Parquet's ZSTD codec | `com.github.luben:zstd-jni` 1.5.7-16 | BSD-2-Clause | 6.5 MB |
| Parquet's Snappy codec | `org.xerial.snappy:snappy-java` 1.1.10.8 | Apache-2.0 | 2.4 MB |
| S3 | AWS SDK for Java v2 2.55.16, `s3` module and its core, with the JDK-based `url-connection-client` (Apache HttpClient and Netty excluded) | Apache-2.0 | 8.8 MB |
| JSON | `jackson-core` streaming parser, already on the class path through Arrow | Apache-2.0 | none added |
| CSV | Our own lenient parser (about 150 lines) | | none |

In all, 18.4 MB of jars. Codec versions match the Quarkus platform, which pins them in the server.

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
- **Hardwood**: a Parquet reader in plain Java 21+ with no required dependencies. It reads through
  a small `InputFile` interface (`readRange(offset, length)`), which maps directly onto S3 ranged
  GETs and positional reads of local files. It read the lab's pyarrow-written, ZSTD-compressed,
  dictionary-encoded invoices file and our generated test files on Java 25.

Hardwood is the lightest by far and works reliably on Java 25, so we chose it. It decompresses
gzip with the JDK; Snappy and ZSTD, the codecs almost every writer uses, need the two codec jars
above. LZ4 and Brotli need further jars we do not ship: such a file is catalogued (the footer is
not compressed) but its read fails with a message naming the codec. Add `lz4-java` if they turn
up.

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
- zstd-jni and snappy-java load native libraries, unpacked into `java.io.tmpdir`, which must be
  writable. Java 25 warns about native access unless the JVM runs with
  `--enable-native-access=ALL-UNNAMED`; the tests pass it, and the server's launch should too.
- Hardwood logs one INFO line through `java.util.logging` saying whether SIMD is in use.

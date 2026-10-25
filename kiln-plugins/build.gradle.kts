// Built-in Kiln steps: connectors, profiling and discovery.
// Code adapted from Apache-licensed Kettle 10.2 must be logged in docs/legal/provenance.md.
plugins {
    `java-library`
}

description = "Built-in Kiln steps"

dependencies {
    "api"(project(":kiln-core"))
    "implementation"(libs.datasketches)
    // SQL Server's JDBC driver (MIT); loaded through DriverManager, so only needed at run time.
    "runtimeOnly"(libs.mssql.jdbc)
    // PostgreSQL's JDBC driver (BSD-2-Clause): writers stream rows with its COPY API. The server
    // ships it already (quarkus-jdbc-postgresql), so it is compiled against, not bundled again.
    "compileOnly"(libs.postgresql)

    // Files and object storage (ADR 0015): Parquet without Hadoop, JSON, and S3.
    "implementation"(libs.hardwood)
    // Parquet's ZSTD and Snappy codecs: read through Hardwood, and called directly to write Parquet.
    "implementation"(libs.zstd.jni)    // ZSTD (the lab's invoices file uses it)
    "implementation"(libs.snappy.java) // Snappy, the most common default
    "runtimeOnly"(libs.lz4.java)       // Parquet's LZ4 and LZ4_RAW codecs (pyarrow's "lz4")
    "implementation"(libs.jackson.core)
    "implementation"(libs.awssdk.s3) {
        // One small blocking HTTP client (the JDK's) instead of Apache HttpClient and Netty.
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "apache5-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    "implementation"(libs.awssdk.url.connection.client)

    // Documents (ADR 0017): Apache Tika's core and only the parser modules for the formats read.
    // What they bring that those formats do not need is left out: Bouncy Castle (encrypted files
    // and signed mail), Access and PST readers, PDF command-line tools, the full OOXML schemas (the
    // lite set POI ships is enough), and juniversalchardet (MPL; our own encoding detection is used).
    "implementation"(libs.tika.core)
    "implementation"(libs.tika.parser.pdf) {
        exclude(group = "org.bouncycastle")
        exclude(group = "org.apache.pdfbox", module = "pdfbox-tools")
        exclude(group = "org.apache.pdfbox", module = "jempbox")
        exclude(group = "org.glassfish.jaxb")
    }
    "implementation"(libs.tika.parser.microsoft) {
        exclude(group = "org.bouncycastle")
        exclude(group = "com.healthmarketscience.jackcess")
        exclude(group = "com.pff", module = "java-libpst")
        exclude(group = "org.apache.poi", module = "poi-ooxml-full")
        exclude(group = "com.github.albfernandez", module = "juniversalchardet")
    }
    "implementation"(libs.tika.parser.mail) {
        exclude(group = "com.github.albfernandez", module = "juniversalchardet")
    }
    "implementation"(libs.tika.parser.miscoffice) {
        exclude(group = "org.glassfish.jaxb")
        exclude(group = "com.github.albfernandez", module = "juniversalchardet")
    }
    // Mail: one mime4j version everywhere, the one the Quarkus platform gives the server (Tika asks for
    // a newer one), so the tests read mail with what the server ships.
    "implementation"(libs.mime4j.core) { version { strictly(libs.versions.mime4j.get()) } }
    "implementation"(libs.mime4j.dom) { version { strictly(libs.versions.mime4j.get()) } }
    // Excel sheets as tables, read with POI's streaming (event) API; the lite OOXML schemas suffice.
    "implementation"(libs.poi.ooxml)
    "implementation"(libs.poi.ooxml.lite)

    "testImplementation"(libs.testcontainers.postgresql)
    "testImplementation"(libs.testcontainers.mssqlserver)
    "testImplementation"(libs.testcontainers.junit)
    "testImplementation"(libs.postgresql)
}

tasks.withType<Test>().configureEach {
    jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED", "--enable-native-access=ALL-UNNAMED")
}

// Tests against heavy databases (SQL Server's image is 2.3 GB and needs 2 GB of memory) are tagged
// and run apart: ./gradlew :kiln-plugins:heavyTest. The ordinary build stays quick.
val heavy = listOf("sqlserver")

tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags(*heavy.toTypedArray()) }
}

tasks.register<Test>("heavyTest") {
    description = "Runs the integration tests against heavy databases (SQL Server)."
    group = "verification"
    val main = project.extensions.getByType<SourceSetContainer>()["test"]
    testClassesDirs = main.output.classesDirs
    classpath = main.runtimeClasspath
    useJUnitPlatform { includeTags(*heavy.toTypedArray()) }
}

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

    // Files and object storage (ADR 0015): Parquet without Hadoop, JSON, and S3.
    "implementation"(libs.hardwood)
    "runtimeOnly"(libs.zstd.jni)       // Parquet's ZSTD codec (the lab's invoices file uses it)
    "runtimeOnly"(libs.snappy.java)    // Parquet's Snappy codec, the most common default
    "implementation"(libs.jackson.core)
    "implementation"(libs.awssdk.s3) {
        // One small blocking HTTP client (the JDK's) instead of Apache HttpClient and Netty.
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "apache5-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    "implementation"(libs.awssdk.url.connection.client)

    "testImplementation"(libs.testcontainers.postgresql)
    "testImplementation"(libs.testcontainers.mssqlserver)
    "testImplementation"(libs.testcontainers.junit)
    "testRuntimeOnly"(libs.postgresql)
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

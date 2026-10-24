// Built-in Kiln steps: connectors, profiling and discovery.
// Code adapted from Apache-licensed Kettle 10.2 must be logged in docs/legal/provenance.md.
plugins {
    `java-library`
}

description = "Built-in Kiln steps"

dependencies {
    "api"(project(":kiln-core"))
    "implementation"(libs.datasketches)

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
    "testImplementation"(libs.testcontainers.junit)
    "testRuntimeOnly"(libs.postgresql)
}

tasks.withType<Test>().configureEach {
    jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED")
    // zstd-jni and snappy-java load native code; Java 25 warns unless it is allowed.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

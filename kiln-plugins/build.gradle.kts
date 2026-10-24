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

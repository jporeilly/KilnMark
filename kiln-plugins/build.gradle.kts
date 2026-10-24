// Built-in Kiln steps: connectors, profiling and discovery.
// Code adapted from Apache-licensed Kettle 10.2 must be logged in docs/legal/provenance.md.
plugins {
    `java-library`
}

description = "Built-in Kiln steps"

dependencies {
    "api"(project(":kiln-core"))
    "implementation"(libs.datasketches)

    "testImplementation"(libs.testcontainers.postgresql)
    "testImplementation"(libs.testcontainers.junit)
    "testRuntimeOnly"(libs.postgresql)
}

tasks.withType<Test>().configureEach {
    jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED")
}

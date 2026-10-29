// Kiln engine core: the node interface, Arrow row sets, the metadata side-channel and the runner.
plugins {
    `java-library`
}

description = "Kiln engine core"

dependencies {
    "api"(libs.arrow.vector)
    "runtimeOnly"(libs.arrow.memory.unsafe)
    // Reads pipeline YAML (docs/design/pipelines.md); used only inside kiln-core.
    "implementation"(libs.snakeyaml.engine)
}

tasks.withType<Test>().configureEach {
    jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED")
}

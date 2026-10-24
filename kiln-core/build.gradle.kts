// Kiln engine core: the step interface, Arrow row sets, the metadata side-channel and the runner.
plugins {
    `java-library`
}

description = "Kiln engine core"

dependencies {
    "api"(libs.arrow.vector)
    "runtimeOnly"(libs.arrow.memory.unsafe)
}

tasks.withType<Test>().configureEach {
    jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED")
}

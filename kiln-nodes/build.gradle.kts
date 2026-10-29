// Pipeline nodes that change rows (filter, compute, select, lookup, aggregate, union, dedupe, route,
// limit) and the expression language they share (docs/design/pipelines.md, sections 3 and 4).
// No dependencies beyond the engine core: the expression language is written here, by hand.
plugins {
    `java-library`
}

description = "Kiln pipeline nodes and expressions"

dependencies {
    "api"(project(":kiln-core"))
}

tasks.withType<Test>().configureEach {
    jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED")
}

// Arrow spike (sprint 0): row sets with back-pressure, in-stream profiling, memory and speed against
// plain row objects. Built only with -Pspikes. Not part of any release.
//   ./gradlew -Pspikes :arrow-spike:test
//   ./gradlew -Pspikes :arrow-spike:spikeRun -PspikeArgs="--mode arrow --rows 10000000" -Pheap=256m
// Findings: docs/spikes/0001-arrow-row-sets.md.
// -ParrowMemory=netty swaps in the Netty allocator, which on Java 25 also needs
//   -PextraJvm="--sun-misc-unsafe-memory-access=allow -Dio.netty.noUnsafe=false".

description = "Arrow row-set spike (not shipped)"

val arrowMemory = (findProperty("arrowMemory") as String?) ?: "unsafe"

dependencies {
    "implementation"(libs.arrow.vector)
    "implementation"(libs.arrow.jdbc)
    "implementation"("org.apache.datasketches:datasketches-java:9.0.0")
    "runtimeOnly"(if (arrowMemory == "unsafe") libs.arrow.memory.unsafe else libs.arrow.memory.netty)
    "runtimeOnly"(libs.postgresql)
}

val jvmFlags = listOf("--add-opens=java.base/java.nio=ALL-UNNAMED")

tasks.withType<Test>().configureEach {
    jvmArgs(jvmFlags)
    maxHeapSize = "512m"
    testLogging { showStandardStreams = true }
}

tasks.register<JavaExec>("spikeRun") {
    group = "spike"
    description = "Runs one spike measurement; arguments in -PspikeArgs."
    val main = project.extensions.getByType<SourceSetContainer>()["main"]
    classpath = main.runtimeClasspath
    mainClass.set("io.kilnmark.spike.arrow.Spike")
    javaLauncher.set(project.extensions.getByType<JavaToolchainService>().launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    jvmArgs(jvmFlags + listOf("-Xmx" + ((findProperty("heap") as String?) ?: "256m"), "-XX:MaxDirectMemorySize=1g")
        + ((findProperty("extraJvm") as String?) ?: "").split(" ").filter { it.isNotBlank() })
    args(((findProperty("spikeArgs") as String?) ?: "").split(" ").filter { it.isNotBlank() })
}

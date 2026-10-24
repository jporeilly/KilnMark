// The Kilnmark server: one image that runs the api, scheduler and worker roles.
import com.github.jk1.license.filter.LicenseBundleNormalizer
import com.github.jk1.license.render.InventoryMarkdownReportRenderer
import com.github.jk1.license.render.JsonReportRenderer

plugins {
    alias(libs.plugins.quarkus)
    id("com.github.jk1.dependency-license-report")
}

// Third-party licences of everything the server ships (ADR 0007). `checkLicense` fails the build on
// a licence not in config/allowed-licenses.json; the inventory ships with every release.
licenseReport {
    configurations = arrayOf("runtimeClasspath")
    outputDir = rootProject.layout.projectDirectory.dir("dist/licenses").asFile.path
    filters = arrayOf(LicenseBundleNormalizer())
    renderers = arrayOf(InventoryMarkdownReportRenderer("THIRD-PARTY-NOTICES.md", "Kilnmark"), JsonReportRenderer())
    allowedLicensesFile = rootProject.file("config/allowed-licenses.json")
}

description = "Kilnmark server"

dependencies {
    "implementation"(enforcedPlatform(libs.quarkus.bom))
    "implementation"("io.quarkus:quarkus-arc")
    "implementation"("io.quarkus:quarkus-rest-jackson")
    "implementation"("io.quarkus:quarkus-security")
    "implementation"("io.quarkus:quarkus-smallrye-health")
    "implementation"("io.quarkus:quarkus-smallrye-openapi")
    "implementation"("io.quarkus:quarkus-jdbc-postgresql")
    "implementation"("io.quarkus:quarkus-flyway")
    "implementation"("org.flywaydb:flyway-database-postgresql")
    "implementation"(project(":kiln-core"))
    "implementation"(project(":kiln-plugins"))
    "implementation"(project(":graph"))

    // AI runtime (ADR 0016): models are chosen at run time, so plain LangChain4j provider modules,
    // not the Quarkus extension. OpenAI's module also serves llama-server, vLLM, Ollama and Azure
    // OpenAI's v1 API. jtokkit (token counting for OpenAI) is not used.
    "implementation"(libs.langchain4j.open.ai) { exclude(group = "com.knuddels", module = "jtokkit") }
    "implementation"(libs.langchain4j.anthropic)
    "implementation"(libs.langchain4j.google.ai.gemini)
    // Read-only MCP server for outside AI assistants (Streamable HTTP), versions from the Quarkus platform.
    "implementation"(enforcedPlatform(libs.quarkus.mcp.server.bom))
    "implementation"("io.quarkiverse.mcp:quarkus-mcp-server-http")

    "testImplementation"("io.quarkus:quarkus-junit5")
    "testImplementation"("io.rest-assured:rest-assured")
}

// Arrow reads buffer addresses through java.nio; tests and dev mode need the same opening as the jar.
tasks.withType<Test>().configureEach {
    jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED", "--enable-native-access=ALL-UNNAMED")
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
}

// AI evaluation (ADR 0016): scores a running server's search and question answering against
// tests/ai-eval/golden.json with the models it is configured to use. Never part of `build`:
//   KILNMARK_TOKEN=km_... ./gradlew :server:aiEval [-Pkilnmark.eval.url=http://localhost:8780] [-Pkilnmark.eval.source=lab]
tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("ai-eval") }
}

tasks.register<Test>("aiEval") {
    group = "verification"
    description = "Scores a running Kilnmark's AI search against the golden set; results in dist/build/server/ai-eval."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("ai-eval") }
    outputs.upToDateWhen { false }
    systemProperty("kilnmark.eval.golden", rootProject.file("tests/ai-eval/golden.json").absolutePath)
    systemProperty("kilnmark.eval.out", layout.buildDirectory.dir("ai-eval").get().asFile.absolutePath)
    listOf("kilnmark.eval.url", "kilnmark.eval.token", "kilnmark.eval.source").forEach { key ->
        providers.gradleProperty(key).orNull?.let { systemProperty(key, it) }
    }
    testLogging { showStandardStreams = true }
}

// The web UI is built with npm and served by the server from /. Needs Node.js 22 or later on the
// PATH; -PskipUi builds the server without it (API only).
val uiDir = rootProject.layout.projectDirectory.dir("ui")
val uiOut = rootProject.layout.projectDirectory.dir("dist/ui")
val npm = if (System.getProperty("os.name").lowercase().contains("windows")) "npm.cmd" else "npm"
val skipUi = providers.gradleProperty("skipUi").isPresent

val installUi by tasks.registering(Exec::class) {
    description = "Installs the web UI's packages (npm ci)."
    onlyIf { !skipUi }
    workingDir = uiDir.asFile
    commandLine(npm, "ci", "--no-audit", "--no-fund")
    inputs.files(uiDir.file("package.json"), uiDir.file("package-lock.json"))
    outputs.dir(uiDir.dir("node_modules"))
}

val buildUi by tasks.registering(Exec::class) {
    description = "Builds the web UI into dist/ui (npm run build)."
    onlyIf { !skipUi }
    dependsOn(installUi)
    workingDir = uiDir.asFile
    commandLine(npm, "run", "build")
    inputs.dir(uiDir.dir("src"))
    inputs.files(uiDir.file("index.html"), uiDir.file("vite.config.ts"), uiDir.file("tsconfig.json"),
        uiDir.file("package-lock.json"))
    outputs.dir(uiOut)
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(buildUi)
    from(uiOut) {
        into("META-INF/resources")
    }
}

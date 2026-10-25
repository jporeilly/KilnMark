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
    // POI (documents, ADR 0017) logs through the Log4j API: send it to the server's own log.
    "runtimeOnly"("org.jboss.logmanager:log4j2-jboss-logmanager")

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
    // Validates the data quality exports against the published OpenLineage and ODCS schemas (Apache-2.0, test only).
    "testImplementation"(libs.json.schema.validator)
}

// Arrow reads buffer addresses through java.nio; tests and dev mode need the same opening as the jar.
tasks.withType<Test>().configureEach {
    jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED", "--enable-native-access=ALL-UNNAMED")
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
    // The assistant's shared fixtures: section anchors (also checked by the UI build) and the docs evaluation set.
    systemProperty("kilnmark.docs.cases", rootProject.file("tests/docs").absolutePath)
    systemProperty("kilnmark.eval.docs", rootProject.file("tests/ai-eval/docs.json").absolutePath)
    inputs.dir(rootProject.file("tests/docs"))
    inputs.file(rootProject.file("tests/ai-eval/docs.json"))
}

// AI evaluation (ADR 0016): scores a running server's search and question answering against
// tests/ai-eval/golden.json with the models it is configured to use. Never part of `build`:
//   KILNMARK_TOKEN=km_... ./gradlew :server:aiEval [-Pkilnmark.eval.url=http://localhost:8780] [-Pkilnmark.eval.source=lab]
// It fails when a score falls below tests/ai-eval/baseline.json (less its tolerance), the release
// gate of ADR 0012; -Pkilnmark.eval.update-baseline=true accepts the new scores instead.
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
    systemProperty("kilnmark.eval.baseline", rootProject.file("tests/ai-eval/baseline.json").absolutePath)
    listOf("kilnmark.eval.url", "kilnmark.eval.token", "kilnmark.eval.source", "kilnmark.eval.update-baseline").forEach { key ->
        providers.gradleProperty(key).orNull?.let { systemProperty(key, it) }
    }
    testLogging { showStandardStreams = true }
}

// The web UI is built with npm and served by the server from /. Needs Node.js 22.12 or later (or
// 20.19 or later: what Vite needs, ui/package.json "engines") on the PATH; -PskipUi builds the
// server without it (API only).
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
    // scripts/: the contrast check, the third-party notices and the anchor check all run as part of the build.
    inputs.dir(uiDir.dir("scripts"))
    inputs.files(uiDir.file("index.html"), uiDir.file("vite.config.ts"), uiDir.file("tsconfig.json"),
        uiDir.file("package.json"), uiDir.file("package-lock.json"))
    // The bundled documentation, and the anchor cases the pre-build check reads (scripts/check-slugs.mjs).
    inputs.files(rootProject.fileTree("docs/guide") { include("*.md") }, rootProject.file("CHANGELOG.md"))
    inputs.dir(rootProject.file("tests/docs"))
    outputs.dir(uiOut)
}

// The user guides and the changelog, for the assistant (docs/design/assistant.md): the same files
// the web UI bundles, one source of truth. Copied into the server's resources under kilnmark/docs,
// with a list of the guides (a jar cannot be listed reliably at run time).
val guideDir = rootProject.layout.projectDirectory.dir("docs/guide")
val docsResources = layout.buildDirectory.dir("generated/docs-resources")
val bundleDocs by tasks.registering(Sync::class) {
    description = "Copies docs/guide/*.md and CHANGELOG.md into the server's resources."
    from(guideDir) {
        include("*.md")
        into("kilnmark/docs/guide")
    }
    from(rootProject.layout.projectDirectory.file("CHANGELOG.md")) {
        into("kilnmark/docs")
    }
    into(docsResources)
    val listFile = docsResources.map { it.file("kilnmark/docs/guide.list").asFile }
    val guideNames = guideDir.asFileTree.matching { include("*.md") }.elements
        .map { files -> files.map { it.asFile.name }.sorted() }
    doLast {
        listFile.get().writeText(guideNames.get().joinToString("\n", postfix = "\n"))
    }
}
sourceSets["main"].resources.srcDir(bundleDocs)

tasks.named<ProcessResources>("processResources") {
    dependsOn(buildUi)
    from(uiOut) {
        into("META-INF/resources")
    }
}

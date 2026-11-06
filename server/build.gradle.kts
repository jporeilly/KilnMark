// The Kilnmark server: one image that runs the api, scheduler and worker roles.
import com.github.jk1.license.filter.LicenseBundleNormalizer
import com.github.jk1.license.render.InventoryMarkdownReportRenderer
import com.github.jk1.license.render.JsonReportRenderer
import org.cyclonedx.gradle.CyclonedxDirectTask
import org.cyclonedx.model.Component

plugins {
    alias(libs.plugins.quarkus)
    id("com.github.jk1.dependency-license-report")
    id("org.cyclonedx.bom")
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

// The server's SBOM (CycloneDX JSON): every library on the runtime classpath, which is what the
// release's server folder holds. No build or repository address: the release names neither.
tasks.named<CyclonedxDirectTask>("cyclonedxDirectBom") {
    includeConfigs = listOf("runtimeClasspath")
    projectType = Component.Type.APPLICATION
    componentGroup = "io.kilnmark"
    componentName = "kilnmark-server"
    includeBuildSystem = false
    jsonOutput = layout.buildDirectory.file("sbom/kilnmark-server.cdx.json")
    xmlOutput.convention(null as RegularFile?)
    // The plugin adds the Git remote as a VCS reference unless one is given, and the repository is private.
    doLast {
        val file = jsonOutput.get().asFile
        @Suppress("UNCHECKED_CAST")
        val bom = groovy.json.JsonSlurper().parse(file) as MutableMap<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val main = (bom["metadata"] as MutableMap<String, Any?>)["component"] as MutableMap<String, Any?>
        main.remove("externalReferences")
        file.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(bom)) + "\n")
    }
}

description = "Kilnmark server"

// Security fixes newer than the Quarkus platform's pins (the SBOM scan in CI finds them): the
// enforced platform would otherwise hold these libraries at its own versions.
val securityPins = mapOf(
    "at.yawk.lz4:lz4-java" to libs.versions.lz4.java.get(),
    "org.xerial.snappy:snappy-java" to libs.versions.snappy.java.get(),
)
configurations.configureEach {
    resolutionStrategy.eachDependency {
        securityPins["${requested.group}:${requested.name}"]?.let {
            useVersion(it)
            because("security fix newer than the Quarkus platform's version")
        }
    }
}

dependencies {
    "implementation"(enforcedPlatform(libs.quarkus.bom))
    "implementation"("io.quarkus:quarkus-arc")
    "implementation"("io.quarkus:quarkus-rest-jackson")
    "implementation"("io.quarkus:quarkus-security")
    // Sign-in through the customer's identity provider (ADR 0014, ADR 0036): off unless configured.
    "implementation"("io.quarkus:quarkus-oidc")
    "implementation"("io.quarkus:quarkus-smallrye-health")
    "implementation"("io.quarkus:quarkus-smallrye-openapi")
    "implementation"("io.quarkus:quarkus-jdbc-postgresql")
    "implementation"("io.quarkus:quarkus-flyway")
    "implementation"("org.flywaydb:flyway-database-postgresql")
    "implementation"(project(":kiln-core"))
    "implementation"(project(":kiln-plugins"))
    // Pipeline nodes and expressions (filter, compute, ...), found through ServiceLoader (ADR 0022).
    "implementation"(project(":kiln-nodes"))
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

    // Email notifications (docs/design/notifications.md): Jakarta Mail through Eclipse Angus, version from the
    // Quarkus platform. Not the Quarkus mailer: the mail server is set at run time, in the database.
    "implementation"("org.eclipse.angus:angus-mail")

    "testImplementation"("io.quarkus:quarkus-junit5")
    // An SMTP server in the test's own process (Apache-2.0, test only), so mail tests need no container.
    "testImplementation"(libs.greenmail)
    "testImplementation"("io.rest-assured:rest-assured")
    // A stand-in identity provider (WireMock) and OIDC identities for tests of sign-in (ADR 0036).
    "testImplementation"("io.quarkus:quarkus-test-oidc-server")
    "testImplementation"("io.quarkus:quarkus-test-security-oidc")
    // Validates the data quality exports against the published OpenLineage and ODCS schemas (Apache-2.0, test only).
    "testImplementation"(libs.json.schema.validator)
}

// Arrow reads buffer addresses through java.nio; tests and dev mode need the same opening as the jar.
tasks.withType<Test>().configureEach {
    jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED", "--enable-native-access=ALL-UNNAMED")
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
    // Not under kilnmark.*: that prefix is the server configuration, which refuses names it does not know.
    // The assistant's shared fixtures: section anchors (also checked by the UI build) and the docs evaluation set.
    systemProperty("kmtest.docs.cases", rootProject.file("tests/docs").absolutePath)
    systemProperty("kmtest.eval.docs", rootProject.file("tests/ai-eval/docs.json").absolutePath)
    inputs.dir(rootProject.file("tests/docs"))
    inputs.file(rootProject.file("tests/ai-eval/docs.json"))
    // The run script's tests run containers/launch/kilnmark-run.* (ADR 0047): a change there reruns them.
    inputs.files(rootProject.fileTree("containers/launch") { include("kilnmark-run.*") })
    // and the environment that turns them on
    inputs.property("kmtest.sh", providers.environmentVariable("KILNMARK_TEST_SH").orElse(""))
    inputs.property("kmtest.powershell", providers.environmentVariable("KILNMARK_TEST_POWERSHELL").orElse(""))
}

// Checks the components reference (docs/guide/components-reference.md and reference-*.md) against the
// catalogue, and its versions tested against the integration tests' images: part of `check`, and run
// again when a guide or those tests change, without running the rest of the server's tests.
val referenceTest = "io.kilnmark.server.pipelines.ComponentsReferenceTest"
val checkReference by tasks.registering(Test::class) {
    group = "verification"
    description = "Checks the components reference against the catalogue and its examples as saving does."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching(referenceTest) }
    inputs.files(rootProject.fileTree("docs/guide") { include("*.md") })
    inputs.dir(rootProject.file("kiln-plugins/src/test/java"))
}
tasks.named("check") { dependsOn(checkReference) }

// Writes the generated parts of the components reference (docs/guide/components-reference.md and
// reference-*.md) from the catalogue again: run it after changing a node's, connector's or rule's
// settings, and commit the pages. ComponentsReferenceTest fails until it is run.
tasks.register<Test>("writeReference") {
    group = "documentation"
    description = "Writes the components reference's generated settings tables from the catalogue."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching(referenceTest) }
    systemProperty("kmtest.reference.write", "true")
    outputs.upToDateWhen { false }
}

// AI evaluation (ADR 0016): scores a running server's search and question answering against
// tests/ai-eval/golden.json with the models it is configured to use. Never part of `build`:
//   KILNMARK_TOKEN=km_... ./gradlew :server:aiEval [-Pkilnmark.eval.url=http://localhost:8780] [-Pkilnmark.eval.source=lab]
// It fails when a score falls below tests/ai-eval/baseline.json (less its tolerance), the release
// gate of ADR 0012; -Pkilnmark.eval.update-baseline=true accepts the new scores instead.
tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("ai-eval") }
    // Run by checkReference, which also reruns it when only the guides change.
    filter { excludeTestsMatching(referenceTest) }
    // Each test profile starts the application again in the same JVM; Gradle's default 512 MB ran out
    // once the sign-in and token profiles (ADR 0036-0038) were added.
    maxHeapSize = "1536m"
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

// The web UI's SBOM (CycloneDX JSON): the packages the built UI bundles, chosen as its licence
// notices are (scripts/ui-sbom.mjs), read from the lock file, so it needs no install.
val uiSbomFile = rootProject.layout.projectDirectory.file("dist/ui-sbom/kilnmark-ui.cdx.json")
val uiSbom by tasks.registering(Exec::class) {
    description = "Writes the web UI's CycloneDX SBOM to dist/ui-sbom (scripts/ui-sbom.mjs)."
    onlyIf { !skipUi }
    workingDir = uiDir.asFile
    commandLine(npm, "run", "--silent", "sbom", "--", uiSbomFile.asFile.absolutePath)
    inputs.files(uiDir.file("package.json"), uiDir.file("package-lock.json"), uiDir.file("scripts/ui-sbom.mjs"))
    outputs.file(uiSbomFile)
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

// The samples (samples/*/*.yaml), which ship as templates: served by GET /api/v1/samples and added
// with the person's own sources (io.kilnmark.server.pipelines.Samples). Copied under kilnmark/samples
// with a list of them, as the guides are.
val samplesDir = rootProject.layout.projectDirectory.dir("samples")
val samplesResources = layout.buildDirectory.dir("generated/samples-resources")
val bundleSamples by tasks.registering(Sync::class) {
    description = "Copies samples/**/*.yaml into the server's resources."
    from(samplesDir) {
        include("**/*.yaml")
        into("kilnmark/samples")
    }
    into(samplesResources)
    val listFile = samplesResources.map { it.file("kilnmark/samples/samples.list").asFile }
    val sampleFiles = samplesDir.asFileTree.matching { include("**/*.yaml") }.elements
        .map { files -> files.map { it.asFile.relativeTo(samplesDir.asFile).invariantSeparatorsPath }.sorted() }
    doLast {
        listFile.get().writeText(sampleFiles.get().joinToString("\n", postfix = "\n"))
    }
}
sourceSets["main"].resources.srcDir(bundleSamples)

tasks.named<ProcessResources>("processResources") {
    dependsOn(buildUi)
    from(uiOut) {
        into("META-INF/resources")
    }
}

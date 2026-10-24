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

    "testImplementation"("io.quarkus:quarkus-junit5")
    "testImplementation"("io.rest-assured:rest-assured")
}

// Arrow reads buffer addresses through java.nio; tests and dev mode need the same opening as the jar.
tasks.withType<Test>().configureEach {
    jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED", "--enable-native-access=ALL-UNNAMED")
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
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

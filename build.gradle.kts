import net.ltgt.gradle.errorprone.errorprone

// Kilnmark root build.
// All build output goes to /dist: one folder per module under dist/build,
// and the assembled release under dist/release.

plugins {
    alias(libs.plugins.quarkus) apply false
    id("com.github.jk1.dependency-license-report") version "3.1.4" apply false
    // A CycloneDX SBOM of what the server ships, with every release (docs/design/supply-chain.md).
    id("org.cyclonedx.bom") version "3.5.1" apply false
    // Static analysis of the Java code, inside the compiler (ADR 0042).
    alias(libs.plugins.errorprone) apply false
}

val distDir = layout.projectDirectory.dir("dist")

layout.buildDirectory.set(distDir.dir("build/root"))

subprojects {
    layout.buildDirectory.set(rootProject.layout.projectDirectory.dir("dist/build/${project.name}"))

    apply(plugin = "java")
    apply(plugin = "net.ltgt.errorprone")

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    }

    repositories { mavenCentral() }

    dependencies {
        "testImplementation"(platform(rootProject.libs.junit.bom))
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testRuntimeOnly"(rootProject.libs.junit.launcher)
        "errorprone"(rootProject.libs.errorprone.core)
        "compileOnly"(rootProject.libs.checker.qual)
        "testCompileOnly"(rootProject.libs.checker.qual)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
        options.compilerArgs.addAll(listOf("-Xlint:all,-classfile,-processing", "-Werror"))
        // Error Prone runs inside every compile, main and test (ADR 0042). With -Werror above, its
        // warnings fail the build as its errors do: fix the code, or suppress the one place with
        // @SuppressWarnings("CheckName") and a comment saying why.
        options.errorprone {
            disableWarningsInGeneratedCode.set(true)
            // Off for the whole build, each for the reason ADR 0042 gives: style, or read and found sound.
            disable(
                "StringConcatToTextBlock",
                "RefactorSwitch",
                "StatementSwitchToExpressionSwitch",
                "AssignmentExpression",
                "BadInstanceof",
                "InlineFormatString",
                "NonApiType",
                "StringSplitter",
                "ArrayRecordComponent",
            )
            // -PepPatch=Check1,Check2 applies those checks' suggested fixes to the sources (docs/development.md).
            providers.gradleProperty("epPatch").orNull?.let {
                errorproneArgs.addAll("-XepPatchChecks:" + it, "-XepPatchLocation:IN_PLACE")
            }
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // CI keeps no test reports, so print the whole failure message where the log shows it.
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

// Copies the runnable server and supporting files into dist/release: the product only. The
// reference lab, the tests and the developer docs never ship (ADR 0024); the release gets its own
// compose.yaml, made from containers/compose.release.yaml, not the developer one at the root, and
// a .env.example listing the secrets it requires: nothing that ships has a default password.
val releaseVersion = project.version.toString()
tasks.register<Sync>("assembleRelease") {
    group = "distribution"
    description = "Copies the release's files into dist/release (release also checks them)."
    dependsOn(":server:quarkusBuild", ":server:checkLicense", ":server:cyclonedxDirectBom", ":server:uiSbom")
    into(distDir.dir("release/kilnmark-${project.version}"))
    from(distDir.dir("build/server/quarkus-app")) {
        into("server")
        // Only this version's application jar: an earlier build's jar can linger in the build folder.
        exclude { it.name.startsWith("server-") && it.name.endsWith(".jar") && it.name != "server-${project.version}.jar" }
    }
    from("containers/compose.release.yaml") {
        rename { "compose.yaml" }
        filter { line -> line.replace("@VERSION@", releaseVersion) }
    }
    inputs.property("version", releaseVersion)
    // Every setting compose.yaml requires (the database password, the admin token), with no values.
    from("containers/release.env.example") { rename { ".env.example" } }
    from("containers") {
        into("containers")
        exclude("compose.release.yaml", "release.env.example", "backup.sh", "restore.sh", "launch/**", "tls/**")
    }
    // Backup and restore (ADR 0039), run from the release folder beside compose.yaml.
    from("containers") {
        include("backup.sh", "restore.sh")
        filePermissions { unix("rwxr-xr-x") }
    }
    // Start and stop with one click, on Windows and Ubuntu (INSTALL.md, "Start and stop with one click").
    from("containers/launch") {
        exclude("*.sh")
    }
    from("containers/launch") {
        include("*.sh")
        filePermissions { unix("rwxr-xr-x") }
    }
    // Kilnmark's public key, so the control script can check later downloads and images (ADR 0044).
    from("config/release") { include("cosign.pub") }
    // The default HTTPS folder compose.yaml mounts: Podman refuses to mount one that does not exist.
    from("containers/tls") { into("tls") }
    // The samples, as templates to read or add through the API (the server also bundles them).
    from("samples") {
        include("**/*.yaml")
        into("samples")
    }
    from("LICENSE", "NOTICE")
    // The full text of licences NOTICE names, such as the LGPL of the MySQL and MariaDB driver.
    from("config/licenses") { into("licenses") }
    from(distDir.dir("licenses")) { include("THIRD-PARTY-NOTICES.md") }
    // CycloneDX SBOMs of the server's libraries and the web UI's packages (docs/design/supply-chain.md).
    from(distDir.file("build/server/sbom/kilnmark-server.cdx.json")) { into("sbom") }
    from(distDir.dir("ui-sbom")) { include("*.cdx.json"); into("sbom") }
    // The web UI's packages and their licences, written by the UI build (ui/scripts/ui-notices.mjs).
    from(distDir.dir("ui")) {
        include("third-party-notices.txt")
        rename { "THIRD-PARTY-NOTICES-UI.txt" }
    }
}

// resolveAllDependencies: resolves every dependency any module can use, so one run writes the checksum
// of each into gradle/verification-metadata.xml (docs/development.md, "Dependency verification").
allprojects {
    tasks.register("resolveAllDependencies") {
        group = "help"
        description = "Resolves every resolvable configuration of this module (for dependency verification)."
        notCompatibleWithConfigurationCache("reads the module's configurations as it runs")
        val resolvable = provider { configurations.filter { it.isCanBeResolved } }
        doLast {
            // not lenient: a configuration that cannot be resolved fails the task, rather than leaving gaps
            resolvable.get().forEach { it.incoming.files.files }
        }
    }
}

// checkProductOnly: fails if the release depends on, names or bundles the reference lab.
apply(from = "gradle/product-only.gradle.kts")

// checkNoCustomerDrivers: drivers Kilnmark may not ship (Oracle's, under the Oracle Free Use Terms
// and Conditions) are installed by the customer (ADR 0007, docs/design/drivers.md). They are test
// dependencies only; this fails the release if one ever reaches its folder, whatever the jar is called.
val customerDrivers = listOf("ojdbc", "com.oracle.database", "oracle.jdbc")
// Test-only drivers the generic JDBC connector's tests register (H2): never shipped either. Matched
// by jar name prefix and by their classes, since "h2" alone is in too many other names.
val testDriverJars = listOf("h2-", "com.h2database.h2-")
val testDriverClasses = listOf("org/h2/")
tasks.register("checkNoCustomerDrivers") {
    group = "verification"
    description = "Fails if the release folder holds a driver the customer must install (Oracle's), or a test-only one (H2)."
    dependsOn("assembleRelease")
    val release = distDir.dir("release")
    doLast {
        val found = release.asFile.walkTopDown().filter { f ->
            f.isFile && (customerDrivers.any { f.name.lowercase().contains(it) }
                || f.name.endsWith(".jar") && testDriverJars.any { f.name.lowercase().startsWith(it) })
        }.toList()
        // a driver's classes, or a jar named like one, inside a jar, or a jar in a jar (fat jars), to any depth
        fun holdsDriver(input: java.io.InputStream, depth: Int): Boolean {
            val zip = java.util.zip.ZipInputStream(input)
            while (true) {
                val e = zip.nextEntry ?: return false
                val file = e.name.substringAfterLast('/').lowercase()
                if (e.name.startsWith("oracle/jdbc/") || testDriverClasses.any { e.name.startsWith(it) }
                    || (file.endsWith(".jar") && (customerDrivers.any { file.contains(it) }
                        || testDriverJars.any { file.startsWith(it) }))) {
                    return true
                }
                // the nested stream is read from this entry only, and must not close the outer one
                if (depth < 8 && file.endsWith(".jar") && holdsDriver(zip, depth + 1)) {
                    return true
                }
            }
        }
        val inside = release.asFile.walkTopDown().filter { it.isFile && it.name.endsWith(".jar") }.filter { jar ->
            jar.inputStream().buffered().use { holdsDriver(it, 0) }
        }.toList()
        if (found.isNotEmpty() || inside.isNotEmpty()) {
            throw GradleException("The release holds a driver Kilnmark may not ship (customers install it): "
                + (found + inside).distinct().joinToString(", "))
        }
        println("checkNoCustomerDrivers: no customer-installed driver in the release")
    }
}

tasks.register("release") {
    group = "distribution"
    description = "Assembles a release folder in dist/release, and checks it holds the product only."
    dependsOn("assembleRelease", "checkProductOnly", "checkNoCustomerDrivers")
}

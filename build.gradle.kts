// Kilnmark root build.
// All build output goes to /dist: one folder per module under dist/build,
// and the assembled release under dist/release.

plugins {
    alias(libs.plugins.quarkus) apply false
    id("com.github.jk1.dependency-license-report") version "3.1.4" apply false
}

val distDir = layout.projectDirectory.dir("dist")

layout.buildDirectory.set(distDir.dir("build/root"))

subprojects {
    layout.buildDirectory.set(rootProject.layout.projectDirectory.dir("dist/build/${project.name}"))

    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    }

    repositories { mavenCentral() }

    dependencies {
        "testImplementation"(platform(rootProject.libs.junit.bom))
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testRuntimeOnly"(rootProject.libs.junit.launcher)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
        options.compilerArgs.addAll(listOf("-Xlint:all,-classfile,-processing", "-Werror"))
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
    dependsOn(":server:quarkusBuild", ":server:checkLicense")
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
        exclude("compose.release.yaml", "release.env.example")
    }
    from("LICENSE", "NOTICE")
    // The full text of licences NOTICE names, such as the LGPL of the MySQL and MariaDB driver.
    from("config/licenses") { into("licenses") }
    from(distDir.dir("licenses")) { include("THIRD-PARTY-NOTICES.md") }
    // The web UI's packages and their licences, written by the UI build (ui/scripts/ui-notices.mjs).
    from(distDir.dir("ui")) {
        include("third-party-notices.txt")
        rename { "THIRD-PARTY-NOTICES-UI.txt" }
    }
}

// checkProductOnly: fails if the release depends on, names or bundles the reference lab.
apply(from = "gradle/product-only.gradle.kts")

// checkNoCustomerDrivers: drivers Kilnmark may not ship (Oracle's, under the Oracle Free Use Terms
// and Conditions) are installed by the customer (ADR 0007, docs/design/drivers.md). They are test
// dependencies only; this fails the release if one ever reaches its folder, whatever the jar is called.
val customerDrivers = listOf("ojdbc", "com.oracle.database", "oracle.jdbc")
tasks.register("checkNoCustomerDrivers") {
    group = "verification"
    description = "Fails if the release folder holds a driver the customer must install (Oracle's)."
    dependsOn("assembleRelease")
    val release = distDir.dir("release")
    doLast {
        val found = release.asFile.walkTopDown().filter { f ->
            f.isFile && customerDrivers.any { f.name.lowercase().contains(it) }
        }.toList()
        val inside = release.asFile.walkTopDown().filter { it.isFile && it.name.endsWith(".jar") }.filter { jar ->
            java.util.zip.ZipFile(jar).use { z ->
                z.entries().asSequence().any { e -> e.name.startsWith("oracle/jdbc/") }
            }
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

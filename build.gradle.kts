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
    }
}

// Copies the runnable server and supporting files into dist/release.
tasks.register<Sync>("release") {
    group = "distribution"
    description = "Assembles a release folder in dist/release."
    dependsOn(":server:quarkusBuild", ":server:checkLicense")
    into(distDir.dir("release/kilnmark-${project.version}"))
    from(distDir.dir("build/server/quarkus-app")) { into("server") }
    from("compose.yaml")
    from("containers") { into("containers") }
    from("LICENSE", "NOTICE")
    from(distDir.dir("licenses")) { include("THIRD-PARTY-NOTICES.md") }
}

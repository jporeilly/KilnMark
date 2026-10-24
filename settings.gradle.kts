pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

// Downloads the JDK 25 the build needs when the machine has none, so nobody has to change
// JAVA_HOME (which other software on the machine may rely on).
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "kilnmark"

include("kiln-core", "kiln-plugins", "graph", "server")

// Spikes: throwaway experiments, built only on request (./gradlew -Pspikes ...). Never shipped.
if (providers.gradleProperty("spikes").isPresent) {
    include("arrow-spike")
    project(":arrow-spike").projectDir = file("spikes/arrow")
}

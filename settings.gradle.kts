pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "kilnmark"

include("kiln-core", "kiln-plugins", "graph", "server")

// Spikes: throwaway experiments, built only on request (./gradlew -Pspikes ...). Never shipped.
if (providers.gradleProperty("spikes").isPresent) {
    include("arrow-spike")
    project(":arrow-spike").projectDir = file("spikes/arrow")
}

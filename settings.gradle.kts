pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "kilnmark"

include("kiln-core", "kiln-plugins", "graph", "server")

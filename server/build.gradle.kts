// The Kilnmark server: one image that runs the api, scheduler and worker roles.
plugins {
    alias(libs.plugins.quarkus)
}

description = "Kilnmark server"

dependencies {
    "implementation"(enforcedPlatform(libs.quarkus.bom))
    "implementation"("io.quarkus:quarkus-arc")
    "implementation"("io.quarkus:quarkus-rest-jackson")
    "implementation"("io.quarkus:quarkus-smallrye-health")
    "implementation"("io.quarkus:quarkus-smallrye-openapi")
    "implementation"("io.quarkus:quarkus-jdbc-postgresql")
    "implementation"("io.quarkus:quarkus-flyway")
    "implementation"("org.flywaydb:flyway-database-postgresql")
    "implementation"(project(":kiln-core"))
    "implementation"(project(":kiln-plugins"))
    "implementation"(project(":graph"))

    "testImplementation"("io.quarkus:quarkus-junit5")
    "testImplementation"("io.rest-assured:rest-assured")
}

// Arrow reads buffer addresses through java.nio; tests and dev mode need the same opening as the jar.
tasks.withType<Test>().configureEach {
    jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED")
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
}

// The web UI, when built (cd ui && npm run build), is served by the server from /.
tasks.named<ProcessResources>("processResources") {
    from(rootProject.layout.projectDirectory.dir("dist/ui")) {
        into("META-INF/resources")
    }
}

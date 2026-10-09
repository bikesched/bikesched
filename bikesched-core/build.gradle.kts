plugins {
    id("bikesched.java-library")
}

description = "PostgreSQL-backed job scheduler: jobs, durable steps, recurring jobs and events"

dependencies {
    implementation(libs.postgresql)
    // Logging facade only; applications pick the binding (tests use logback-classic, see the testing bundle).
    implementation(libs.slf4j.api)

    // Architecture rules of the package structure (ArchitectureTest).
    testImplementation(libs.archunit)
}

// Makes the library version available at runtime (Bikesched.version()).
tasks.processResources {
    // A local value: a lambda referencing a script property is not configuration-cache compatible.
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("org/bikesched/version.properties") { expand("version" to version) }
}

tasks.jar {
    val version = project.version.toString()
    manifest {
        attributes(
            "Implementation-Title" to "bikesched",
            "Implementation-Version" to version,
        )
    }
}

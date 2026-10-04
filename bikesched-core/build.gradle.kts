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

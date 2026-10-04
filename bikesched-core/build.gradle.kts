plugins {
    id("bikesched.java-library")
}

dependencies {
    implementation(libs.postgresql)
    // Logging facade only; applications pick the binding (tests use logback-classic, see the testing bundle).
    implementation(libs.slf4j.api)

    // Architecture rules of the package structure (ArchitectureTest).
    testImplementation(libs.archunit)
}

// Dev helper: runs org.bikesched.Starter (test sources, so it isn't shipped in the library jar)
// against the Postgres from compose.yaml.
tasks.register<JavaExec>("runStarter") {
    group = "application"
    description = "Starts bikesched against the docker compose Postgres."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "org.bikesched.Starter"
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
}

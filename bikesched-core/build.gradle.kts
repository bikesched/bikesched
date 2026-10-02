plugins {
    id("bikesched.java-library")
}

dependencies {
    implementation(libs.postgresql)
    // Logging facade only; applications pick the binding (tests use logback-classic, see the testing bundle).
    implementation(libs.slf4j.api)
}

// Dev helper: runs bikesched.core.Starter (test sources, so it isn't shipped in the library jar)
// against the Postgres from compose.yaml.
tasks.register<JavaExec>("runStarter") {
    group = "application"
    description = "Starts bikesched against the docker compose Postgres."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "bikesched.core.Starter"
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
}

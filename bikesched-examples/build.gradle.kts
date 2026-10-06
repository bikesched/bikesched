plugins {
    id("bikesched.java-conventions")
    application
}

dependencies {
    // Payloads, events and step results are records, mapped to JSON with bikesched-jackson
    // (brings bikesched-core and Jackson).
    implementation(project(":bikesched-jackson"))
    implementation(libs.postgresql)
    implementation(libs.slf4j.api)
    // Waits for job outcomes in the examples' main classes.
    implementation(libs.awaitility)
    runtimeOnly(libs.logback.classic)
}

// ./gradlew :bikesched-examples:run against the Postgres from compose.yaml (honors POSTGRES_PORT).
application {
    mainClass = "org.bikesched.examples.taxi.TaxiBookingExample"
}

// ./gradlew :bikesched-examples:runSimple: one handler, one job, same database as run.
tasks.register<JavaExec>("runSimple") {
    group = "application"
    description = "Runs the simple example against the docker compose Postgres."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.bikesched.examples.simple.SimpleExample"
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
}

// ./gradlew :bikesched-examples:runEvent: a job waiting for the event its service publishes.
tasks.register<JavaExec>("runEvent") {
    group = "application"
    description = "Runs the event example against the docker compose Postgres."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.bikesched.examples.event.EventExample"
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
}

// ./gradlew :bikesched-examples:runCinema: the cinema ticket example, same database as run.
tasks.register<JavaExec>("runCinema") {
    group = "application"
    description = "Runs the cinema ticket example against the docker compose Postgres."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.bikesched.examples.cinema.CinemaExample"
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
}

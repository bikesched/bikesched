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

// ./gradlew :bikesched-examples:runCinema: the cinema ticket example, same database as run.
tasks.register<JavaExec>("runCinema") {
    group = "application"
    description = "Runs the cinema ticket example against the docker compose Postgres."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.bikesched.examples.cinema.CinemaExample"
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
}

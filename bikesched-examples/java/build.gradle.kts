plugins {
    id("bikesched.java-conventions")
    application
}

dependencies {
    // Payloads, events and step results are records, mapped to JSON with bikesched-jackson
    // (brings bikesched-core and Jackson).
    implementation(project(":bikesched-jackson"))
    // @JobHandler methods (annotation example).
    implementation(project(":bikesched-annotations"))
    implementation(libs.postgresql)
    implementation(libs.slf4j.api)
    // Waits for job outcomes in the examples' main classes.
    implementation(libs.awaitility)
    runtimeOnly(libs.logback.classic)
}

// ./gradlew :bikesched-examples:java:run against the Postgres from compose.yaml (honors POSTGRES_PORT).
application {
    mainClass = "org.bikesched.examples.taxi.TaxiBookingExample"
}

// ./gradlew :bikesched-examples:java:runSimple: one handler, one job, same database as run.
tasks.register<JavaExec>("runSimple") {
    group = "application"
    description = "Runs the simple example against the docker compose Postgres."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.bikesched.examples.simple.SimpleExample"
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
}

// ./gradlew :bikesched-examples:java:runEvent: a job waiting for the event its service publishes.
tasks.register<JavaExec>("runEvent") {
    group = "application"
    description = "Runs the event example against the docker compose Postgres."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.bikesched.examples.event.EventExample"
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
}

// ./gradlew :bikesched-examples:java:runPublish: a job publishing an event once, another one waiting.
tasks.register<JavaExec>("runPublish") {
    group = "application"
    description = "Runs the publish example against the docker compose Postgres."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.bikesched.examples.publish.PublishExample"
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
}

// ./gradlew :bikesched-examples:java:runOutcome: business outcomes (declined cards) as step results.
tasks.register<JavaExec>("runOutcome") {
    group = "application"
    description = "Runs the outcome example against the docker compose Postgres."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.bikesched.examples.outcome.OutcomeExample"
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
}

// ./gradlew :bikesched-examples:java:runPoll: a webhook with a polling fallback.
tasks.register<JavaExec>("runPoll") {
    group = "application"
    description = "Runs the poll example against the docker compose Postgres."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.bikesched.examples.poll.PollExample"
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
}

// ./gradlew :bikesched-examples:java:runAnnotation: job handlers declared as annotated methods.
tasks.register<JavaExec>("runAnnotation") {
    group = "application"
    description = "Runs the annotation example against the docker compose Postgres."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.bikesched.examples.annotation.AnnotationExample"
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
}

// ./gradlew :bikesched-examples:java:runCinema: the cinema ticket example, same database as run.
tasks.register<JavaExec>("runCinema") {
    group = "application"
    description = "Runs the cinema ticket example against the docker compose Postgres."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.bikesched.examples.cinema.CinemaExample"
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
}

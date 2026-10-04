plugins {
    id("bikesched.java-conventions")
    application
}

dependencies {
    implementation(project(":bikesched-core"))
    implementation(libs.postgresql)
    implementation(libs.slf4j.api)
    // Payloads, events and step results are records, mapped to JSON with Jackson.
    implementation(libs.jackson.databind)
    runtimeOnly(libs.logback.classic)
}

// ./gradlew :bikesched-examples:run against the Postgres from compose.yaml (honors POSTGRES_PORT).
application {
    mainClass = "org.bikesched.examples.taxi.TaxiBookingExample"
}

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
    runtimeOnly(libs.logback.classic)
}

// ./gradlew :bikesched-examples:run against the Postgres from compose.yaml (honors POSTGRES_PORT).
application {
    mainClass = "org.bikesched.examples.taxi.TaxiBookingExample"
}

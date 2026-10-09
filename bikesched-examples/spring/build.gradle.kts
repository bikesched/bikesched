// The Spring Boot example: a regular Spring Boot application, wired by the starter.
plugins {
    id("bikesched.java-conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
    // Builds the node from the context's DataSource and discovers the @JobHandler methods.
    implementation(project(":bikesched-spring-boot-starter"))
    // Payloads and events are records; the starter maps them with the context's Jackson.
    implementation(project(":bikesched-jackson"))
    // JPA persists the order and the fulfillment.
    implementation(libs.spring.boot.starter.data.jpa)
    runtimeOnly(libs.postgresql)
    // Waits for the job outcome in the application runner.
    implementation(libs.awaitility)
}

// ./gradlew :bikesched-examples:spring:bootRun against the Postgres from compose.yaml (honors
// POSTGRES_PORT, see application.properties).

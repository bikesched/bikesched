// SPDX-License-Identifier: Apache-2.0
plugins {
    id("bikesched.java-conventions")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

dependencies {
    // bikesched-kotlin provides the Kotlin DSL extensions on the Java API.
    implementation(project(":bikesched-kotlin"))
    // Wait for the simple example's job in main.
    implementation(project(":bikesched-test"))
    implementation(project(":bikesched-jackson"))
    // PostgreSQL JDBC driver (bikesched-core needs it at runtime; Exposed needs it too).
    implementation(libs.postgresql)
    implementation(libs.slf4j.api)
    // Ktor server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    // Ktor client for the demo client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    // Exposed (JDBC wrapper for the order database)
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    // kotlinx-serialization for JsonMapper and Ktor
    implementation(libs.kotlinx.serialization.json)
    // JSR 354 money API with the Moneta reference implementation
    implementation(libs.moneta)
    // HikariCP for the Exposed data source
    implementation(libs.hikaricp)
    runtimeOnly(libs.logback.classic)
}

application {
    mainClass = "org.bikesched.examples.kotlin.shop.ShopExampleKt"
}

tasks.register<JavaExec>("runSimple") {
    group = "application"
    description = "Runs one Kotlin job with two steps and an event published after 15 seconds."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.bikesched.examples.kotlin.simple.SimpleExample"
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
}

tasks.register<JavaExec>("runShop") {
    group = "application"
    description = "Runs the Kotlin shop and a client placing an order against the docker compose Postgres."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.bikesched.examples.kotlin.shop.ShopExampleKt"
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
}

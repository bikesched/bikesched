// SPDX-License-Identifier: Apache-2.0
plugins {
    id("bikesched.java-library")
}

description = "Spring Boot starter for bikesched: auto-configuration, @JobHandler discovery, metrics"

dependencies {
    api(project(":bikesched-core"))
    api(project(":bikesched-annotations"))
    // @AutoConfiguration, the conditions and @ConfigurationProperties are part of the API (the
    // properties record) and of the auto-configuration.
    api(libs.spring.boot.autoconfigure)
    // core has it as an implementation dependency, so it is on the runtime class path but not
    // on the compile module path of this module.
    compileOnly(libs.slf4j.api)

    // Optional integrations: only the guarded methods of the auto-configuration touch them.
    compileOnly(project(":bikesched-jackson"))
    compileOnly(project(":bikesched-micrometer"))
    // Optional: BikeschedTransactions reads the caller's transaction-bound connection.
    compileOnly(libs.spring.tx)
    compileOnly(libs.spring.jdbc)

    testImplementation(project(":bikesched-jackson"))
    testImplementation(project(":bikesched-micrometer"))
    testImplementation(project(":bikesched-test"))
    testImplementation(libs.spring.boot.starter.jdbc)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.micrometer.core)
    testImplementation(libs.postgresql)
}

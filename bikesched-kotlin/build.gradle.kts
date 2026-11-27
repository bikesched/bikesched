plugins {
    id("bikesched.kotlin-library")
}

description = "Kotlin extensions for bikesched"

dependencies {
    api(project(":bikesched-core"))
    // Handlers are suspend lambdas, run with runBlocking on the job's thread (ADR 0065).
    api(libs.kotlinx.coroutines.core)

    // Tests map Kotlin data classes to JSON with bikesched-jackson plus Jackson's Kotlin module.
    testImplementation(project(":bikesched-jackson"))
    testImplementation(libs.jackson.module.kotlin)
    testImplementation(libs.postgresql)
}

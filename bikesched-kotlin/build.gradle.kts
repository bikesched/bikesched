plugins {
    id("bikesched.kotlin-library")
}

description = "Kotlin extensions for bikesched"

dependencies {
    api(project(":bikesched-core"))

    // Tests map Kotlin data classes to JSON with bikesched-jackson plus Jackson's Kotlin module.
    testImplementation(project(":bikesched-jackson"))
    testImplementation(libs.jackson.module.kotlin)
    testImplementation(libs.postgresql)
}

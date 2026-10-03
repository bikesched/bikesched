plugins {
    id("bikesched.kotlin-library")
}

dependencies {
    api(project(":bikesched-core"))

    // Tests map Kotlin data classes to JSON with Jackson and its Kotlin module.
    testImplementation(libs.postgresql)
    testImplementation(libs.jackson.databind)
    testImplementation(libs.jackson.module.kotlin)
}

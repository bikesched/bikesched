plugins {
    id("bikesched.java-library")
}

description = "Micrometer metrics for bikesched"

dependencies {
    api(project(":bikesched-core"))
    api(libs.micrometer.core)

    testImplementation(project(":bikesched-test"))
    testImplementation(libs.postgresql)
}

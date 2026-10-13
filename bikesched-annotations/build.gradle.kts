plugins {
    id("bikesched.java-library")
}

description = "Annotations for bikesched: job handlers declared as annotated methods"

dependencies {
    api(project(":bikesched-core"))

    testImplementation(project(":bikesched-jackson"))
    testImplementation(project(":bikesched-test"))
    testImplementation(libs.postgresql)
}

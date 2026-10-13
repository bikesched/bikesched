plugins {
    id("bikesched.java-library")
}

description = "Testing support for applications using bikesched"

dependencies {
    api(project(":bikesched-core"))
    // Backs the wait helpers; never appears in a public signature (see docs/specs/bikesched-test.md).
    implementation(libs.awaitility)

    testImplementation(libs.postgresql)
}

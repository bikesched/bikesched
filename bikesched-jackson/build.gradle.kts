plugins {
    id("bikesched.java-library")
}

description = "Jackson 3 JsonMapper for bikesched"

dependencies {
    api(project(":bikesched-core"))
    // Part of the API: JacksonJsonMapper accepts a configured ObjectMapper.
    api(libs.jackson.databind)

    testImplementation(project(":bikesched-test"))
    testImplementation(libs.postgresql)
}

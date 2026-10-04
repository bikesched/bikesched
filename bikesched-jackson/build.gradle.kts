plugins {
    id("bikesched.java-library")
}

dependencies {
    api(project(":bikesched-core"))
    // Part of the API: JacksonJsonMapper accepts a configured ObjectMapper.
    api(libs.jackson.databind)

    testImplementation(libs.postgresql)
}

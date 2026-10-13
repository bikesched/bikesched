pluginManagement {
    includeBuild("build-logic")
}

plugins {
    // From build-logic: JDK toolchain auto-provisioning (plugin version in gradle/libs.versions.toml).
    id("bikesched.toolchains")
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "bikesched"

include("bikesched-core")
include("bikesched-kotlin")
include("bikesched-jackson")
include("bikesched-annotations")
include("bikesched-cron")
include("bikesched-micrometer")
include("bikesched-spring-boot-starter")
include("bikesched-admin")
include("bikesched-test")
include("bikesched-stress-tests")
include("bikesched-examples:java")
include("bikesched-examples:kotlin")
include("bikesched-examples:spring")
include("bikesched-website")

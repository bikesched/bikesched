// Settings convention: lets Gradle auto-provision the JDK toolchain if it isn't installed locally.
// The plugin version is managed in gradle/libs.versions.toml (see build-logic/build.gradle.kts).

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention")
}

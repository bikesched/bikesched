// Shared conventions for all Java modules of bikesched: toolchain, language baseline, compiler
// flags, formatting and the test setup. Published libraries apply bikesched.java-library on top.

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    java
    id("com.diffplug.spotless")
}

// Version catalog access from a precompiled script plugin.
val libs = versionCatalogs.named("libs")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.findVersion("java").get().requiredVersion)
    }
}

// Built with the toolchain JDK (`java`), compiled for the language baseline (`java-release`, ADR
// 0049): `--release` rejects language features and JDK APIs newer than the baseline.
val javaRelease = libs.findVersion("java-release").get().requiredVersion

tasks.withType<JavaCompile>().configureEach {
    options.release = javaRelease.toInt()
    options.encoding = "UTF-8"
    // Without `try`: a resource that only scopes a block (an MDC entry, a log context) is never
    // referenced in it, and Java 21 has no unnamed variables to say so.
    options.compilerArgs.addAll(listOf("-Xlint:all,-try", "-Werror"))
}

// Formatting: `spotlessCheck` runs as part of `check`, `spotlessApply` fixes violations.
// Use the google-java-format IntelliJ plugin with the same version for identical IDE formatting.
// Every source file starts with the SPDX license header; Spotless skips package-info.java and
// module-info.java, which carry it by hand.
spotless {
    // Fixed line endings instead of reading .gitattributes, which needs JGit (excluded from the
    // Spotless plugin in build-logic/build.gradle.kts, it clashes with JReleaser's).
    lineEndings = com.diffplug.spotless.LineEnding.UNIX
    java {
        googleJavaFormat(libs.findVersion("google-java-format").get().requiredVersion)
        formatAnnotations()
        licenseHeader("// SPDX-License-Identifier: Apache-2.0")
    }
}

dependencies {
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(platform(libs.findLibrary("testcontainers-bom").get()))
    testImplementation(libs.findBundle("testing").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

// Kotlin modules target the same baseline, against the JDK API of that version.
pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
    extensions.configure<KotlinJvmProjectExtension> {
        compilerOptions {
            jvmTarget = JvmTarget.fromTarget(javaRelease)
            freeCompilerArgs.add("-Xjdk-release=$javaRelease")
        }
    }
}

// Tests run on the toolchain JDK; `-PtestJavaVersion=21` runs them on another JDK instead (CI
// tests on the baseline).
val testJavaVersion = providers.gradleProperty("testJavaVersion").orNull

tasks.withType<Test>().configureEach {
    if (testJavaVersion != null) {
        javaLauncher =
            javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(testJavaVersion) }
    }
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

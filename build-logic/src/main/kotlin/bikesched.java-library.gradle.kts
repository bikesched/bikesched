// Shared conventions for all Java library modules of bikesched.

plugins {
    `java-library`
    id("com.diffplug.spotless")
}

// Version catalog access from a precompiled script plugin.
val libs = versionCatalogs.named("libs")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.findVersion("java").get().requiredVersion)
    }
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

// The Javadoc documents the API only: the implementation lives in non-exported `internal` packages.
// It runs in classpath mode (the module descriptor is excluded too), finding the compiled internal
// classes the API sources refer to on the classpath.
tasks.withType<Javadoc>().configureEach {
    modularity.inferModulePath = false
    exclude("module-info.java", "**/internal/**")
    (options as StandardJavadocDocletOptions).addBooleanOption("Werror", true)
}

// Formatting: `spotlessCheck` runs as part of `check`, `spotlessApply` fixes violations.
// Use the google-java-format IntelliJ plugin with the same version for identical IDE formatting.
spotless {
    java {
        googleJavaFormat(libs.findVersion("google-java-format").get().requiredVersion)
        formatAnnotations()
    }
}

dependencies {
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(platform(libs.findLibrary("testcontainers-bom").get()))
    testImplementation(libs.findBundle("testing").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

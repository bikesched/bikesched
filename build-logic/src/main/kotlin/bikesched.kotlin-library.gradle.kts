// Conventions for the published Kotlin library modules of bikesched: the shared Java conventions
// (toolchain, formatting, tests) plus the Kotlin compiler in explicit API mode with warnings as
// errors, ktfmt formatting, and sources and Dokka jars.

plugins {
    id("bikesched.java-conventions")
    `java-library`
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.dokka")
}

// Version catalog access from a precompiled script plugin.
val libs = versionCatalogs.named("libs")

kotlin {
    // Every public declaration needs an explicit visibility and return type.
    explicitApi()
    compilerOptions {
        allWarningsAsErrors = true
    }
}

// module-info.java is compiled by javac, which must see the Kotlin classes as part of the module
// (otherwise "package is empty" for every exported Kotlin package).
tasks.named<JavaCompile>("compileJava") {
    val moduleInfo = file("src/main/java/module-info.java")
    val kotlinClasses =
        tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin")
            .flatMap { it.destinationDirectory }
    options.compilerArgumentProviders.add(
        CommandLineArgumentProvider {
            val moduleName =
                Regex("""module\s+([\w.]+)""").find(moduleInfo.readText())!!.groupValues[1]
            listOf("--patch-module", "$moduleName=${kotlinClasses.get().asFile.absolutePath}")
        }
    )
}

spotless {
    kotlin {
        ktfmt(libs.findVersion("ktfmt").get().requiredVersion).googleStyle()
    }
}

java {
    withSourcesJar()
    withJavadocJar()
}

// The API docs come from Dokka: javadoc only sees module-info.java in a Kotlin module.
tasks.named<Javadoc>("javadoc") {
    enabled = false
}

tasks.named<Jar>("javadocJar") {
    from(tasks.named("dokkaGeneratePublicationHtml"))
}

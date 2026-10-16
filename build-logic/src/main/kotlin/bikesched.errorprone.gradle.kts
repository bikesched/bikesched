// Error Prone with NullAway for the main sources of a Java module: the JSpecify annotations
// (@NullMarked packages, @Nullable) are checked at compile time, so dereferencing a possibly null
// value or passing null where it is not allowed fails the build (ADR 0048). Error Prone's own
// default checks run too; their warnings fail the build like any other (-Werror). Tests are not
// checked.

import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone

plugins {
    java
    id("net.ltgt.errorprone")
}

// Version catalog access from a precompiled script plugin.
val libs = versionCatalogs.named("libs")

dependencies {
    errorprone(libs.findLibrary("errorprone-core").get())
    errorprone(libs.findLibrary("nullaway").get())
    // NullAway reads the package annotations of dependencies: the PostgreSQL driver's refer to
    // the Checker Framework, whose missing classes javac would report as warnings.
    compileOnly(libs.findLibrary("checker-qual").get())
}

tasks.named<JavaCompile>("compileJava") {
    // checker-qual is on the module path but required by no module.
    options.compilerArgs.addAll(listOf("--add-modules", "org.checkerframework.checker.qual"))
    options.errorprone {
        check("NullAway", CheckSeverity.ERROR)
        // Only @NullMarked code is checked; unannotated libraries are trusted.
        option("NullAway:OnlyNullMarked", "true")
        // JSpecify semantics: generics, arrays and type-use annotations.
        option("NullAway:JSpecifyMode", "true")
    }
}

tasks.named<JavaCompile>("compileTestJava") {
    options.errorprone.enabled = false
}

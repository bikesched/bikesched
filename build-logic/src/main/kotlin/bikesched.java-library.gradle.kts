// Conventions for the published Java library modules of bikesched: the shared Java conventions
// (toolchain, compiler flags, formatting, tests) plus sources and Javadoc jars, published to GitHub
// Packages (bikesched.publishing).

plugins {
    id("bikesched.java-conventions")
    `java-library`
    id("bikesched.publishing")
}

java {
    withSourcesJar()
    withJavadocJar()
}

// The Javadoc documents the API only: the implementation lives in non-exported `internal` packages.
// It runs in classpath mode (the module descriptor is excluded too), finding the compiled internal
// classes the API sources refer to on the classpath.
tasks.withType<Javadoc>().configureEach {
    modularity.inferModulePath = false
    exclude("module-info.java", "**/internal/**")
    (options as StandardJavadocDocletOptions).addBooleanOption("Werror", true)
}

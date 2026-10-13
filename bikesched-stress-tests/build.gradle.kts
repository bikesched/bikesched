plugins {
    id("bikesched.java-conventions")
}

description = "Load and failure tests of bikesched, run on demand before a release"

dependencies {
    testImplementation(project(":bikesched-test"))
    testImplementation(libs.postgresql)
}

// The tests restart databases, pause processes and run for minutes: `build` compiles and formats
// them but does not run them. `./gradlew :bikesched-stress-tests:stressTest` does, see README.md.
tasks.test {
    enabled = false
}

tasks.register<Test>("stressTest") {
    group = "verification"
    description = "Runs the load and failure tests (restarts databases, pauses processes)."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    // Every run measures anew: never up to date, never from the build cache.
    outputs.upToDateWhen { false }
    // Scales the load tests: ./gradlew :bikesched-stress-tests:stressTest -Pstress.jobs=20000
    providers.gradlePropertiesPrefixedBy("stress.").get().forEach { (key, value) ->
        systemProperty(key, value)
    }
    // Room for the load tests' bookkeeping (latencies and step runs per job).
    maxHeapSize = "1g"
}

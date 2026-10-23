// Root project: shared configuration lives in build-logic (convention plugins),
// all versions live in gradle/libs.versions.toml.

plugins {
    // Release tooling: version calculation, changelog and GitHub Release (RELEASING.md).
    id("bikesched.release")
}

// The catalog holds the branch's next version as a snapshot; the release workflow builds the
// release with -PreleaseVersion=X.Y.Z (computed by `releaseVersions`) instead of committing it.
val bikeschedVersion =
    providers.gradleProperty("releaseVersion").getOrElse(libs.versions.bikesched.get())

allprojects {
    group = "org.bikesched"
    version = bikeschedVersion
}

// The release tooling's own tests (version calculation, docs version replacement) run with the
// build.
tasks.named("check") {
    dependsOn(gradle.includedBuild("build-logic").task(":check"))
}

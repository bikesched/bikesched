// Root project: shared configuration lives in build-logic (convention plugins),
// all versions live in gradle/libs.versions.toml.

allprojects {
    group = "bikesched"
    version = rootProject.libs.versions.bikesched.get()
}

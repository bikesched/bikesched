plugins {
    `kotlin-dsl`
}

dependencies {
    // Gradle plugins applied by the convention plugins; versions come from libs.versions.toml.
    implementation(plugin(libs.plugins.foojay.resolver))
    // Without JGit: JReleaser needs JGit 5, Spotless would bring JGit 7 onto the same classpath.
    // Spotless only uses it to read line endings from .gitattributes, see
    // bikesched.java-conventions.
    implementation(plugin(libs.plugins.spotless)) { exclude(group = "org.eclipse.jgit") }
    implementation(plugin(libs.plugins.kotlin.jvm))
    implementation(plugin(libs.plugins.kotlin.serialization))
    implementation(plugin(libs.plugins.dokka))
    implementation(plugin(libs.plugins.errorprone))
    implementation(plugin(libs.plugins.jreleaser))

    // Release versions (bikesched.release).
    implementation(libs.jsemver)
}

// Maps a catalog plugin alias to its plugin marker artifact so it can be used as a dependency.
fun plugin(plugin: Provider<PluginDependency>): Provider<String> =
    plugin.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" }

plugins {
    `kotlin-dsl`
}

dependencies {
    // Gradle plugins applied by the convention plugins; versions come from libs.versions.toml.
    implementation(plugin(libs.plugins.foojay.resolver))
    implementation(plugin(libs.plugins.spotless))
    implementation(plugin(libs.plugins.kotlin.jvm))
    implementation(plugin(libs.plugins.kotlin.serialization))
    implementation(plugin(libs.plugins.dokka))
    implementation(plugin(libs.plugins.errorprone))
}

// Maps a catalog plugin alias to its plugin marker artifact so it can be used as a dependency.
fun plugin(plugin: Provider<PluginDependency>): Provider<String> =
    plugin.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" }

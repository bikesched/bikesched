// Publishing of the library modules (applied by bikesched.java-library and bikesched.kotlin-library):
// one Maven publication with the jar, sources and Javadoc jars, published to GitHub Packages with
// `./gradlew publishAllPublicationsToGitHubPackagesRepository`.
//
// Credentials come from the Gradle properties gitHubPackagesUsername and gitHubPackagesPassword,
// e.g. via the environment variables ORG_GRADLE_PROJECT_gitHubPackagesUsername and
// ORG_GRADLE_PROJECT_gitHubPackagesPassword (in CI: the actor and GITHUB_TOKEN).

plugins {
    `maven-publish`
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name = project.name
                description = provider { project.description }
                url = "https://github.com/sbuettner/bikesched"
                scm {
                    url = "https://github.com/sbuettner/bikesched"
                    connection = "scm:git:https://github.com/sbuettner/bikesched.git"
                }
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/sbuettner/bikesched")
            credentials(PasswordCredentials::class)
        }
    }
}

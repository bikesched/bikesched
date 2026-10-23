// Publishing of the library modules (applied by bikesched.java-library and bikesched.kotlin-library):
// one Maven publication with the jar, sources and Javadoc jars, published to GitHub Packages with
// `./gradlew publishAllPublicationsToGitHubPackagesRepository`.
//
// Credentials come from the Gradle properties GitHubPackagesUsername and GitHubPackagesPassword
// (named after the repository, case-sensitive), e.g. via the environment variables
// ORG_GRADLE_PROJECT_GitHubPackagesUsername and ORG_GRADLE_PROJECT_GitHubPackagesPassword (in CI:
// the actor and GITHUB_TOKEN).

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
                url = "https://github.com/bikesched/bikesched"
                licenses {
                    license {
                        name = "Apache-2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                        distribution = "repo"
                    }
                }
                developers {
                    developer {
                        id = "sbuettner"
                        name = "Simon Büttner"
                        email = "mail@bikesched.org"
                    }
                }
                scm {
                    url = "https://github.com/bikesched/bikesched"
                    connection = "scm:git:https://github.com/bikesched/bikesched.git"
                }
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/bikesched/bikesched")
            credentials(PasswordCredentials::class)
        }
    }
}

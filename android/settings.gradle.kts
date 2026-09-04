pluginManagement {
    repositories {
        // No content filters here on purpose. They are only a resolution optimisation, and the
        // regex form is a well-known source of Kotlin escaping bugs for no real benefit.
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    // Credentials are read here rather than inside the repository block so the repository can be
    // declared conditionally. A Maven repository configured with a null username fails the build
    // the moment anything is resolved, with a message that says nothing about tokens, so a
    // developer who has not set one up yet must not see this repository at all.
    //
    // Put these in ~/.gradle/gradle.properties, never in the repository:
    //     aryGithubUser=<github username>
    //     aryGithubToken=<token with read:packages>
    val githubUser: String? = providers.gradleProperty("aryGithubUser").orNull
        ?: System.getenv("ARY_GITHUB_USER")
    val githubToken: String? = providers.gradleProperty("aryGithubToken").orNull
        ?: System.getenv("ARY_GITHUB_TOKEN")
    val githubOwner: String = providers.gradleProperty("aryGithubOwner").getOrElse("arysoftware")
    val githubRepo: String = providers.gradleProperty("aryGithubRepo").getOrElse("ary-push-sdk")

    // Local staging output of:
    //     ./gradlew :sdk:publishReleasePublicationToLocalStagingRepository
    val localStaging = rootDir.resolve("build/local-maven")

    repositories {
        google()
        mavenCentral()

        // The samples consume the published SDK exactly as a real application does. GitHub
        // Packages is the default host: JitPack needs a paid plan for a private repository,
        // GitHub Packages only needs a free token with read:packages.
        if (!githubUser.isNullOrBlank() && !githubToken.isNullOrBlank()) {
            maven {
                name = "aryGithubPackages"
                url = uri("https://maven.pkg.github.com/$githubOwner/$githubRepo")
                credentials {
                    username = githubUser
                    password = githubToken
                }
            }
        }

        // Listed last so a real published artifact always wins, and declared only when it
        // exists, so it never masks a genuine "not published yet" failure with a stale local
        // copy. This is what makes the samples buildable while working on the SDK itself: no
        // credentials, and no project(":sdk") substitution that would skip POM generation and
        // AAR packaging — the samples resolve a real artifact, exactly as an application does.
        if (localStaging.isDirectory) {
            maven {
                name = "aryLocalStaging"
                url = localStaging.toURI()
            }
        }
    }
}

rootProject.name = "ary-push-android"

include(":sdk")
include(":sample-basic")
include(":sample-existing-firebase")

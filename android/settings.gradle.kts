pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\.android.*")
                includeGroupByRegex("com\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()

        // The samples consume the published SDK from GitHub Packages, exactly as a real
        // application does. JitPack would need a paid plan for a private repository; GitHub
        // Packages only needs a free token with read:packages.
        //
        // Put these in ~/.gradle/gradle.properties, never in the repository:
        //     aryGithubUser=<github username>
        //     aryGithubToken=<token with read:packages>
        maven {
            url = uri("https://maven.pkg.github.com/arysoftware/ary-push-sdk")
            credentials {
                username = providers.gradleProperty("aryGithubUser").orNull
                    ?: System.getenv("ARY_GITHUB_USER")
                password = providers.gradleProperty("aryGithubToken").orNull
                    ?: System.getenv("ARY_GITHUB_TOKEN")
            }
        }
    }
}

rootProject.name = "ary-push-android"

include(":sdk")
include(":sample-basic")
include(":sample-existing-firebase")

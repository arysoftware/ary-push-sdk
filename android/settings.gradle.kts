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

        // The samples consume the published SDK, exactly as a real application does.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "ary-push-android"

include(":sdk")
include(":sample-basic")
include(":sample-existing-firebase")

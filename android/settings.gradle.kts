pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    // The local staging output of:
    //     ./gradlew :sdk:publishReleasePublicationToLocalStagingRepository
    val localStaging = rootDir.resolve("build/local-maven")

    repositories {
        google()
        mavenCentral()

        // JitPack. No credentials: the repository is public, and JitPack builds public
        // repositories for free, on demand, from a git tag. There is nothing to publish and
        // nothing to authenticate.
        //
        // GitHub Packages is deliberately not used. It requires a personal access token from
        // every consumer even when the repository is public, which is the one thing this SDK
        // should not ask of an application that embeds it.
        maven {
            name = "jitpack"
            url = uri("https://jitpack.io")
        }

        // Listed last so a real published artifact always wins, and declared only when it
        // exists, so it never masks a genuine "not published yet" failure with a stale local
        // copy. This is what makes the samples buildable while working on the SDK itself, with
        // no project(":sdk") substitution that would skip POM generation and AAR packaging —
        // the samples resolve a real artifact, exactly as an application does.
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

// Flutter plugin module for the ARY Push SDK.
//
// A bridge, not an implementation: it depends on the Android SDK artifact and forwards to it.
// There is no notification handling here.
//
// The AGP and Kotlin versions below match what `flutter create` currently generates, so the
// plugin builds alongside a stock Flutter application rather than fighting it over versions.

buildscript {
    // Defaults match what `flutter create` generates on Flutter 3.44. An application on an
    // older Flutter, whose Android build uses an earlier AGP, can override both from its
    // android/gradle.properties instead of forking this plugin:
    //
    //     aryPushAgpVersion=8.7.3
    //     aryPushKotlinVersion=2.1.0
    //
    val agpVersion = (project.findProperty("aryPushAgpVersion") as String?) ?: "9.0.1"
    val kotlinVersion = (project.findProperty("aryPushKotlinVersion") as String?) ?: "2.3.20"

    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:$agpVersion")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
    }
}

plugins {
    id("com.android.library")
}

group = "com.ary.push.flutter"
version = "1.0.0"

// Resolved once, against this module's own directory. Inside `allprojects` a relative path
// would resolve against whichever project is being configured, which is not this one.
val aryMavenUrl: String? =
    providers.gradleProperty("aryMavenUrl").orNull ?: System.getenv("ARY_MAVEN_URL")
val aryMavenUser: String? =
    providers.gradleProperty("aryMavenUser").orNull ?: System.getenv("ARY_MAVEN_USER")
val aryMavenPassword: String? =
    providers.gradleProperty("aryMavenPassword").orNull
        ?: System.getenv("ARY_MAVEN_PASSWORD")

// GitHub Packages, the default host for the published SDK. A consuming application sets two
// properties in android/gradle.properties (or the matching environment variables) and needs
// nothing else:
//
//     aryGithubUser=<github username>
//     aryGithubToken=<a token with read:packages>
//
// GitHub Packages requires authentication even for packages you can already see, which is why
// a token is needed here where a public Maven repository would need none.
val aryGithubUser: String? =
    providers.gradleProperty("aryGithubUser").orNull ?: System.getenv("ARY_GITHUB_USER")
val aryGithubToken: String? =
    providers.gradleProperty("aryGithubToken").orNull ?: System.getenv("ARY_GITHUB_TOKEN")
val aryGithubOwner: String =
    providers.gradleProperty("aryGithubOwner").getOrElse("arysoftware")
val aryGithubRepo: String =
    providers.gradleProperty("aryGithubRepo").getOrElse("ary-push-sdk")

// Monorepo development: the SDK published locally by scripts/dev_publish_local.sh. Absent in a
// consuming application, which resolves the artifact from a remote repository instead.
val aryJitpackToken: String? =
    providers.gradleProperty("aryJitpackToken").orNull ?: System.getenv("ARY_JITPACK_TOKEN")

val aryPushLocalRepo = file("../../android/build/local-maven")

val localSdkProject = rootProject.findProject(":ary-push-sdk")

// Where the native SDK comes from, in priority order:
//
//   1. A Gradle project at `:ary-push-sdk`, when the host build includes the SDK from a local
//      checkout or a git submodule. No credentials, works with a private repository.
//   2. Otherwise the published artifact, by default from GitHub Packages.
//
// Override the coordinate from android/gradle.properties, for example to use JitPack:
//
//     arySdkCoordinate=com.github.arysoftware:ary-push-sdk:v1.0.0
//
val arySdkCoordinate: String = providers.gradleProperty("arySdkCoordinate")
    .getOrElse("com.ary:ary-push:1.0.0")

// Declared on every project in the build, not just this one.
//
// Gradle resolves a dependency graph using the repositories of the project that owns the
// resolution -- the application module -- not those of the module that declared the dependency.
// A repository declared only in this file would never be consulted for the SDK coordinate, and
// the failure is an opaque "Could not find" that lists every repository except the one that has
// it. Reaching into rootProject is the only way a plugin can carry its own native dependency,
// and it is skipped entirely when the SDK is already present as a local project.
if (localSdkProject == null) {
    rootProject.allprojects {
        repositories {
            if (!aryGithubToken.isNullOrBlank()) {
                maven {
                    name = "aryGithubPackages"
                    url = uri("https://maven.pkg.github.com/$aryGithubOwner/$aryGithubRepo")
                    credentials {
                        username = aryGithubUser
                        password = aryGithubToken
                    }
                }
            }

            // JitPack, for teams that prefer it. Free only for public repositories.
            if (!aryJitpackToken.isNullOrBlank() || arySdkCoordinate.startsWith("com.github.")) {
                maven {
                    name = "aryJitpack"
                    url = uri("https://jitpack.io")
                    if (!aryJitpackToken.isNullOrBlank()) {
                        credentials { username = aryJitpackToken }
                    }
                }
            }

            // A self-hosted Maven repository.
            if (!aryMavenUrl.isNullOrBlank()) {
                maven {
                    url = uri(aryMavenUrl)
                    credentials {
                        username = aryMavenUser
                        password = aryMavenPassword
                    }
                }
            }

            // Monorepo development: the SDK published by scripts/dev_publish_local.sh.
            if (aryPushLocalRepo.isDirectory) {
                maven { url = uri(aryPushLocalRepo) }
            }
        }
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

android {
    namespace = "com.ary.push.flutter"

    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
    }

    defaultConfig {
        // Flutter's own floor. The native SDK supports API 21, so a native Android application
        // consuming com.ary:ary-push directly is not constrained by this number.
        minSdk = 24
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

// How the native SDK is resolved, in priority order:
//
//  1. A Gradle project at `:ary-push-sdk`, when the host build includes the SDK from a local
//     path. This is the monorepo setup and needs no publishing step at all: edit Kotlin under
//     android/sdk and the next build picks it up.
//  2. The Maven artifact `com.ary:ary-push`, once the SDK is published to ARY's private
//     repository. This is what a real consuming application will use.
dependencies {
    if (localSdkProject != null) {
        logger.info("ARY Push SDK: building against the local SDK at ${localSdkProject.projectDir}")
        implementation(localSdkProject)
    } else {
        implementation(arySdkCoordinate)
    }
}

// The failure without credentials is an opaque "Could not find" that lists every repository
// except the one holding the artifact. Say what to do before that happens.
if (localSdkProject == null &&
    aryGithubToken.isNullOrBlank() &&
    aryJitpackToken.isNullOrBlank() &&
    aryMavenUrl.isNullOrBlank() &&
    !aryPushLocalRepo.isDirectory
) {
    logger.lifecycle(
        """

        ARY Push SDK: no credentials configured for $arySdkCoordinate.

        A private repository always needs authentication somewhere. Pick one:

          1. GitHub Packages. In android/gradle.properties:

                 aryGithubUser=<github username>
                 aryGithubToken=<token with read:packages>

          2. A git submodule, which needs no package token at all. In
             android/settings.gradle.kts:

                 include(":ary-push-sdk")
                 project(":ary-push-sdk").projectDir =
                     file("../ary-push-sdk/android/sdk")

          3. JitPack, which is a paid feature for private repositories:

                 arySdkCoordinate=com.github.arysoftware:ary-push-sdk:v1.0.0
                 aryJitpackToken=<token from jitpack.io/private>

        See docs/INTEGRATION.md.

        """.trimIndent()
    )
}

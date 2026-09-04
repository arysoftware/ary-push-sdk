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
val aryPushLocalRepo = file("../../android/build/local-maven")

allprojects {
    repositories {
        google()
        mavenCentral()

        // JitPack builds the SDK straight from its GitHub tag, so a consuming application needs
        // no publishing step and no repository declaration of its own. Declared here, in the
        // plugin, which is the whole point: the app only edits pubspec.yaml.
        maven {
            name = "jitpack"
            url = uri("https://jitpack.io")
            // A private repository needs a JitPack auth token. Public ones need nothing.
            val jitpackToken = providers.gradleProperty("aryJitpackToken").orNull
                ?: System.getenv("ARY_JITPACK_TOKEN")
            if (!jitpackToken.isNullOrBlank()) {
                credentials { username = jitpackToken }
            }
        }

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

        // A self-hosted Maven repository, for teams not using GitHub Packages. Supplied through
        // a property or environment variable; never hardcoded, never committed with credentials.
        if (!aryMavenUrl.isNullOrBlank()) {
            maven {
                url = uri(aryMavenUrl)
                credentials {
                    username = aryMavenUser
                    password = aryMavenPassword
                }
            }
        }

        if (aryPushLocalRepo.isDirectory) {
            maven { url = uri(aryPushLocalRepo) }
        }
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
// Where the native SDK comes from, in priority order:
//
//   1. A Gradle project at `:ary-push-sdk`, when the host build includes the SDK from a local
//      path. Used while developing the SDK itself.
//   2. Otherwise the published artifact. The default coordinate is JitPack's, which is what
//      makes a Flutter integration a single pubspec entry: JitPack builds the AAR from the
//      GitHub tag, and the repository below is declared here rather than in every application.
//
// Override the coordinate from android/gradle.properties to use GitHub Packages or a
// self-hosted repository instead:
//
//     arySdkCoordinate=com.ary:ary-push:1.0.0
//
val arySdkCoordinate: String = providers.gradleProperty("arySdkCoordinate")
    .getOrElse("com.github.$aryGithubOwner:$aryGithubRepo:1.0.0")

val localSdkProject = rootProject.findProject(":ary-push-sdk")

dependencies {
    if (localSdkProject != null) {
        logger.info("ARY Push SDK: building against the local SDK at ${localSdkProject.projectDir}")
        implementation(localSdkProject)
    } else {
        implementation(arySdkCoordinate)
    }
}

// A private repository needs a JitPack auth token, and the failure without one is an opaque
// "Could not find". Say so up front rather than after the fact.
if (localSdkProject == null &&
    arySdkCoordinate.startsWith("com.github.") &&
    providers.gradleProperty("aryJitpackToken").orNull.isNullOrBlank() &&
    System.getenv("ARY_JITPACK_TOKEN").isNullOrBlank()
) {
    logger.info(
        """
        ARY Push SDK: resolving $arySdkCoordinate from JitPack with no auth token.
        That is correct for a public repository. If ary-push-sdk is private, add to
        android/gradle.properties:

            aryJitpackToken=<token from jitpack.io/private>

        Alternatives, if you would rather not use JitPack:

            arySdkCoordinate=com.ary:ary-push:1.0.0
            aryGithubUser=<username>
            aryGithubToken=<token with read:packages>

        or include the SDK from a local checkout in android/settings.gradle.kts:

            include(":ary-push-sdk")
            project(":ary-push-sdk").projectDir = file("../ary-push-sdk/android/sdk")

        See docs/INTEGRATION.md.
        """.trimIndent()
    )
}

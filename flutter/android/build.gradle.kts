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

// Monorepo development: the SDK published locally by scripts/dev_publish_local.sh. Absent in a
// consuming application, which resolves the artifact from the private repository instead.
val aryPushLocalRepo = file("../../android/build/local-maven")

allprojects {
    repositories {
        google()
        mavenCentral()

        // ARY's private Maven repository, where com.ary:ary-push lives.
        // Supplied by the host application through a Gradle property or an environment
        // variable; never hardcoded, and never committed with credentials.
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
val localSdkProject = rootProject.findProject(":ary-push-sdk")

dependencies {
    if (localSdkProject != null) {
        logger.info("ARY Push SDK: building against the local SDK at ${localSdkProject.projectDir}")
        implementation(localSdkProject)
    } else {
        implementation("com.ary:ary-push:1.0.0")
    }
}

// Without either source, resolution fails with a bare "Could not find com.ary:ary-push", which
// says nothing about how to fix it. Say it here, before the failure happens.
if (localSdkProject == null && aryMavenUrl.isNullOrBlank() && !aryPushLocalRepo.isDirectory) {
    logger.lifecycle(
        """

        ARY Push SDK: the native SDK could not be located.

          Building from a local checkout? Add two lines to your app's
          android/settings.gradle.kts:

              include(":ary-push-sdk")
              project(":ary-push-sdk").projectDir = file("/path/to/ary-push-sdk/android/sdk")

          Consuming a published artifact? Point Gradle at the private repository in
          android/gradle.properties:

              aryMavenUrl=https://maven.ary.internal/releases

        """.trimIndent()
    )
}

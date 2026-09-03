pluginManagement {
    val flutterSdkPath =
        run {
            val properties = java.util.Properties()
            file("local.properties").inputStream().use { properties.load(it) }
            val flutterSdkPath = properties.getProperty("flutter.sdk")
            require(flutterSdkPath != null) { "flutter.sdk not set in local.properties" }
            flutterSdkPath
        }

    includeBuild("$flutterSdkPath/packages/flutter_tools/gradle")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("dev.flutter.flutter-plugin-loader") version "1.0.0"
    id("com.android.application") version "9.0.1" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
}

// The native ARY Push SDK, resolved straight from this repository. There is no publishing step:
// edit Kotlin under android/sdk and the next build picks it up.
//
// A real consuming application deletes these two lines and resolves com.ary:ary-push from ARY's
// private Maven repository instead. The plugin supports both and prefers whichever is present.
include(":ary-push-sdk")
project(":ary-push-sdk").projectDir = file("../../../android/sdk")

include(":app")

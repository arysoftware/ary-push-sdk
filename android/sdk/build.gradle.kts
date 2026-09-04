// Plugins are requested WITHOUT a version on purpose.
//
// This module is built in two very different contexts:
//
//   * standalone, from android/, where the root build.gradle.kts puts the Android and Kotlin
//     plugins on the classpath with the versions from gradle/libs.versions.toml;
//   * included by a Flutter application, whose own settings.gradle.kts has already put AGP and
//     Kotlin on the classpath at whatever versions that application uses.
//
// In the second case Gradle reports the classpath version as "unknown" and refuses any request
// that names a version, so naming one here would make the SDK impossible to consume from a
// local path. Requesting without a version works in both.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}

val sdkGroup: String = providers.gradleProperty("aryPush.group").getOrElse("com.ary")
val sdkArtifact: String = providers.gradleProperty("aryPush.artifact").getOrElse("ary-push")
val sdkVersion: String = providers.gradleProperty("aryPush.version").getOrElse("1.0.0")

android {
    namespace = "com.ary.push"
    compileSdk = 36

    defaultConfig {
        // Android 5.0. Deliberately low: this SDK is embedded in consumer applications whose
        // users are not all on recent hardware.
        minSdk = 21

        consumerProguardFiles("consumer-rules.pro")

        // Exposed to the SDK at runtime so the version is never duplicated in source.
        buildConfigField("String", "SDK_VERSION", "\"$sdkVersion\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        disable += "GradleDependency"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }

    // The public surface of a library should be intentional, not accidental: every public
    // declaration must state its visibility and its return type.
    explicitApi()
}

// Dependency versions are literal here rather than read from a version catalog, on purpose.
//
// This module is meant to be dropped into someone else's build as a local Gradle module, and a
// host application very often already has its own `libs` catalog. Requiring a second one named
// `libs` would collide with theirs and make integration a merge exercise. Literal coordinates
// keep the module self-contained: `include` it and it builds.
//
// These versions are the compatibility matrix in docs/ARCHITECTURE.md. Change them together.
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.annotation:annotation:1.8.0")

    // Optional automatic initialization. Host applications that disable it still link fine.
    implementation("androidx.startup:startup-runtime:1.1.1")

    // Firebase Cloud Messaging. Declared as `implementation` so the host application's
    // dependency graph is not forced; a host that pins a Firebase BoM wins by normal Gradle
    // version resolution. See docs/FIREBASE.md.
    implementation("com.google.firebase:firebase-messaging:24.0.0")

    // HTTP transport for the default RestClient implementation only.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = sdkGroup
            artifactId = sdkArtifact
            version = sdkVersion

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set("ARY Push SDK")
                description.set("Private ARY-owned push notification SDK for Android.")
                licenses {
                    license {
                        name.set("Proprietary")
                        distribution.set("repo")
                    }
                }
            }
        }
    }

    repositories {
        // There is no GitHub Packages repository here, and no credentials anywhere in this
        // build. Applications get the SDK from JitPack, which builds this public repository on
        // demand from a git tag, so a release is a tag and nothing is pushed to a Maven host.
        // GitHub Packages would undo that: it demands a personal access token from every
        // consumer even when the repository is public.

        // A self-hosted Maven repository, for teams that would rather not depend on JitPack.
        // Configured entirely from properties; never committed with credentials.
        val privateUrl = providers.gradleProperty("aryMavenUrl").orNull
        if (privateUrl != null) {
            maven {
                name = "aryPrivate"
                url = uri(privateUrl)
                credentials {
                    username = providers.gradleProperty("aryMavenUser").orNull
                    password = providers.gradleProperty("aryMavenPassword").orNull
                }
            }
        }

        // Always available, and what scripts/dev_publish_local.sh targets:
        //   ./gradlew :sdk:publishReleasePublicationToLocalStagingRepository
        maven {
            name = "localStaging"
            url = uri(rootProject.layout.buildDirectory.dir("local-maven"))
        }
    }
}

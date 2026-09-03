// Versions come from the root build, which declares these plugins with `apply false`.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The Google Services plugin is applied only when this sample has been given its own
// google-services.json. That keeps the repository free of Firebase configuration and lets CI
// build the sample without credentials, while a developer who drops the file in gets the full
// end-to-end experience.
val googleServicesFile = file("google-services.json")
if (googleServicesFile.exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.ary.push.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ary.push.sample"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // In a real host application this is the private Maven coordinate:
    //   implementation("com.ary:ary-push:1.0.0")
    implementation(project(":sdk"))

    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
}

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Windows and Android Vessel share one version, the one in CMakeLists.txt
// (project(Vessel VERSION 1.6.0 ...)): they ship together in one GitHub release, and Jeti,
// the launcher, compares the installed versionName with that release's tag.
val vesselVersion: String = Regex("""^project\(\S+\s+VERSION\s+([0-9.]+)""", RegexOption.MULTILINE)
    .find(rootProject.file("../CMakeLists.txt").readText())
    ?.groupValues?.get(1)
    ?: error("No version in CMakeLists.txt")

// Android only installs an update whose versionCode is higher, so it's built from the
// version: 1.6.0 -> 10600, 1.10.2 -> 11002.
val vesselVersionCode: Int = vesselVersion.split('.').map(String::toInt).let { (major, minor, patch) ->
    major * 10000 + minor * 100 + patch
}

// The release key's location and passwords, kept out of git (see the README). Every published
// APK must be signed with this same key, or it can't update an installed Vessel.
val keyProperties = Properties().apply {
    val file = rootProject.file("key.properties")
    if (file.exists()) file.inputStream().use(::load)
}

android {
    namespace = "com.better_video_player"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // Permanent: Android treats an APK with another applicationId as a different app.
        applicationId = "com.bersamamus.vessel"
        // 29: MediaStore's RELATIVE_PATH and loadThumbnail, which the library is built on.
        minSdk = 29
        targetSdk = 37
        versionCode = vesselVersionCode
        versionName = vesselVersion

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keyProperties.isNotEmpty()) {
            create("release") {
                storeFile = file(keyProperties.getProperty("storeFile"))
                storePassword = keyProperties.getProperty("storePassword")
                keyAlias = keyProperties.getProperty("keyAlias")
                keyPassword = keyProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // libmpv's native libraries: 64-bit ARM (phones) and x86-64 (the emulator).
            ndk {
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
        release {
            // The published APK is for phones only: x86-64 would double it to about 60 MB.
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
            // Without key.properties, the debug key: fine for trying a release build out,
            // never for publishing.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            // R8 shrinks the whole app, not only its libraries: limiting it to androidx/kotlin
            // (packageScope) made the release build crash at launch with an IllegalAccessError,
            // because classes it moved were still used from the app's own, untouched code.
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    implementation(libs.libmpv)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}


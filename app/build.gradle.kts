plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The app version, in ONE place: versionName below and the
// herder_version resource both read this val, so the version the UI
// header shows can never drift from the package version. (v0.2.8's
// resValue duplicated the literal in a second spot while its comment
// claimed the two stayed in step "by construction" — they were a
// hand-kept copy, and a bump that edited one would have desynced the
// header silently. This is the by-construction version.)
val herderVersion = "0.3.5"

android {
    namespace = "org.bin.apk.herder"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.bin.apk.herder"
        minSdk = 28
        targetSdk = 28
        versionCode = 27
        versionName = herderVersion
        // Mirror the version into a resource so the UI can show it
        // without BuildConfig (AGP 8+ does not generate BuildConfig
        // for a plain app module unless asked).
        resValue("string", "herder_version", herderVersion)
        // Herder's backend is arm64-only (and the app refuses 32-bit
        // devices), so the other three ABIs' graphics .so copies —
        // x86, x86_64, armeabi-v7a — are dead bytes in the APK.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            // Tier-1 size pass (2026-11-08, "A Byte Is a Byte"): the
            // unshrunk v0.1.8 APK was 40.9 MB, 99% of it dex from the
            // Compose tree. Herder uses no reflection-based libraries
            // (org.json and HttpURLConnection are framework), so R8
            // tree-shaking is low-risk here; the class-presence CI
            // gate and the on-device verification stand behind it.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        // Herder targets API 28 on purpose (the downloaded-binary exec
        // model) and is sideloaded, never published to Google Play, so
        // the Play targetSdk floor does not apply.
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    // v0.2.0 (the tier-3 pass): the whole UI is framework Views now, so
    // Compose, material3 and every AndroidX UI artifact are GONE. What
    // remains is core-ktx (kept only for ContextCompat.startForeground-
    // Service — one call) and kotlinx-coroutines, which the engine's
    // service and downloaders are written against. Nothing else gets in
    // without arguing its way in ("A Byte Is a Byte").
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

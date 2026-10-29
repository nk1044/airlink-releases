import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.clipsync"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.clipsync"
        minSdk = 33
        targetSdk = 36
        // CI stamps these with -PversionCode= and -PversionName=.
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "1.0"
    }

    // CI signs with a fixed key from these variables, so a new release installs over the old one.
    val keystore = System.getenv("CLIPSYNC_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = keystore
                storePassword = System.getenv("CLIPSYNC_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CLIPSYNC_KEY_ALIAS")
                keyPassword = System.getenv("CLIPSYNC_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideloaded app: without the CI key, sign release builds with the local debug key.
            signingConfig = signingConfigs.getByName(if (keystore != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// No dependencies: framework APIs only.

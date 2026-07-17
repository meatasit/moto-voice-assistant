plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.moto.voice"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.moto.voice"
        minSdk = 26
        targetSdk = 34
        versionCode = 34
        versionName = "1.3.28"
    }

    signingConfigs {
        // Committed, stable debug key so every CI build (and every dev machine) signs with
        // the SAME certificate. Without this, GitHub Actions generates a fresh ~/.android/
        // debug.keystore per run → each Release has a different signature → the phone forces
        // an uninstall+reinstall on every update, wiping app data (Azure key + Default
        // Assistant role). Standard, non-secret debug credentials (password "android").
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.okhttp)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.session)
    implementation(libs.security.crypto)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

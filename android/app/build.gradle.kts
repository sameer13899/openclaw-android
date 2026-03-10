plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
}

android {
    namespace = "com.openclaw.android"
    compileSdk = 36

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    defaultConfig {
        applicationId = "com.openclaw.android"
        minSdk = 24
        //noinspection ExpiredTargetSdkVersion
        targetSdk = 28
        versionCode = 1
        versionName = "1.0.0"

        ndk { abiFilters += listOf("arm64-v8a") }

        // Initial download URLs (§2.9) — BuildConfig hardcoded fallbacks
        buildConfigField(
            "String", "BOOTSTRAP_URL",
            "\"https://github.com/termux/termux-packages/releases/download/bootstrap-2026.02.12-r1%2Bapt.android-7/bootstrap-aarch64.zip\""
        )
        buildConfigField(
            "String", "WWW_URL",
            "\"https://github.com/AidanPark/openclaw-android-app/releases/download/v1.0.0/www.zip\""
        )
        buildConfigField(
            "String", "CONFIG_URL",
            "\"https://raw.githubusercontent.com/AidanPark/openclaw-android-app/main/config.json\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-DEBUG"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation(project(":terminal-emulator"))
    implementation(project(":terminal-view"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.gson)
    // WebView + @JavascriptInterface — Android SDK built-in, no extra dependency
}

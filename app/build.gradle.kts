import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.scifilauncher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.scifilauncher"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        signingConfig = signingConfigs.getByName("debug")
        testFunctionalTest = false
        testHandleProfiling = false

        ndk {
            // Personal device only (Galaxy A54, arm64-v8a) - no need to build/ship other ABIs.
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        viewBinding = true
        aidl = true
    }

    androidResources {
        noCompress += "onnx"
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    dependenciesInfo {
        includeInApk = true
        includeInBundle = true
    }

    buildToolsVersion = "34.0.0"
    ndkVersion = "27.1.12297006"
}

// Kotlin compiler options (Kotlin DSL way)
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.activity:activity-compose:1.9.0")

    val composeBom = platform("androidx.compose:compose-bom:2024.04.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")

    implementation("androidx.biometric:biometric:1.2.0-alpha05")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20231013")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // QR scanning for the "Scan QR" quick control - handles camera + decode in one activity,
    // no need to hand-roll CameraX + a barcode decoder.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")

    // Shizuku: lets this app run commands with real ADB/shell-level privilege (e.g. genuine
    // `am force-stop`) without root - only after the user explicitly activates the separate
    // Shizuku app and grants this app permission through it. Nothing this unlocks works
    // silently or without that setup.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Offline speaker verification: ECAPA-TDNN speaker embedding model (Wespeaker project,
    // app/src/main/assets/ecapa_tdnn_speaker.onnx) - replaced the earlier FRILL/TFLite attempt,
    // which was a general-purpose embedding, not built for speaker verification, and showed
    // real same-speaker inconsistency in on-device testing.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
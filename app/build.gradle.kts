import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.KeyStore
import java.security.MessageDigest

/** SHA-256 of the debug keystore's signing certificate, hex-encoded lowercase, no separators -
 * matches the format AppIntegrityCheck.kt computes at runtime from the installed APK. Reads the
 * keystore directly via the JVM's own KeyStore API rather than shelling out to `keytool` and
 * parsing its human-readable output, since that format isn't a stable contract. Returns an
 * empty string (not a crash) if the keystore doesn't exist yet at configuration time - a fresh
 * checkout's first build - so the runtime check treats "couldn't compute at build time" as
 * "don't false-positive" rather than failing the build. */
fun debugCertSha256(): String {
    val keystoreFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
    if (!keystoreFile.exists()) return ""
    return runCatching {
        val ks = KeyStore.getInstance("JKS")
        keystoreFile.inputStream().use { ks.load(it, "android".toCharArray()) }
        val cert = ks.getCertificate("androiddebugkey") ?: return ""
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
    }.getOrDefault("")
}

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

        // Baked in at build time so AppIntegrityCheck.kt can compare it against the real
        // running APK's signing cert to catch a repackaged/resigned copy. Computed dynamically
        // from whatever key actually signs this build (both debug and release currently use
        // the debug signingConfig above - there's no separate release keystore yet) rather than
        // hardcoded, specifically because this exact debug key was found to be regenerable/
        // machine-local during the 2026-07-31 signing-mismatch incident (see experience.md) -
        // a hardcoded value would have gone stale the moment that happened.
        buildConfigField("String", "EXPECTED_SIGNING_CERT_SHA256", "\"${debugCertSha256()}\"")

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
        buildConfig = true
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
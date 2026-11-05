plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "il.calltranscriber"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "il.calltranscriber"
        minSdk = 34          // Galaxy S24 ships with Android 14
        targetSdk = 35
        versionCode = 4
        versionName = "0.1.3"

        ndk { abiFilters += "arm64-v8a" }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Release signing key: created once by the GitHub Actions workflow and kept in
    // the repo's private Actions cache, never in git. Every CI build then carries
    // the same signature, so a new APK installs over the old one (keeping the
    // downloaded model). Without those variables (e.g. a local build) the build
    // falls back to the default debug key.
    val ciKeystore: String? = System.getenv("CT_KEYSTORE_FILE")
    signingConfigs {
        if (ciKeystore != null) {
            create("personal") {
                storeFile = file(ciKeystore)
                storePassword = System.getenv("CT_KEYSTORE_PASSWORD")
                keyAlias = "calltranscriber"
                keyPassword = System.getenv("CT_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("personal") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    lint { abortOnError = false }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}

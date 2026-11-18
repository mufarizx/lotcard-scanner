plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Nomor versi naik otomatis tiap build di GitHub Actions (supaya APK baru
// bisa dipasang menimpa yang lama).
val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.sanwa.lotcard.scanner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sanwa.lotcard.scanner"
        minSdk = 26
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    // Kunci tanda tangan APK. Default: signing/lotcard-scanner.jks di repo
    // (private). Bisa diganti lewat GitHub Secrets (lihat README).
    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("LOTCARD_KEYSTORE_FILE") ?: "../signing/lotcard-scanner.jks")
            storePassword = System.getenv("LOTCARD_KEYSTORE_PASSWORD") ?: "lotcard-sanwa"
            keyAlias = System.getenv("LOTCARD_KEY_ALIAS") ?: "lotcard"
            keyPassword = System.getenv("LOTCARD_KEY_PASSWORD") ?: "lotcard-sanwa"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
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
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

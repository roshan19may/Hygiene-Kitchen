plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.kitchenlens.app"
    // Google Play requires target API 36 (Android 16) for new apps and updates from 31 Aug 2026
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kitchenlens.app"
        minSdk = 26
        targetSdk = 36
        // Every Play upload needs a higher versionCode; GitHub passes its run number.
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "1.0.0"

        // The address the QR code points to; the order code is added at the end.
        // GitHub builds pass -PscanBaseUrl=... automatically (see .github/workflows/android.yml).
        // For production use your Firebase Hosting address, e.g. https://your-project.web.app/o/
        val scanBaseUrl = (project.findProperty("scanBaseUrl") as String?)
            ?.takeIf { it.isNotBlank() } ?: "https://YOUR-PROJECT-ID.web.app/o/"
        buildConfigField("String", "SCAN_BASE_URL", "\"$scanBaseUrl\"")
        // Shown in the app for help and account-deletion requests.
        val supportEmail = (project.findProperty("supportEmail") as String?)?.takeIf { it.isNotBlank() } ?: "support@example.com"
        buildConfigField("String", "SUPPORT_EMAIL", "\"$supportEmail\"")
        // Where privacy.html and delete-account.html are published (same site as the scan page)
        val webBaseUrl = (project.findProperty("webBaseUrl") as String?)?.takeIf { it.isNotBlank() } ?: "https://YOUR-PROJECT-ID.web.app/"
        buildConfigField("String", "WEB_BASE_URL", "\"$webBaseUrl\"")
    }

    // Release signing for Google Play. Values come from GitHub secrets (see README,
    // "Publish on Google Play"); without them the release build is left unsigned.
    val keystoreFile = System.getenv("KL_KEYSTORE_FILE")?.let { file(it) }
    signingConfigs {
        if (keystoreFile != null && keystoreFile.exists()) {
            create("release") {
                storeFile = keystoreFile
                storePassword = System.getenv("KL_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KL_KEY_ALIAS")
                keyPassword = System.getenv("KL_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Jetpack Compose UI
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // In-app camera (photos and short clips; no gallery uploads)
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    // CameraX hands back a ListenableFuture; these make that type available and add .await()
    implementation("androidx.concurrent:concurrent-futures-ktx:1.2.0")
    implementation("com.google.guava:guava:33.3.1-android")

    // Firebase: login, database, file storage (hosted in Mumbai region)
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-storage")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // On-device AI: reads dates from supplier bills
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // QR code generation
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
}

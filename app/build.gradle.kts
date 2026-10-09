plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22"
}

import java.io.ByteArrayOutputStream

val gitCommitCount: Int = try {
    ByteArrayOutputStream().use { baos ->
        project.exec {
            commandLine("git", "-C", project.rootDir.parent, "rev-list", "--count", "HEAD")
            standardOutput = baos
        }
        baos.toString().trim().toInt()
    }
} catch (_: Exception) { 1 }

val gitCommitHash: String = try {
    ByteArrayOutputStream().use { baos ->
        project.exec {
            commandLine("git", "-C", project.rootDir.parent, "rev-parse", "--short", "HEAD")
            standardOutput = baos
        }
        baos.toString().trim()
    }
} catch (_: Exception) { "unknown" }

// ── Release signing (CI-provided env; local builds stay unsigned) ──
val ksFile = System.getenv("ANDROID_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
val ksStorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: ""
val ksKeyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: "sutliff"
val ksKeyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: ksStorePassword
val hasKeystore = ksFile != null && File(ksFile).exists()

android {
    namespace = "com.sutliff.pocket"
    compileSdk = 34

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = File(ksFile!!)
                storePassword = ksStorePassword
                keyAlias = ksKeyAlias
                keyPassword = ksKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.sutliff.pocket"
        minSdk = 26
        targetSdk = 34
        versionCode = gitCommitCount
        versionName = "1.0.$gitCommitCount-$gitCommitHash"

        buildConfigField("String", "TELEMETRY_URL", "\"${project.findProperty("telemetryUrl") ?: "https://telemetry.ffilt.us/"}\"")
        buildConfigField("String", "GIT_HASH", "\"$gitCommitHash\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Lifecycle & Work
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}

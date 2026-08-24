import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val stableDevSigningDir = rootProject.file("dev-signing")
val stableDevKeyBase64 = rootProject.file("dev-signing/offline-transfer-dev.keystore.b64")
val stableDevKeystore = rootProject.file("dev-signing/offline-transfer-dev.keystore")

if (!stableDevKeystore.exists() && stableDevKeyBase64.exists()) {
    stableDevSigningDir.mkdirs()
    stableDevKeystore.writeBytes(
        Base64.getDecoder().decode(stableDevKeyBase64.readText().trim()),
    )
}

android {
    namespace = "com.luisforlo.offlinetransfer"
    compileSdk = 37

    signingConfigs {
        create("stableDev") {
            storeFile = stableDevKeystore
            storePassword = "offline-transfer-dev"
            keyAlias = "offline-transfer-dev"
            keyPassword = "offline-transfer-dev"
        }
    }

    defaultConfig {
        applicationId = "com.luisforlo.offlinetransfer"
        minSdk = 26
        targetSdk = 37
        versionCode = 9
        versionName = "0.5.0-dev"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("stableDev")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.camera:camera-core:1.6.1")
    implementation("androidx.camera:camera-camera2:1.6.1")
    implementation("androidx.camera:camera-lifecycle:1.6.1")
    implementation("androidx.camera:camera-view:1.6.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
}

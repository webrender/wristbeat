plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "wristbeat.wear"
    compileSdk = 35
    defaultConfig {
        applicationId = "wristbeat.wear"
        minSdk = 30
        targetSdk = 35
        // CI numbers each build so every release installs as an upgrade over the last.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toInt() ?: 1
        versionName = "0.1"
    }
    signingConfigs {
        // CI signs with one stable key (DEBUG_KEYSTORE_BASE64 secret) so a newer release can be
        // installed over an older one; local builds keep the machine's default debug key.
        getByName("debug") {
            System.getenv("WRISTBEAT_DEBUG_KEYSTORE")?.let { storeFile = file(it) }
        }
    }
    buildTypes {
        // Non-debuggable and R8-optimized, which matters for Compose frame times. Signed with the
        // debug key so it sideloads like the debug build; there's no store key yet.
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":composeApp"))
    implementation(compose.runtime)
    implementation("androidx.activity:activity-compose:1.9.3")
    // Wear Compose 1.4 is built on Compose 1.7, matching Compose Multiplatform 1.7.x.
    implementation("androidx.wear.compose:compose-material:1.4.1")
    implementation("androidx.wear.compose:compose-foundation:1.4.1")
    implementation("androidx.wear.compose:compose-navigation:1.4.1")
}

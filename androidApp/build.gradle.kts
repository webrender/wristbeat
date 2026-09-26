plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "wristbeat.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "wristbeat.android"
        minSdk = 26
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
    implementation("androidx.core:core-ktx:1.13.1")
}

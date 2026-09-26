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
        versionCode = 1
        versionName = "0.1"
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
}

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

@OptIn(ExperimentalWasmDsl::class)
kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "composeApp.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
    }
}

// Android target + Wear OS source set land in a later iteration, once the web build is dialed in:
// androidTarget() with Oboe-backed AudioEngine/AudioClock actuals and a Vibrator/VibrationEffect-backed
// HapticEngine actual, per HANDOFF.md's expect/actual plan.

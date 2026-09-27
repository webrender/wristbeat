package wristbeat.app

private const val KEY_PREFIX = "wristbeat.calibration."

// Browsers don't say which device the AudioContext is playing through (not without a microphone
// permission prompt), so the web keeps one offset.
internal actual fun currentAudioOutput(): AudioOutput = AudioOutput("default", null)

internal actual fun loadCalibrationMs(outputId: String): Double? =
    jsStorageGet(KEY_PREFIX + outputId)?.toDoubleOrNull()

internal actual fun saveCalibrationMs(outputId: String, offsetMs: Double) {
    jsStorageSet(KEY_PREFIX + outputId, offsetMs.toString())
}

// localStorage can throw (private windows, blocked site data); calibration then just isn't saved.
private fun jsStorageGet(key: String): String? = js("{ try { return window.localStorage.getItem(key); } catch (e) { return null; } }")

private fun jsStorageSet(key: String, value: String): Unit = js("{ try { window.localStorage.setItem(key, value); } catch (e) {} }")

package wristbeat.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * The audio output a calibration belongs to. [id] keys the saved offset; [label] names it for the
 * player ("Pixel Buds", "Phone speaker"), and is null where the platform can't tell outputs apart
 * (the web, which keeps a single offset).
 */
data class AudioOutput(val id: String, val label: String?)

/** Which output sound is going to right now. */
internal expect fun currentAudioOutput(): AudioOutput

/** The offset saved for output [outputId], or null if it has never been calibrated. */
internal expect fun loadCalibrationMs(outputId: String): Double?

internal expect fun saveCalibrationMs(outputId: String, offsetMs: Double)

/**
 * Calibrate's measured offset, saved per audio output. A Bluetooth headset can add a couple of
 * hundred milliseconds of latency that the platform's output timestamps don't always report, so
 * each output keeps its own offset, and switching outputs (e.g. connecting the headset) switches
 * to that output's offset automatically.
 */
@Stable
class Calibration internal constructor() {
    var output: AudioOutput by mutableStateOf(currentAudioOutput())
        private set

    /** The saved offset for [output], or null if it hasn't been calibrated yet. */
    var offsetMs: Double? by mutableStateOf(loadCalibrationMs(output.id))
        private set

    /** The offset stages judge with: [offsetMs], or 0 when uncalibrated. */
    val inputOffsetMs: Double get() = offsetMs ?: 0.0

    fun record(offsetMs: Double) {
        refreshOutput()
        this.offsetMs = offsetMs
        saveCalibrationMs(output.id, offsetMs)
    }

    /** Re-checks the current output, switching to its saved offset if it changed. */
    fun refreshOutput() {
        val current = currentAudioOutput()
        if (current != output) {
            output = current
            offsetMs = loadCalibrationMs(current.id)
        }
    }

    /** One line of HUD copy, e.g. "Calibrated for Pixel Buds: +212ms" or "Not calibrated". */
    fun statusLine(): String {
        val forOutput = output.label?.let { " for $it" } ?: ""
        return offsetMs?.let { "Calibrated$forOutput: ${formatMs(it)}" } ?: "Not calibrated$forOutput"
    }
}

/**
 * The app-wide [Calibration], polling the audio output once a second so plugging in or connecting
 * headphones picks up their offset without a restart.
 */
@Composable
fun rememberCalibration(): Calibration {
    val calibration = remember { Calibration() }
    LaunchedEffect(calibration) {
        while (true) {
            delay(1000)
            calibration.refreshOutput()
        }
    }
    return calibration
}

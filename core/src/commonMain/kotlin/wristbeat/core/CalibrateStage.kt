package wristbeat.core

import kotlin.math.round

const val CALIBRATE_TOTAL_BEATS = 20
const val CALIBRATE_COUNT_IN_BEATS = 4
const val CALIBRATE_MIN_TAPS = 6

data class CalibrateResult(val ok: Boolean, val offsetMs: Double, val tapCount: Int)

/**
 * Tracks taps against a click track and derives an input offset.
 * No audio or haptics on tap here by design: playing a sound for the player's own tap
 * masked the click track and made calibration harder to judge (prototype issue).
 */
class CalibrateStage(
    private val totalBeats: Int = CALIBRATE_TOTAL_BEATS,
    private val countInBeats: Int = CALIBRATE_COUNT_IN_BEATS,
) {
    private val errorsMs = mutableListOf<Double>()

    val chart: List<ChartEvent> = Charts.calibrateClickTrack(totalBeats)
    val tapCount: Int get() = errorsMs.size

    /** Records a tap at the given fractional beat position. Returns the signed error in ms if counted, else null. */
    fun recordTap(beat: Double): Double? {
        val nearest = round(beat).toInt()
        if (nearest < countInBeats || nearest >= totalBeats) return null
        val errorMs = (beat - nearest) * SECONDS_PER_BEAT * 1000.0
        errorsMs.add(errorMs)
        return errorMs
    }

    fun finish(): CalibrateResult {
        val ok = errorsMs.size >= CALIBRATE_MIN_TAPS
        val offset = if (ok) median(errorsMs) else 0.0
        return CalibrateResult(ok, offset, errorsMs.size)
    }
}

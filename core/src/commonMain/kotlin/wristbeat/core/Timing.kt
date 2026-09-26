package wristbeat.core

const val BPM = 116.0
const val SECONDS_PER_BEAT = 60.0 / BPM

/** Mango Chop runs faster than the shared 116 BPM tempo that Calibrate and Snap Crabs use. */
const val MANGO_CHOP_BPM = 132.0

/** Converts an audio-clock time (seconds) into a fractional beat position, given the song's start time [t0Seconds]. */
fun beatAt(audibleTimeSeconds: Double, t0Seconds: Double): Double =
    (audibleTimeSeconds - t0Seconds) / SECONDS_PER_BEAT

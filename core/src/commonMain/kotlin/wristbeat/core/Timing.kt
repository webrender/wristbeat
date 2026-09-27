package wristbeat.core

const val BPM = 116.0
const val SECONDS_PER_BEAT = 60.0 / BPM

/** Mango Chop runs faster than the shared 116 BPM tempo that Calibrate and Snap Crabs use. */
const val MANGO_CHOP_BPM = 140.0

/** Bongo Blitz outruns even Mango Chop, matching its jungle drum-corps intensity. */
const val BONGO_BLITZ_BPM = 172.0

/**
 * Remix 1 runs its one song at a single tempo through all three of its borrowed stages: quicker
 * than Snap Crabs, a touch slower than Mango Chop, and well below Bongo Blitz.
 */
const val REMIX_1_BPM = 128.0

/** Converts an audio-clock time (seconds) into a fractional beat position, given the song's start time [t0Seconds]. */
fun beatAt(audibleTimeSeconds: Double, t0Seconds: Double): Double =
    (audibleTimeSeconds - t0Seconds) / SECONDS_PER_BEAT

package wristbeat.app

import wristbeat.core.SoundId

/** Schedules a chart's sounds against the same clock exposed by [AudioClock]. */
expect class AudioEngine() {
    /** [param] is sound-specific (e.g. a MIDI note for BASS_*); most sounds ignore it. */
    fun play(sound: SoundId, atSeconds: Double, param: Double = 0.0)
}

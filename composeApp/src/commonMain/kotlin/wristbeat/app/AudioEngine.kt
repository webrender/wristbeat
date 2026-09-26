package wristbeat.app

import wristbeat.core.SoundId

/** Schedules a chart's sounds against the same clock exposed by [AudioClock]. */
expect class AudioEngine() {
    fun play(sound: SoundId, atSeconds: Double)
}

package wristbeat.app

import android.os.SystemClock

/** Wraps [AndroidAudio]'s output stream: [now] is the stream time of the sample reaching the speaker. */
actual class AudioClock actual constructor() {
    actual fun start() {
        AndroidAudio.ensureStarted()
    }

    actual fun now(): Double = AndroidAudio.now()

    // Android input events are stamped in SystemClock.uptimeMillis().
    actual fun timeAtInputEvent(eventUptimeMillis: Long): Double =
        backdate(now(), (SystemClock.uptimeMillis() - eventUptimeMillis).toDouble())
}

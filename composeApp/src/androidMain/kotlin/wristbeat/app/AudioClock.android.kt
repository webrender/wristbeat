package wristbeat.app

import android.os.SystemClock

// Shared origin so every AudioClock instance agrees, like the single shared AudioContext on web.
private val originNanos = SystemClock.elapsedRealtimeNanos()

/**
 * Placeholder clock: a monotonic system clock, not the audio output clock. Swap for Oboe/AAudio's
 * stream timestamp alongside the real AudioEngine, per HANDOFF's timing-accuracy requirement.
 */
actual class AudioClock actual constructor() {
    actual fun start() {}

    actual fun now(): Double = (SystemClock.elapsedRealtimeNanos() - originNanos) / 1e9

    // Android input events are stamped in SystemClock.uptimeMillis().
    actual fun audibleTimeForInputEvent(eventTimestampMs: Double): Double =
        now() - (SystemClock.uptimeMillis() - eventTimestampMs) / 1000.0
}

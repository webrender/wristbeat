package wristbeat.app

/**
 * Wraps the platform's audio output clock. On web this is Web Audio's AudioContext;
 * on Android/Wear (added later) it will be Oboe/AAudio's stream timestamp.
 */
expect class AudioClock() {
    /** Starts (or resumes) the underlying audio output. Must be called from a user gesture. */
    fun start()

    /** The current audio output time, in seconds. */
    fun now(): Double

    /**
     * What [now] read at the moment of an input event, given the event's own timestamp
     * (`PointerInputChange.uptimeMillis`: the DOM `event.timeStamp` on web, `MotionEvent`'s event
     * time on Android). Judging a tap by when the finger landed rather than when its handler ran
     * keeps phones' frame-batched touch delivery from adding up to a frame of random lateness to
     * every tap. Falls back to [now] if the timestamp doesn't look like it's in that timebase.
     */
    fun timeAtInputEvent(eventUptimeMillis: Long): Double
}

/** Older than this, an event timestamp is more likely in the wrong timebase than really that stale. */
internal const val MAX_INPUT_AGE_MS = 250.0

/** A tap arriving at [now] that happened [elapsedMs] ago, per [AudioClock.timeAtInputEvent]. */
internal fun backdate(now: Double, elapsedMs: Double): Double =
    if (elapsedMs in 0.0..MAX_INPUT_AGE_MS) now - elapsedMs / 1000.0 else now

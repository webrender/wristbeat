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
     * Converts a raw input-event timestamp (the platform's event timebase, e.g. a JS
     * PointerEvent.timeStamp in ms) into the same audio-clock timebase as [now], in seconds.
     */
    fun audibleTimeForInputEvent(eventTimestampMs: Double): Double
}

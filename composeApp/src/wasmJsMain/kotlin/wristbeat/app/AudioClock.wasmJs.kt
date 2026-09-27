package wristbeat.app

import kotlin.math.max

actual class AudioClock actual constructor() {
    init {
        jsInstallInputStamp()
    }

    actual fun start() {
        WebAudioContext.ensureStarted()
    }

    actual fun now(): Double {
        val ctx = WebAudioContext.context() ?: return 0.0
        return SmoothContextTime.read(ctx)
    }

    // Compose (1.7) stamps web pointer events with the time it handled them, not the DOM event's
    // timeStamp, so a touch that waited behind a slow frame would carry that wait. Compose handles
    // DOM input synchronously, though, so the event being handled right now is the latest one
    // jsInstallInputStamp's capture listener saw, and its timeStamp is when the finger landed.
    actual fun timeAtInputEvent(eventUptimeMillis: Long): Double =
        backdate(now(), jsPerformanceNow() - jsLastInputTimeStamp())
}

// Records the timeStamp of every pointer/mouse/touch event before Compose sees it. Idempotent.
private fun jsInstallInputStamp(): Unit = js(
    """{
        if (window.__wristbeatInputStamp !== undefined) return;
        window.__wristbeatInputStamp = -1;
        var stamp = function (e) { window.__wristbeatInputStamp = e.timeStamp; };
        ['pointerdown', 'pointermove', 'pointerup', 'mousedown', 'mousemove', 'mouseup',
         'touchstart', 'touchmove', 'touchend'].forEach(function (type) {
            window.addEventListener(type, stamp, { capture: true, passive: true });
        });
    }"""
)

private fun jsLastInputTimeStamp(): Double = js("window.__wristbeatInputStamp")

/**
 * The AudioContext's `currentTime`, smoothed. `currentTime` only advances once per audio callback,
 * which on phones can be 10–40ms of audio at a time (desktop is usually a few ms), so reading it
 * raw quantizes every tap to that step. `getOutputTimestamp()` instead pairs a context time with
 * the `performance.now()` it plays at, which extrapolates smoothly; [lead] tracks how far
 * `currentTime` runs ahead of that output position (the output latency plus part of a callback),
 * averaged slowly so the result stays in `currentTime`'s timebase — the one sounds are scheduled
 * in — without its steps. Falls back to raw `currentTime` until the browser has a timestamp.
 */
private object SmoothContextTime {
    private var lead = Double.NaN
    private var last = 0.0

    fun read(ctx: JsAny): Double {
        val current = jsCurrentTime(ctx)
        val output = jsOutputPosition(ctx)
        val t = if (output < 0) {
            current
        } else {
            val diff = current - output
            // A big jump means the output itself changed (e.g. a headset connected): re-seed.
            lead = if (lead.isNaN() || kotlin.math.abs(diff - lead) > 0.1) diff else lead + (diff - lead) * 0.05
            output + lead
        }
        last = max(last, t)
        return last
    }
}

private fun jsCurrentTime(ctx: JsAny): Double = js("ctx.currentTime")

private fun jsPerformanceNow(): Double = js("performance.now()")

// The context time reaching the speaker right now, extrapolated from getOutputTimestamp(), or -1
// where it's unsupported or not yet reporting.
private fun jsOutputPosition(ctx: JsAny): Double = js(
    """{
        if (!ctx.getOutputTimestamp) return -1;
        var ts = ctx.getOutputTimestamp();
        if (!(ts.contextTime > 0 && ts.performanceTime > 0)) return -1;
        return ts.contextTime + (performance.now() - ts.performanceTime) / 1000;
    }"""
)

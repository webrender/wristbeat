package wristbeat.app

actual class AudioClock actual constructor() {
    actual fun start() {
        WebAudioContext.ensureStarted()
    }

    actual fun now(): Double {
        val ctx = WebAudioContext.context() ?: return 0.0
        return jsCurrentTime(ctx)
    }

    actual fun audibleTimeForInputEvent(eventTimestampMs: Double): Double {
        val ctx = WebAudioContext.context() ?: return 0.0
        return jsAudibleAt(ctx, eventTimestampMs)
    }
}

private fun jsCurrentTime(ctx: JsAny): Double = js("ctx.currentTime")

// Same fallback chain as the prototype's audibleAt(): prefer getOutputTimestamp's
// contextTime/performanceTime pair, fall back to currentTime minus output/base latency.
private fun jsAudibleAt(ctx: JsAny, perfT: Double): Double = js(
    """{
        if (ctx.getOutputTimestamp) {
            var ts = ctx.getOutputTimestamp();
            if (ts.contextTime > 0 && ts.performanceTime > 0) {
                return ts.contextTime + (perfT - ts.performanceTime) / 1000;
            }
        }
        return ctx.currentTime - (ctx.outputLatency || ctx.baseLatency || 0) + (perfT - performance.now()) / 1000;
    }"""
)

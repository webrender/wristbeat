package wristbeat.app

/** Single shared AudioContext behind [AudioClock] and [AudioEngine] so their timebases always agree. */
internal object WebAudioContext {
    private var ctx: JsAny? = null
    private var noise: JsAny? = null

    fun ensureStarted(): JsAny {
        val existing = ctx
        if (existing == null) {
            val created = jsCreateAudioContext()
            ctx = created
            return created
        }
        jsResumeIfSuspended(existing)
        return existing
    }

    fun context(): JsAny? = ctx

    /** A shared 1s white-noise buffer, generated once and reused by every noise-based voice. */
    fun noiseBuffer(ctx: JsAny): JsAny {
        val existing = noise
        if (existing != null) return existing
        val created = jsCreateNoiseBuffer(ctx)
        noise = created
        return created
    }
}

private fun jsCreateAudioContext(): JsAny =
    js("new (window.AudioContext || window.webkitAudioContext)({ latencyHint: 'interactive' })")

private fun jsResumeIfSuspended(ctx: JsAny): Unit =
    js("{ if (ctx.state !== 'running') { ctx.resume(); } }")

private fun jsCreateNoiseBuffer(ctx: JsAny): JsAny = js(
    """{
        var len = Math.floor(ctx.sampleRate * 1.0);
        var buf = ctx.createBuffer(1, len, ctx.sampleRate);
        var d = buf.getChannelData(0);
        for (var i = 0; i < len; i++) d[i] = Math.random() * 2 - 1;
        return buf;
    }"""
)

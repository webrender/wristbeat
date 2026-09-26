package wristbeat.app

/** Single shared AudioContext behind [AudioClock] and [AudioEngine] so their timebases always agree. */
internal object WebAudioContext {
    private var ctx: JsAny? = null

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
}

private fun jsCreateAudioContext(): JsAny =
    js("new (window.AudioContext || window.webkitAudioContext)({ latencyHint: 'interactive' })")

private fun jsResumeIfSuspended(ctx: JsAny): Unit =
    js("{ if (ctx.state !== 'running') { ctx.resume(); } }")

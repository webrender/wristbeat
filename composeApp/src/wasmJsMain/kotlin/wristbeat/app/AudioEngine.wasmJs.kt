package wristbeat.app

import wristbeat.core.SoundId

actual class AudioEngine actual constructor() {
    actual fun play(sound: SoundId, atSeconds: Double) {
        val ctx = WebAudioContext.context() ?: return
        jsScheduleClick(ctx, atSeconds, sound == SoundId.CLICK_ACCENT)
    }
}

// Ported from the prototype's SND.click(t, accent) (wristbeat-prototype.html:216).
private fun jsScheduleClick(ctx: JsAny, atSeconds: Double, accent: Boolean): Unit = js(
    """{
        var freq = accent ? 2100 : 1450;
        var o = ctx.createOscillator();
        o.type = 'square';
        o.frequency.setValueAtTime(freq, atSeconds);
        var g = ctx.createGain();
        g.gain.setValueAtTime(0.0001, atSeconds);
        g.gain.exponentialRampToValueAtTime(0.14, atSeconds + 0.004);
        g.gain.exponentialRampToValueAtTime(0.0001, atSeconds + 0.03);
        o.connect(g).connect(ctx.destination);
        o.start(atSeconds);
        o.stop(atSeconds + 0.05);
    }"""
)

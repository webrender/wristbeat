package wristbeat.app

import kotlin.math.pow
import wristbeat.core.SoundId

actual class AudioEngine actual constructor() {
    actual fun play(sound: SoundId, atSeconds: Double, param: Double) {
        val ctx = WebAudioContext.context() ?: return
        val noise = WebAudioContext.noiseBuffer(ctx)
        when (sound) {
            SoundId.CLICK_NORMAL -> jsClick(ctx, atSeconds, false)
            SoundId.CLICK_ACCENT -> jsClick(ctx, atSeconds, true)
            SoundId.STICK -> {
                jsOsc(ctx, "square", 1650.0, atSeconds, 0.004, 0.1, 0.03)
                jsNoise(ctx, noise, atSeconds, 0.02, 0.18, "highpass", 4200.0, 1.0)
            }
            SoundId.KICK -> jsOscRamp(ctx, "sine", 150.0, 44.0, atSeconds, 0.004, 0.85, 0.26)
            SoundId.RIM -> {
                jsNoise(ctx, noise, atSeconds, 0.05, 0.22, "bandpass", 1900.0, 1.4)
                jsOsc(ctx, "triangle", 440.0, atSeconds, 0.004, 0.16, 0.04)
            }
            SoundId.HAT -> jsNoise(ctx, noise, atSeconds, 0.03, 0.08, "highpass", 7800.0, 1.0)
            SoundId.BASS_LONG -> jsBass(ctx, mtof(param), atSeconds, 0.35)
            SoundId.BASS_MED -> jsBass(ctx, mtof(param), atSeconds, 0.3)
            SoundId.BASS_SHORT -> jsBass(ctx, mtof(param), atSeconds, 0.18)
            SoundId.UKE_F -> ukeStrum(ctx, listOf(65.0, 69.0, 72.0), atSeconds)
            SoundId.UKE_C -> ukeStrum(ctx, listOf(64.0, 67.0, 72.0), atSeconds)
            SoundId.UKE_BB -> ukeStrum(ctx, listOf(62.0, 65.0, 70.0), atSeconds)
            SoundId.MEL -> {
                val freq = mtof(param)
                jsOsc(ctx, "sine", freq, atSeconds, 0.004, 0.12 * 0.45, 0.38)
                jsOsc(ctx, "triangle", freq * 2.0, atSeconds, 0.004, 0.035 * 0.45, 0.1)
            }
            SoundId.LEAD_SNAP -> {
                jsNoise(ctx, noise, atSeconds, 0.045, 0.6, "bandpass", 2200.0, 5.0)
                jsOsc(ctx, "sine", 1040.0, atSeconds, 0.004, 0.28, 0.035)
            }
            SoundId.PLAYER_SNAP -> {
                jsNoise(ctx, noise, atSeconds, 0.045, 0.65, "bandpass", 3300.0, 5.0)
                jsOsc(ctx, "sine", 1560.0, atSeconds, 0.004, 0.28, 0.035)
            }
            SoundId.PERFECT_DING -> {
                jsOsc(ctx, "sine", 1976.0, atSeconds, 0.004, 0.1, 0.22)
                jsOsc(ctx, "sine", 2960.0, atSeconds, 0.004, 0.04, 0.15)
            }
            SoundId.WHIFF -> jsOscRamp(ctx, "sawtooth", 320.0, 120.0, atSeconds, 0.004, 0.06, 0.16)
            SoundId.STEEL_PAN -> {
                // Steel pans are tuned so the octave and twelfth ring over the fundamental; the
                // upper partials die away first, which gives the bright "ping" before the hum.
                val freq = mtof(param)
                jsOsc(ctx, "sine", freq, atSeconds, 0.003, 0.09, 0.45)
                jsOsc(ctx, "sine", freq * 2.0, atSeconds, 0.003, 0.05, 0.2)
                jsOsc(ctx, "sine", freq * 3.0, atSeconds, 0.003, 0.02, 0.08)
            }
            // Mango Chop's offbeat chord stab: a short, bright square with a sine an octave down
            // for body, clipped instead of strummed so it doesn't read as Snap Crabs' ukulele.
            SoundId.KEYS -> {
                val freq = mtof(param)
                jsOsc(ctx, "square", freq, atSeconds, 0.003, 0.022, 0.09)
                jsOsc(ctx, "sine", freq / 2.0, atSeconds, 0.003, 0.05, 0.11)
            }
            // Mango Chop's 16th-note shaker, filtered well above the whistles.
            SoundId.SHAKER -> jsNoise(ctx, noise, atSeconds, 0.035, 0.06, "highpass", 6500.0, 1.0)
            // Ported from the prototype's SND.whistle(t, dur, f0, f1) (wristbeat-prototype.html:198).
            SoundId.WHISTLE_MANGO -> whistle(ctx, noise, atSeconds, 0.26, 520.0, 1150.0)
            SoundId.WHISTLE_LIME -> whistle(ctx, noise, atSeconds, 0.13, 950.0, 1900.0)
            // Falling instead of rising, and doubled, so a pineapple is recognisable by ear.
            SoundId.WHISTLE_PINEAPPLE -> {
                whistle(ctx, noise, atSeconds, 0.12, 1250.0, 800.0)
                whistle(ctx, noise, atSeconds + 0.15, 0.12, 1250.0, 800.0)
            }
            // Ported from the prototype's SND.chop(t) (wristbeat-prototype.html:210).
            SoundId.CHOP -> {
                jsNoise(ctx, noise, atSeconds, 0.06, 0.55, "bandpass", 1300.0, 1.5)
                jsOscRamp(ctx, "sine", 260.0, 90.0, atSeconds, 0.004, 0.5, 0.12)
                jsNoise(ctx, noise, atSeconds + 0.01, 0.1, 0.16, "highpass", 5200.0, 1.0)
            }
            SoundId.SLICE -> {
                jsNoise(ctx, noise, atSeconds, 0.14, 0.4, "bandpass", 3200.0, 1.2)
                jsOscRamp(ctx, "sine", 2400.0, 900.0, atSeconds, 0.004, 0.12, 0.1)
            }
            // Ported from the prototype's SND.thud(t) (wristbeat-prototype.html:215).
            SoundId.THUD -> jsOscRamp(ctx, "sine", 130.0, 55.0, atSeconds, 0.004, 0.5, 0.16)
        }
    }
}

private fun mtof(m: Double): Double = 440.0 * 2.0.pow((m - 69.0) / 12.0)

private fun whistle(ctx: JsAny, noise: JsAny, t: Double, dur: Double, f0: Double, f1: Double) {
    jsOscRamp(ctx, "sine", f0, f1, t, 0.015, 0.2, dur)
    jsNoise(ctx, noise, t, 0.03, 0.2, "bandpass", 900.0, 2.0)
}

// Ported from the prototype's SND.uke(t, notes, len) (wristbeat-prototype.html:193): each note in the
// chord fires ~11ms after the last, giving a strummed feel instead of a flat chord stab.
private fun ukeStrum(ctx: JsAny, notes: List<Double>, t: Double) {
    notes.forEachIndexed { i, note ->
        val tt = t + i * 0.011
        val freq = mtof(note)
        jsOsc(ctx, "triangle", freq, tt, 0.004, 0.075, 0.14)
        jsOsc(ctx, "square", freq, tt, 0.004, 0.018, 0.05)
    }
}

// Ported from the prototype's SND.click(t, accent) (wristbeat-prototype.html:216).
private fun jsClick(ctx: JsAny, atSeconds: Double, accent: Boolean): Unit = js(
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

// Ported from the prototype's osc(type, f, t, dur, peak, a) (wristbeat-prototype.html:168).
private fun jsOsc(ctx: JsAny, type: String, freq: Double, t: Double, attack: Double, peak: Double, decay: Double): Unit = js(
    """{
        var o = ctx.createOscillator(); o.type = type; o.frequency.setValueAtTime(freq, t);
        var g = ctx.createGain();
        g.gain.setValueAtTime(0.0001, t);
        g.gain.exponentialRampToValueAtTime(peak, t + attack);
        g.gain.exponentialRampToValueAtTime(0.0001, t + attack + decay);
        o.connect(g).connect(ctx.destination);
        o.start(t); o.stop(t + attack + decay + 0.05);
    }"""
)

// Same shape as jsOsc, but with a frequency ramp from f0 to f1 (kick, whiff).
private fun jsOscRamp(ctx: JsAny, type: String, f0: Double, f1: Double, t: Double, attack: Double, peak: Double, decay: Double): Unit = js(
    """{
        var o = ctx.createOscillator(); o.type = type;
        o.frequency.setValueAtTime(f0, t);
        o.frequency.exponentialRampToValueAtTime(f1, t + attack + decay);
        var g = ctx.createGain();
        g.gain.setValueAtTime(0.0001, t);
        g.gain.exponentialRampToValueAtTime(peak, t + attack);
        g.gain.exponentialRampToValueAtTime(0.0001, t + attack + decay);
        o.connect(g).connect(ctx.destination);
        o.start(t); o.stop(t + attack + decay + 0.05);
    }"""
)

// Ported from the prototype's noise(t, dur, peak, type, freq, q) (wristbeat-prototype.html:174).
private fun jsNoise(ctx: JsAny, buffer: JsAny, t: Double, dur: Double, peak: Double, filterType: String, freq: Double, q: Double): Unit = js(
    """{
        var s = ctx.createBufferSource(); s.buffer = buffer;
        s.playbackRate.value = 0.8 + Math.random() * 0.4;
        var f = ctx.createBiquadFilter(); f.type = filterType; f.frequency.value = freq; f.Q.value = q;
        var g = ctx.createGain();
        g.gain.setValueAtTime(0.0001, t);
        g.gain.exponentialRampToValueAtTime(peak, t + 0.002);
        g.gain.exponentialRampToValueAtTime(0.0001, t + 0.002 + dur);
        s.connect(f).connect(g).connect(ctx.destination);
        s.start(t, Math.random() * 0.5); s.stop(t + dur + 0.05);
    }"""
)

// Ported from the prototype's SND.bass(t, m, len) (wristbeat-prototype.html:186).
private fun jsBass(ctx: JsAny, freq: Double, t: Double, len: Double): Unit = js(
    """{
        var o = ctx.createOscillator(); o.type = 'sawtooth'; o.frequency.value = freq;
        var f = ctx.createBiquadFilter(); f.type = 'lowpass';
        f.frequency.setValueAtTime(900, t); f.frequency.exponentialRampToValueAtTime(160, t + len);
        var g = ctx.createGain();
        g.gain.setValueAtTime(0.0001, t);
        g.gain.exponentialRampToValueAtTime(0.3, t + 0.008);
        g.gain.exponentialRampToValueAtTime(0.0001, t + 0.008 + len);
        o.connect(f).connect(g).connect(ctx.destination);
        o.start(t); o.stop(t + len + 0.1);
    }"""
)

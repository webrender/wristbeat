package wristbeat.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * One scheduled sound on [AndroidAudio]'s render thread — a software stand-in for the Web Audio node
 * graphs in AudioEngine.wasmJs.kt, with the same shapes (exponential gain/pitch ramps, biquads).
 */
internal abstract class SynthVoice(private val startFrame: Long) {
    private var started = false

    /** Adds this voice into [out] for frames [blockStart, blockStart + n). Returns true once it's finished. */
    fun render(out: FloatArray, blockStart: Long, n: Int): Boolean {
        // A voice scheduled in the past (e.g. a tap's snap at "now") starts at the render cursor instead.
        val offset = if (started) 0 else (startFrame - blockStart).coerceAtLeast(0L)
        if (offset >= n) return false
        started = true
        for (i in offset.toInt() until n) {
            val s = next() ?: return true
            out[i] += s
        }
        return false
    }

    /** The next sample, or null once the voice has decayed. */
    protected abstract fun next(): Float?
}

internal enum class Wave { SINE, SQUARE, TRIANGLE, SAWTOOTH }

internal enum class FilterType { LOWPASS, HIGHPASS, BANDPASS }

/** Web Audio's `exponentialRampToValueAtTime` envelope: 0.0001 → [peak] over [attack], back to 0.0001 over [decay]. */
private class Envelope(attack: Double, peak: Double, decay: Double, sampleRate: Int) {
    private val attackFrames = maxOf(1, (attack * sampleRate).toInt())
    private val totalFrames = attackFrames + maxOf(1, (decay * sampleRate).toInt())
    private val attackMul = (peak / FLOOR).pow(1.0 / attackFrames)
    private val decayMul = (FLOOR / peak).pow(1.0 / (totalFrames - attackFrames))
    private var gain = FLOOR
    private var frame = 0

    /** The next gain value, or null once the envelope has finished. */
    fun next(): Double? {
        if (frame >= totalFrames) return null
        val g = gain
        gain *= if (frame < attackFrames) attackMul else decayMul
        frame++
        return g
    }

    companion object {
        const val FLOOR = 0.0001
    }
}

/** Phase-accumulator oscillator, band-limited with PolyBLEP for the square and sawtooth. */
private class Oscillator(private val wave: Wave, private val sampleRate: Int) {
    private var phase = 0.0

    fun next(freq: Double): Double {
        val dt = freq / sampleRate
        val t = phase
        phase += dt
        if (phase >= 1.0) phase -= 1.0
        return when (wave) {
            Wave.SINE -> sin(2 * PI * t)
            Wave.SAWTOOTH -> 2 * t - 1 - polyBlep(t, dt)
            Wave.SQUARE -> (if (t < 0.5) 1.0 else -1.0) + polyBlep(t, dt) - polyBlep((t + 0.5) % 1.0, dt)
            Wave.TRIANGLE -> 1 - 4 * abs(((t + 0.25) % 1.0) - 0.5)
        }
    }

    private fun polyBlep(t: Double, dt: Double): Double = when {
        t < dt -> (t / dt).let { x -> x + x - x * x - 1 }
        t > 1 - dt -> ((t - 1) / dt).let { x -> x * x + x + x + 1 }
        else -> 0.0
    }
}

/**
 * RBJ-cookbook biquad matching Web Audio's BiquadFilterNode, including its quirk that lowpass/highpass
 * Q is a resonance in dB while bandpass Q is linear.
 */
private class Biquad(private val type: FilterType, private val sampleRate: Int) {
    private var b0 = 0.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

    fun set(freq: Double, q: Double) {
        val w0 = 2 * PI * freq.coerceIn(10.0, sampleRate * 0.49) / sampleRate
        val cw = cos(w0)
        val qLinear = if (type == FilterType.BANDPASS) q else 10.0.pow(q / 20)
        val alpha = sin(w0) / (2 * qLinear)
        val a0 = 1 + alpha
        when (type) {
            FilterType.LOWPASS -> { b0 = (1 - cw) / 2; b1 = 1 - cw; b2 = (1 - cw) / 2 }
            FilterType.HIGHPASS -> { b0 = (1 + cw) / 2; b1 = -(1 + cw); b2 = (1 + cw) / 2 }
            FilterType.BANDPASS -> { b0 = alpha; b1 = 0.0; b2 = -alpha }
        }
        b0 /= a0; b1 /= a0; b2 /= a0
        a1 = -2 * cw / a0
        a2 = (1 - alpha) / a0
    }

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }
}

/** Port of the prototype's osc(); [f1] != [f0] gives the kick/whiff exponential pitch drop over the whole note. */
internal class OscVoice(
    startFrame: Long,
    wave: Wave,
    f0: Double,
    private val f1: Double,
    attack: Double,
    peak: Double,
    decay: Double,
    sampleRate: Int,
) : SynthVoice(startFrame) {
    private val osc = Oscillator(wave, sampleRate)
    private val env = Envelope(attack, peak, decay, sampleRate)
    private var freq = f0
    private val freqMul = (f1 / f0).pow(1.0 / ((attack + decay) * sampleRate))

    override fun next(): Float? {
        val g = env.next() ?: return null
        val s = osc.next(freq) * g
        freq = if (freqMul < 1) maxOf(f1, freq * freqMul) else minOf(f1, freq * freqMul)
        return s.toFloat()
    }
}

/** Port of the prototype's noise(): filtered white noise with a 2ms attack. */
internal class NoiseVoice(
    startFrame: Long,
    filterType: FilterType,
    freq: Double,
    q: Double,
    dur: Double,
    peak: Double,
    sampleRate: Int,
) : SynthVoice(startFrame) {
    private val filter = Biquad(filterType, sampleRate).apply { set(freq, q) }
    private val env = Envelope(0.002, peak, dur, sampleRate)

    override fun next(): Float? {
        val g = env.next() ?: return null
        return (filter.process(Random.nextDouble(-1.0, 1.0)) * g).toFloat()
    }
}

/** Port of the prototype's SND.bass(): a sawtooth through a lowpass sweeping 900Hz → 160Hz over [len]. */
internal class BassVoice(startFrame: Long, private val freq: Double, len: Double, sampleRate: Int) : SynthVoice(startFrame) {
    private val osc = Oscillator(Wave.SAWTOOTH, sampleRate)
    private val env = Envelope(0.008, 0.3, len, sampleRate)
    private val filter = Biquad(FilterType.LOWPASS, sampleRate)
    private var cutoff = 900.0
    private val cutoffMul = (160.0 / 900.0).pow(1.0 / (len * sampleRate))
    private var frame = 0

    override fun next(): Float? {
        val g = env.next() ?: return null
        // Recomputing coefficients every 32 samples is plenty for a sweep this slow.
        if (frame % 32 == 0) filter.set(cutoff, 1.0)
        frame++
        cutoff = maxOf(160.0, cutoff * cutoffMul)
        return (filter.process(osc.next(freq)) * g).toFloat()
    }
}

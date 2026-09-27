package wristbeat.app

import kotlin.math.pow
import wristbeat.core.SoundId

/** Same voicings as AudioEngine.wasmJs.kt, rendered by [AndroidAudio]'s software synth. */
actual class AudioEngine actual constructor() {
    actual fun play(sound: SoundId, atSeconds: Double, param: Double) {
        val t = atSeconds
        when (sound) {
            SoundId.CLICK_NORMAL -> osc(Wave.SQUARE, 1450.0, t, 0.004, 0.14, 0.026)
            SoundId.CLICK_ACCENT -> osc(Wave.SQUARE, 2100.0, t, 0.004, 0.14, 0.026)
            SoundId.STICK -> {
                osc(Wave.SQUARE, 1650.0, t, 0.004, 0.1, 0.03)
                noise(t, 0.02, 0.18, FilterType.HIGHPASS, 4200.0, 1.0)
            }
            SoundId.KICK -> osc(Wave.SINE, 150.0, t, 0.004, 0.85, 0.26, endFreq = 44.0)
            SoundId.RIM -> {
                noise(t, 0.05, 0.22, FilterType.BANDPASS, 1900.0, 1.4)
                osc(Wave.TRIANGLE, 440.0, t, 0.004, 0.16, 0.04)
            }
            SoundId.HAT -> noise(t, 0.03, 0.08, FilterType.HIGHPASS, 7800.0, 1.0)
            SoundId.BASS_LONG -> bass(mtof(param), t, 0.35)
            SoundId.BASS_MED -> bass(mtof(param), t, 0.3)
            SoundId.BASS_SHORT -> bass(mtof(param), t, 0.18)
            SoundId.UKE_F -> ukeStrum(listOf(65.0, 69.0, 72.0), t)
            SoundId.UKE_C -> ukeStrum(listOf(64.0, 67.0, 72.0), t)
            SoundId.UKE_BB -> ukeStrum(listOf(62.0, 65.0, 70.0), t)
            SoundId.MEL -> {
                val freq = mtof(param)
                osc(Wave.SINE, freq, t, 0.004, 0.12 * 0.45, 0.38)
                osc(Wave.TRIANGLE, freq * 2.0, t, 0.004, 0.035 * 0.45, 0.1)
            }
            SoundId.LEAD_SNAP -> {
                noise(t, 0.045, 0.6, FilterType.BANDPASS, 2200.0, 5.0)
                osc(Wave.SINE, 1040.0, t, 0.004, 0.28, 0.035)
            }
            SoundId.PLAYER_SNAP -> {
                noise(t, 0.045, 0.65, FilterType.BANDPASS, 3300.0, 5.0)
                osc(Wave.SINE, 1560.0, t, 0.004, 0.28, 0.035)
            }
            SoundId.PERFECT_DING -> {
                osc(Wave.SINE, 1976.0, t, 0.004, 0.1, 0.22)
                osc(Wave.SINE, 2960.0, t, 0.004, 0.04, 0.15)
            }
            SoundId.WHIFF -> osc(Wave.SAWTOOTH, 320.0, t, 0.004, 0.06, 0.16, endFreq = 120.0)
            SoundId.STEEL_PAN -> {
                val freq = mtof(param)
                osc(Wave.SINE, freq, t, 0.003, 0.09, 0.45)
                osc(Wave.SINE, freq * 2.0, t, 0.003, 0.05, 0.2)
                osc(Wave.SINE, freq * 3.0, t, 0.003, 0.02, 0.08)
            }
            SoundId.KEYS -> {
                val freq = mtof(param)
                osc(Wave.SQUARE, freq, t, 0.003, 0.022, 0.09)
                osc(Wave.SINE, freq / 2.0, t, 0.003, 0.05, 0.11)
            }
            SoundId.SHAKER -> noise(t, 0.035, 0.06, FilterType.HIGHPASS, 6500.0, 1.0)
            SoundId.WHISTLE_MANGO -> whistle(t, 0.26, 520.0, 1150.0)
            SoundId.WHISTLE_LIME -> whistle(t, 0.13, 950.0, 1900.0)
            SoundId.WHISTLE_PINEAPPLE -> {
                whistle(t, 0.12, 1250.0, 800.0)
                whistle(t + 0.15, 0.12, 1250.0, 800.0)
            }
            SoundId.CHOP -> {
                noise(t, 0.06, 0.55, FilterType.BANDPASS, 1300.0, 1.5)
                osc(Wave.SINE, 260.0, t, 0.004, 0.5, 0.12, endFreq = 90.0)
                noise(t + 0.01, 0.1, 0.16, FilterType.HIGHPASS, 5200.0, 1.0)
            }
            SoundId.SLICE -> {
                noise(t, 0.14, 0.4, FilterType.BANDPASS, 3200.0, 1.2)
                osc(Wave.SINE, 2400.0, t, 0.004, 0.12, 0.1, endFreq = 900.0)
            }
            SoundId.THUD -> osc(Wave.SINE, 130.0, t, 0.004, 0.5, 0.16, endFreq = 55.0)
        }
    }
}

private fun mtof(m: Double): Double = 440.0 * 2.0.pow((m - 69.0) / 12.0)

private fun whistle(t: Double, dur: Double, f0: Double, f1: Double) {
    osc(Wave.SINE, f0, t, 0.015, 0.2, dur, endFreq = f1)
    noise(t, 0.03, 0.2, FilterType.BANDPASS, 900.0, 2.0)
}

// Each note in the chord fires ~11ms after the last, for a strummed feel (see the wasmJs ukeStrum).
private fun ukeStrum(notes: List<Double>, t: Double) {
    notes.forEachIndexed { i, note ->
        val tt = t + i * 0.011
        val freq = mtof(note)
        osc(Wave.TRIANGLE, freq, tt, 0.004, 0.075, 0.14)
        osc(Wave.SQUARE, freq, tt, 0.004, 0.018, 0.05)
    }
}

private fun osc(wave: Wave, freq: Double, t: Double, attack: Double, peak: Double, decay: Double, endFreq: Double = freq) =
    AndroidAudio.schedule(OscVoice(AndroidAudio.frameAt(t), wave, freq, endFreq, attack, peak, decay, AndroidAudio.sampleRate))

private fun noise(t: Double, dur: Double, peak: Double, filterType: FilterType, freq: Double, q: Double) =
    AndroidAudio.schedule(NoiseVoice(AndroidAudio.frameAt(t), filterType, freq, q, dur, peak, AndroidAudio.sampleRate))

private fun bass(freq: Double, t: Double, len: Double) =
    AndroidAudio.schedule(BassVoice(AndroidAudio.frameAt(t), freq, len, AndroidAudio.sampleRate))

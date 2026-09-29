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
            SoundId.CRASH -> {
                noise(t, 1.4, 0.13, FilterType.HIGHPASS, 5200.0, 1.0)
                noise(t, 0.3, 0.12, FilterType.BANDPASS, 3400.0, 0.8)
            }
            SoundId.TOM -> {
                val freq = mtof(param)
                osc(Wave.SINE, freq, t, 0.004, 0.55, 0.2, endFreq = freq * 0.6)
                noise(t, 0.03, 0.1, FilterType.BANDPASS, 1200.0, 1.0)
            }
            // Same voicings as AudioEngine.wasmJs.kt's bongo hits.
            SoundId.LEAD_BONGO_HI -> {
                noise(t, 0.05, 0.55, FilterType.BANDPASS, 1500.0, 4.0)
                osc(Wave.SINE, 620.0, t, 0.003, 0.32, 0.05, endFreq = 340.0)
            }
            SoundId.PLAYER_BONGO_HI -> {
                noise(t, 0.05, 0.45, FilterType.BANDPASS, 1700.0, 4.0)
                osc(Wave.SINE, 700.0, t, 0.003, 0.26, 0.05, endFreq = 380.0)
            }
            SoundId.LEAD_BONGO_LO -> {
                noise(t, 0.09, 0.4, FilterType.BANDPASS, 480.0, 2.5)
                osc(Wave.SINE, 220.0, t, 0.004, 0.4, 0.11, endFreq = 130.0)
            }
            SoundId.PLAYER_BONGO_LO -> {
                noise(t, 0.09, 0.32, FilterType.BANDPASS, 520.0, 2.5)
                osc(Wave.SINE, 250.0, t, 0.004, 0.32, 0.11, endFreq = 150.0)
            }
            // Same voicings as AudioEngine.wasmJs.kt's CHEER/BOO.
            SoundId.CHEER -> {
                noise(t, 0.6, 0.18, FilterType.BANDPASS, 2200.0, 0.6)
                for ((i, note) in listOf(60.0, 64.0, 67.0, 72.0, 76.0).withIndex()) {
                    val tt = t + i * 0.07
                    val freq = mtof(note)
                    osc(Wave.TRIANGLE, freq, tt, 0.006, 0.16, 0.22)
                    osc(Wave.SQUARE, freq, tt, 0.006, 0.05, 0.12)
                }
            }
            SoundId.BOO -> {
                noise(t, 0.9, 0.14, FilterType.LOWPASS, 700.0, 0.8)
                for ((i, note) in listOf(67.0, 65.0, 64.0, 60.0).withIndex()) {
                    val tt = t + i * 0.22
                    val freq = mtof(note)
                    osc(Wave.SAWTOOTH, freq, tt, 0.02, 0.22, 0.24, endFreq = freq * 0.94)
                }
            }
            // Same voicings as AudioEngine.wasmJs.kt's Bongo Blitz band.
            SoundId.MARIMBA -> {
                val freq = mtof(param)
                osc(Wave.SINE, freq, t, 0.002, 0.13, 0.32)
                osc(Wave.SINE, freq * 4.0, t, 0.001, 0.035, 0.04)
            }
            SoundId.PAN_FLUTE -> {
                val freq = mtof(param)
                osc(Wave.SINE, freq, t, 0.025, 0.1, 0.34)
                osc(Wave.TRIANGLE, freq * 2.0, t, 0.02, 0.012, 0.18)
                noise(t, 0.16, 0.12, FilterType.BANDPASS, freq, 14.0)
            }
            SoundId.PAD -> osc(Wave.TRIANGLE, mtof(param), t, 0.09, 0.028, 1.1)
            SoundId.CLAVE -> osc(Wave.SINE, 2500.0, t, 0.001, 0.12, 0.035)
            SoundId.CLAP -> {
                for (i in 0 until 3) noise(t + i * 0.011, 0.012, 0.2, FilterType.BANDPASS, 1150.0, 1.2)
                noise(t + 0.033, 0.11, 0.16, FilterType.BANDPASS, 1050.0, 0.9)
            }
            // Same voicings as AudioEngine.wasmJs.kt's Night Drift band and sounds.
            SoundId.SAW_LEAD -> {
                val freq = mtof(param)
                filteredOsc(Wave.SAWTOOTH, freq, t, 0.005, 0.035, 0.24, 4200.0, 1400.0)
                filteredOsc(Wave.SAWTOOTH, freq * 1.006, t, 0.005, 0.035, 0.24, 4200.0, 1400.0)
                osc(Wave.SQUARE, freq / 2.0, t, 0.005, 0.012, 0.16)
            }
            SoundId.SAW_STAB -> {
                val freq = mtof(param)
                filteredOsc(Wave.SAWTOOTH, freq, t, 0.008, 0.028, 0.16, 3000.0, 600.0)
                filteredOsc(Wave.SAWTOOTH, freq * 0.994, t, 0.008, 0.028, 0.16, 3000.0, 600.0)
            }
            SoundId.SNARE -> {
                noise(t, 0.13, 0.26, FilterType.HIGHPASS, 1800.0, 1.0)
                osc(Wave.TRIANGLE, 200.0, t, 0.003, 0.22, 0.07, endFreq = 150.0)
            }
            SoundId.OPEN_HAT -> noise(t, 0.14, 0.06, FilterType.HIGHPASS, 7200.0, 1.0)
            SoundId.CORNER_CALL -> {
                osc(Wave.SQUARE, 1319.0, t, 0.003, 0.07, 0.07)
                osc(Wave.SQUARE, 1760.0, t + 0.09, 0.003, 0.07, 0.09)
            }
            SoundId.BOOST -> {
                osc(Wave.SQUARE, 1200.0, t, 0.002, 0.1, 0.02)
                osc(Wave.SAWTOOTH, 90.0, t, 0.004, 0.12, 0.14, endFreq = 190.0)
                noise(t + 0.02, 0.16, 0.12, FilterType.HIGHPASS, 3200.0, 1.0)
            }
            SoundId.SKID -> {
                noise(t, 0.22, 0.35, FilterType.BANDPASS, 2600.0, 6.0)
                osc(Wave.SINE, 1900.0, t, 0.004, 0.08, 0.2, endFreq = 1450.0)
            }
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

private fun filteredOsc(wave: Wave, freq: Double, t: Double, attack: Double, peak: Double, decay: Double, cutoff0: Double, cutoff1: Double) =
    AndroidAudio.schedule(
        FilteredOscVoice(AndroidAudio.frameAt(t), wave, freq, attack, peak, decay, cutoff0, cutoff1, AndroidAudio.sampleRate),
    )

private fun bass(freq: Double, t: Double, len: Double) =
    AndroidAudio.schedule(BassVoice(AndroidAudio.frameAt(t), freq, len, AndroidAudio.sampleRate))

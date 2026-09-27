package wristbeat.core

/** A sound to dispatch through a platform AudioEngine. Chart data stays pure — no audio calls baked in. */
enum class SoundId {
    CLICK_NORMAL,
    CLICK_ACCENT,
    STICK,
    KICK,
    RIM,
    HAT,
    BASS_LONG,
    BASS_MED,
    BASS_SHORT,
    UKE_F,
    UKE_C,
    UKE_BB,
    MEL,
    LEAD_SNAP,
    PLAYER_SNAP,
    PERFECT_DING,
    WHIFF,
    STEEL_PAN,
    KEYS,
    SHAKER,
    WHISTLE_MANGO,
    WHISTLE_LIME,
    WHISTLE_PINEAPPLE,
    CHOP,
    SLICE,
    THUD,
}

/** [param] carries a sound-specific extra value (a MIDI note for BASS_*, MEL, STEEL_PAN and KEYS); other sounds leave it 0. */
data class ChartEvent(val beat: Double, val sound: SoundId, val param: Double = 0.0)

object Charts {
    /** A click track with an accent every 4th beat, matching the prototype's calibrate metronome. */
    fun calibrateClickTrack(totalBeats: Int): List<ChartEvent> =
        (0 until totalBeats).map { b ->
            ChartEvent(
                beat = b.toDouble(),
                sound = if (b % 4 == 0) SoundId.CLICK_ACCENT else SoundId.CLICK_NORMAL,
            )
        }

    /** The prototype's top melody line: (beat offset within a 32-beat phrase, MIDI note). */
    private val MELODY: List<Pair<Double, Double>> = listOf(
        0.0 to 77.0, 1.0 to 79.0, 1.5 to 81.0, 2.5 to 79.0, 3.0 to 77.0, 4.5 to 74.0, 5.0 to 77.0, 6.0 to 79.0,
        8.0 to 81.0, 9.0 to 84.0, 9.5 to 81.0, 10.5 to 79.0, 11.0 to 77.0, 12.5 to 79.0, 13.0 to 77.0, 14.0 to 74.0,
        16.0 to 77.0, 17.0 to 79.0, 17.5 to 81.0, 18.5 to 84.0, 19.0 to 86.0, 20.5 to 84.0, 21.0 to 81.0, 22.0 to 79.0,
        24.0 to 77.0, 25.0 to 74.0, 25.5 to 72.0, 26.5 to 74.0, 27.0 to 77.0, 28.0 to 77.0,
    )

    private val ROOTS = listOf(41.0, 36.0, 46.0, 36.0)
    private val UKE_CHORDS = listOf(SoundId.UKE_F, SoundId.UKE_C, SoundId.UKE_BB, SoundId.UKE_C)

    /**
     * Backing band for Snap Crabs: a 4-beat stick count-in, then kick/rim/hat/bass/uke over an
     * F-C-Bb-C progression (roots 41/36/46/36) with the top melody line layered in every 32 beats,
     * matching buildMusic() in the prototype.
     */
    fun snapCrabsBacking(endBeat: Double): List<ChartEvent> = band(endBeat)

    /** Mango Chop's Am-F-C-G progression: (bass root, chord stab voicing) per bar. */
    private val MANGO_CHORDS: List<Pair<Double, List<Double>>> = listOf(
        45.0 to listOf(57.0, 60.0, 64.0),
        41.0 to listOf(57.0, 60.0, 65.0),
        36.0 to listOf(55.0, 60.0, 64.0),
        43.0 to listOf(55.0, 59.0, 62.0),
    )

    /** A syncopated soca bass figure: (beat offset in the bar, semitones above the root, sound). */
    private val MANGO_BASS: List<Triple<Double, Double, SoundId>> = listOf(
        Triple(0.0, 0.0, SoundId.BASS_MED),
        Triple(0.75, 0.0, SoundId.BASS_SHORT),
        Triple(1.5, 12.0, SoundId.BASS_SHORT),
        Triple(2.0, 0.0, SoundId.BASS_MED),
        Triple(2.75, 7.0, SoundId.BASS_SHORT),
        Triple(3.5, 12.0, SoundId.BASS_SHORT),
    )

    /**
     * Mango Chop's steel pan tune over one 32-beat pass of the progression: (beat offset, MIDI note).
     * Kept to 62–74 (about 290–590Hz), under the toss whistles, so it doesn't mask the cues.
     */
    private val MANGO_MELODY: List<Pair<Double, Double>> = listOf(
        0.0 to 64.0, 0.5 to 69.0, 1.0 to 72.0, 1.75 to 71.0, 2.5 to 69.0, 3.0 to 64.0,
        4.0 to 65.0, 4.5 to 69.0, 5.0 to 72.0, 5.75 to 69.0, 6.5 to 67.0, 7.0 to 65.0,
        8.0 to 64.0, 8.5 to 67.0, 9.0 to 72.0, 9.75 to 74.0, 10.5 to 72.0, 11.0 to 67.0,
        12.0 to 62.0, 12.5 to 67.0, 13.0 to 71.0, 13.75 to 69.0, 14.5 to 67.0,
        16.0 to 69.0, 16.75 to 72.0, 17.5 to 74.0, 18.0 to 72.0, 18.5 to 71.0, 19.0 to 69.0,
        20.0 to 72.0, 20.75 to 69.0, 21.5 to 65.0, 22.0 to 69.0, 22.5 to 72.0,
        24.0 to 67.0, 24.75 to 64.0, 25.5 to 67.0, 26.0 to 72.0, 26.5 to 71.0,
        28.0 to 71.0, 28.5 to 69.0, 29.0 to 67.0, 29.5 to 62.0, 30.0 to 64.0,
    )

    /**
     * Mango Chop's own song, a soca groove in A minor at [MANGO_CHOP_BPM] so it doesn't sound
     * like Snap Crabs' ukulele tune: four-on-the-floor kick, rim on 2 and 4, a 16th-note shaker in
     * place of the hats, a syncopated bass, offbeat keyboard stabs instead of the uke, and a steel
     * pan playing the tune. Same 4-beat stick count-in as Snap Crabs.
     */
    fun mangoChopBacking(endBeat: Double): List<ChartEvent> {
        val events = mutableListOf<ChartEvent>()
        for (b in 0 until 4) events += ChartEvent(b.toDouble(), SoundId.STICK)

        var bar = 1
        while (bar * 4 < endBeat) {
            val b = bar * 4.0
            val (root, chord) = MANGO_CHORDS[(bar - 1) % 4]
            for (i in 0 until 4) events += ChartEvent(b + i, SoundId.KICK)
            events += ChartEvent(b + 1, SoundId.RIM)
            events += ChartEvent(b + 3, SoundId.RIM)
            // Every 16th except the downbeats, which the kick already covers.
            for (i in 0 until 16) if (i % 4 != 0) events += ChartEvent(b + i * 0.25, SoundId.SHAKER)
            for ((offset, interval, sound) in MANGO_BASS) events += ChartEvent(b + offset, sound, root + interval)
            for (o in listOf(0.5, 1.5, 2.5, 3.5)) {
                for (note in chord) events += ChartEvent(b + o, SoundId.KEYS, note)
            }
            bar++
        }

        var phrase = 4.0
        while (phrase < endBeat) {
            for ((offset, note) in MANGO_MELODY) {
                if (phrase + offset < endBeat) events += ChartEvent(phrase + offset, SoundId.STEEL_PAN, note)
            }
            phrase += 32.0
        }

        events += ChartEvent(endBeat, SoundId.KICK)
        events += ChartEvent(endBeat, SoundId.BASS_LONG, MANGO_CHORDS[0].first)
        events += ChartEvent(endBeat, SoundId.STEEL_PAN, 69.0)
        return events
    }

    private fun band(endBeat: Double): List<ChartEvent> {
        val events = mutableListOf<ChartEvent>()
        for (b in 0 until 4) events += ChartEvent(b.toDouble(), SoundId.STICK)

        var bar = 1
        while (bar * 4 < endBeat) {
            val b = bar * 4.0
            val chordIndex = (bar - 1) % 4
            val root = ROOTS[chordIndex]
            events += ChartEvent(b, SoundId.KICK)
            events += ChartEvent(b + 2, SoundId.KICK)
            if (bar % 2 == 0) events += ChartEvent(b + 3.5, SoundId.KICK)
            events += ChartEvent(b + 1, SoundId.RIM)
            events += ChartEvent(b + 3, SoundId.RIM)
            for (i in 0 until 8) events += ChartEvent(b + i * 0.5, SoundId.HAT)
            for (o in listOf(0.5, 1.5, 2.5, 3.5)) events += ChartEvent(b + o, UKE_CHORDS[chordIndex])
            events += ChartEvent(b, SoundId.BASS_LONG, root)
            events += ChartEvent(b + 1.5, SoundId.BASS_SHORT, root)
            events += ChartEvent(b + 2, SoundId.BASS_MED, root + 7)
            events += ChartEvent(b + 3, SoundId.BASS_SHORT, root + 12)
            bar++
        }

        var phrase = 4.0
        while (phrase < endBeat) {
            for ((offset, note) in MELODY) {
                if (phrase + offset < endBeat) events += ChartEvent(phrase + offset, SoundId.MEL, note)
            }
            phrase += 32.0
        }

        events += ChartEvent(endBeat, SoundId.KICK)
        events += ChartEvent(endBeat, SoundId.BASS_LONG, ROOTS[0])
        return events
    }
}

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
    WHISTLE_MANGO,
    WHISTLE_LIME,
    WHISTLE_PINEAPPLE,
    CHOP,
    SLICE,
    THUD,
}

/** [param] carries a sound-specific extra value (a MIDI note for BASS_*, MEL and STEEL_PAN); other sounds leave it 0. */
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

    /** Chord tones of F, C, Bb, C in the steel pan's register, below the melody line. */
    private val PAN_CHORDS = listOf(
        listOf(65.0, 69.0, 72.0),
        listOf(64.0, 67.0, 72.0),
        listOf(65.0, 70.0, 74.0),
        listOf(64.0, 67.0, 72.0),
    )

    /** A calypso 3+3+2 figure, doubled over each half bar: (beat offset in the bar, chord tone index). */
    private val PAN_RIFF: List<Pair<Double, Int>> = listOf(
        0.0 to 0, 0.75 to 1, 1.5 to 2, 2.0 to 1, 2.75 to 2, 3.5 to 0,
    )

    /**
     * Backing band for Snap Crabs: a 4-beat stick count-in, then kick/rim/hat/bass/uke over an
     * F-C-Bb-C progression (roots 41/36/46/36) with the top melody line layered in every 32 beats,
     * matching buildMusic() in the prototype.
     */
    fun snapCrabsBacking(endBeat: Double): List<ChartEvent> = band(endBeat)

    /**
     * Backing for Mango Chop: the same band (played faster, at [MANGO_CHOP_BPM]) plus a steel pan
     * picking out a calypso figure on the chord tones under the melody. The pan stays in the
     * 300–600Hz range, below the toss whistles, so it doesn't mask the cues.
     */
    fun mangoChopBacking(endBeat: Double): List<ChartEvent> {
        val events = band(endBeat).toMutableList()
        var bar = 1
        while (bar * 4 < endBeat) {
            val chord = PAN_CHORDS[(bar - 1) % 4]
            for ((offset, tone) in PAN_RIFF) events += ChartEvent(bar * 4.0 + offset, SoundId.STEEL_PAN, chord[tone])
            bar++
        }
        events += ChartEvent(endBeat, SoundId.STEEL_PAN, 77.0)
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

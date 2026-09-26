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
}

/** [param] carries a sound-specific extra value (currently just BASS_*'s MIDI note); unused sounds leave it 0. */
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

    /**
     * Backing band for Snap Crabs: a 4-beat stick count-in, then kick/rim/hat/bass/uke over an
     * F-C-Bb-C progression (roots 41/36/46/36) with the top melody line layered in every 32 beats,
     * matching buildMusic() in the prototype.
     */
    fun snapCrabsBacking(endBeat: Double): List<ChartEvent> {
        val events = mutableListOf<ChartEvent>()
        for (b in 0 until 4) events += ChartEvent(b.toDouble(), SoundId.STICK)

        val roots = listOf(41.0, 36.0, 46.0, 36.0)
        val ukeChords = listOf(SoundId.UKE_F, SoundId.UKE_C, SoundId.UKE_BB, SoundId.UKE_C)
        var bar = 1
        while (bar * 4 < endBeat) {
            val b = bar * 4.0
            val chordIndex = (bar - 1) % 4
            val root = roots[chordIndex]
            events += ChartEvent(b, SoundId.KICK)
            events += ChartEvent(b + 2, SoundId.KICK)
            if (bar % 2 == 0) events += ChartEvent(b + 3.5, SoundId.KICK)
            events += ChartEvent(b + 1, SoundId.RIM)
            events += ChartEvent(b + 3, SoundId.RIM)
            for (i in 0 until 8) events += ChartEvent(b + i * 0.5, SoundId.HAT)
            for (o in listOf(0.5, 1.5, 2.5, 3.5)) events += ChartEvent(b + o, ukeChords[chordIndex])
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
        events += ChartEvent(endBeat, SoundId.BASS_LONG, roots[0])
        return events
    }
}

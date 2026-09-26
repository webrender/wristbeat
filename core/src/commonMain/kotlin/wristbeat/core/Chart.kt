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

    /**
     * Backing band for Snap Crabs: a 4-beat stick count-in, then a kick/rim/hat/bass groove over an
     * F-C-Bb-C progression (roots 41/36/46/36), matching buildMusic() in the prototype. Uke and the
     * top melody line are left out this iteration — the groove alone is enough to judge feel against.
     */
    fun snapCrabsBacking(endBeat: Double): List<ChartEvent> {
        val events = mutableListOf<ChartEvent>()
        for (b in 0 until 4) events += ChartEvent(b.toDouble(), SoundId.STICK)

        val roots = listOf(41.0, 36.0, 46.0, 36.0)
        var bar = 1
        while (bar * 4 < endBeat) {
            val b = bar * 4.0
            val root = roots[(bar - 1) % 4]
            events += ChartEvent(b, SoundId.KICK)
            events += ChartEvent(b + 2, SoundId.KICK)
            if (bar % 2 == 0) events += ChartEvent(b + 3.5, SoundId.KICK)
            events += ChartEvent(b + 1, SoundId.RIM)
            events += ChartEvent(b + 3, SoundId.RIM)
            for (i in 0 until 8) events += ChartEvent(b + i * 0.5, SoundId.HAT)
            events += ChartEvent(b, SoundId.BASS_LONG, root)
            events += ChartEvent(b + 1.5, SoundId.BASS_SHORT, root)
            events += ChartEvent(b + 2, SoundId.BASS_MED, root + 7)
            events += ChartEvent(b + 3, SoundId.BASS_SHORT, root + 12)
            bar++
        }
        events += ChartEvent(endBeat, SoundId.KICK)
        events += ChartEvent(endBeat, SoundId.BASS_LONG, roots[0])
        return events
    }
}

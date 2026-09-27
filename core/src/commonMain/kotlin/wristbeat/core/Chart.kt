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
    CRASH,
    TOM,
    LEAD_BONGO_HI,
    LEAD_BONGO_LO,
    PLAYER_BONGO_HI,
    PLAYER_BONGO_LO,
    CHEER,
    BOO,
    MARIMBA,
    PAN_FLUTE,
    PAD,
    CLAVE,
}

/**
 * [param] carries a sound-specific extra value (a MIDI note for BASS_*, MEL, STEEL_PAN, KEYS, TOM,
 * MARIMBA, PAN_FLUTE and PAD); other sounds leave it 0.
 */
data class ChartEvent(val beat: Double, val sound: SoundId, val param: Double = 0.0)

/**
 * Snap Crabs' song form, in beats from the start of the run. The intro's last bar is the stick
 * count-in; the first lead crab call lands on [VERSE].
 */
object SnapCrabsSong {
    const val VERSE = 12.0
    const val CHORUS = 44.0
    const val OUTRO = 76.0
    const val FINAL = 84.0

    /** A few beats after [FINAL], so the last chord rings out before the results come up. */
    const val END = 87.0
}

/**
 * Mango Chop's song form, in [MANGO_CHOP_BPM] beats from the start of the run. The intro's last
 * bar is the stick count-in; the first toss lands on [VERSE].
 */
object MangoChopSong {
    const val VERSE = 12.0
    const val CHORUS = 28.0
    const val BRIDGE = 44.0
    const val FINAL_CHORUS = 60.0
    const val OUTRO = 92.0

    /** A few beats after [OUTRO]'s final hit, so it rings out before the results come up. */
    const val END = 96.0
}

/**
 * Bongo Blitz's song form, in [BONGO_BLITZ_BPM] beats from the start of the run. The intro's last
 * bar is the stick count-in; the first call lands on [VERSE]. Each of [VERSE], [CHORUS] and
 * [BRIDGE] holds four 8-beat call-and-response pairs (see `BONGO_PATTERNS` in `BongoBlitzStage.kt`),
 * so the section boundaries below are exactly 32 beats apart.
 */
object BongoBlitzSong {
    const val VERSE = 12.0
    const val CHORUS = 44.0
    const val BRIDGE = 76.0
    const val OUTRO = 108.0

    /** A few beats after [OUTRO]'s final hit, so it rings out before the results come up. */
    const val END = 112.0
}

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
     * Snap Crabs' verse answer riff, one lick per bar of the progression: (beat offset, MIDI note).
     * Every call-and-response pattern sits in beats 0–2 of its bar, so the riff lives in 2.5–3.5,
     * answering the snaps instead of talking over them.
     */
    private val VERSE_RIFF: List<List<Pair<Double, Double>>> = listOf(
        listOf(3.0 to 69.0, 3.5 to 72.0),
        listOf(3.0 to 67.0, 3.5 to 64.0),
        listOf(3.0 to 65.0, 3.5 to 70.0),
        listOf(2.5 to 72.0, 3.0 to 70.0, 3.5 to 67.0),
    )

    private val ROOTS = listOf(41.0, 36.0, 46.0, 36.0)
    private val UKE_CHORDS = listOf(SoundId.UKE_F, SoundId.UKE_C, SoundId.UKE_BB, SoundId.UKE_C)

    /**
     * Snap Crabs' song, a ukulele band over F-C-Bb-C (roots 41/36/46/36) with its sections laid out
     * by [SnapCrabsSong]:
     * - **Intro** — uke, bass and offbeat hats with a preview of the chorus hook, then a bar of
     *   stick count-in under held chords.
     * - **Verse** — the easy call-and-response patterns over a light kick; its second half adds an
     *   answer riff between the snaps, and it ends on a tom fill.
     * - **Chorus** — a crash, a busier kick, and the prototype's melody line on top.
     * - **Outro** — stop-time hits, a Bb–C climb, and a final F chord with a crash that rings out.
     */
    fun snapCrabsBacking(): List<ChartEvent> {
        val events = mutableListOf<ChartEvent>()
        val verse = SnapCrabsSong.VERSE
        val chorus = SnapCrabsSong.CHORUS
        val outro = SnapCrabsSong.OUTRO

        // Intro: two bars (F, C) with the hook's opening, then the count-in bar over Bb and C.
        for (bar in 0 until 2) {
            val b = bar * 4.0
            for (o in listOf(0.5, 1.5, 2.5, 3.5)) {
                events += ChartEvent(b + o, UKE_CHORDS[bar])
                events += ChartEvent(b + o, SoundId.HAT)
            }
            events += ChartEvent(b, SoundId.BASS_LONG, ROOTS[bar])
            events += ChartEvent(b + 2, SoundId.BASS_LONG, ROOTS[bar])
        }
        for ((offset, note) in MELODY) if (offset < 8.0) events += ChartEvent(offset, SoundId.MEL, note)
        for (b in 8 until 12) events += ChartEvent(b.toDouble(), SoundId.STICK)
        events += ChartEvent(8.0, SoundId.UKE_BB)
        events += ChartEvent(8.0, SoundId.BASS_LONG, ROOTS[2])
        events += ChartEvent(10.0, SoundId.UKE_C)
        events += ChartEvent(10.0, SoundId.BASS_LONG, ROOTS[3])

        // Verse and chorus: one bar of the band per 4 beats.
        var b = verse
        while (b < outro) {
            val chordIndex = (((b - verse) / 4).toInt()) % 4
            val root = ROOTS[chordIndex]
            val inChorus = b >= chorus
            events += ChartEvent(b, SoundId.KICK)
            events += ChartEvent(b + 2, SoundId.KICK)
            if (inChorus) {
                events += ChartEvent(b + 1.5, SoundId.KICK)
                events += ChartEvent(b + 3.5, SoundId.KICK)
                events += ChartEvent(b, UKE_CHORDS[chordIndex])
            }
            events += ChartEvent(b + 1, SoundId.RIM)
            events += ChartEvent(b + 3, SoundId.RIM)
            for (i in 0 until 8) events += ChartEvent(b + i * 0.5, SoundId.HAT)
            if (inChorus) events += ChartEvent(b + 3.75, SoundId.HAT)
            for (o in listOf(0.5, 1.5, 2.5, 3.5)) events += ChartEvent(b + o, UKE_CHORDS[chordIndex])
            events += ChartEvent(b, SoundId.BASS_LONG, root)
            events += ChartEvent(b + 1.5, SoundId.BASS_SHORT, root)
            events += ChartEvent(b + 2, SoundId.BASS_MED, root + 7)
            events += ChartEvent(b + 3, SoundId.BASS_SHORT, root + 12)
            val lastVerseBar = b + 4 == chorus
            if (!inChorus && b >= verse + 16 && !lastVerseBar) {
                for ((offset, note) in VERSE_RIFF[chordIndex]) events += ChartEvent(b + offset, SoundId.MEL, note)
            }
            if (lastVerseBar) events += tomFill(b + 3)
            b += 4
        }
        events += ChartEvent(chorus, SoundId.CRASH)
        for ((offset, note) in MELODY) events += ChartEvent(chorus + offset, SoundId.MEL, note)

        // Outro: stop-time hits on F (the 3-3-2 figure) under steady hats, then Bb, C and home.
        events += ChartEvent(outro, SoundId.CRASH)
        for (o in listOf(0.0, 1.5, 3.0)) {
            events += ChartEvent(outro + o, SoundId.KICK)
            events += ChartEvent(outro + o, SoundId.UKE_F)
            events += ChartEvent(outro + o, if (o == 0.0) SoundId.BASS_LONG else SoundId.BASS_SHORT, ROOTS[0])
        }
        events += ChartEvent(outro, SoundId.MEL, 77.0)
        for (i in 0 until 14) events += ChartEvent(outro + i * 0.5, SoundId.HAT)
        events += ChartEvent(outro + 4, SoundId.UKE_BB)
        events += ChartEvent(outro + 5, SoundId.UKE_BB)
        events += ChartEvent(outro + 4, SoundId.BASS_LONG, ROOTS[2])
        events += ChartEvent(outro + 6, SoundId.UKE_C)
        events += ChartEvent(outro + 6, SoundId.BASS_LONG, ROOTS[3])
        events += ChartEvent(outro + 4, SoundId.KICK)
        events += ChartEvent(outro + 6, SoundId.KICK)
        for ((o, note) in listOf(4.0 to 72.0, 5.0 to 74.0, 6.0 to 76.0)) events += ChartEvent(outro + o, SoundId.MEL, note)
        events += tomFill(outro + 7)
        events += finalHit(SnapCrabsSong.FINAL, ROOTS[0]) +
            ChartEvent(SnapCrabsSong.FINAL, SoundId.UKE_F) +
            listOf(77.0, 81.0, 84.0).map { ChartEvent(SnapCrabsSong.FINAL, SoundId.MEL, it) }
        return events.sortedBy { it.beat }
    }

    /** Four descending 16th-note toms across one beat, leading into the next section. */
    private fun tomFill(beat: Double): List<ChartEvent> =
        listOf(50.0, 47.0, 43.0, 40.0).mapIndexed { i, note -> ChartEvent(beat + i * 0.25, SoundId.TOM, note) }

    /** The last downbeat of a song: kick, crash and a long bass note on [root]. */
    private fun finalHit(beat: Double, root: Double): List<ChartEvent> = listOf(
        ChartEvent(beat, SoundId.KICK),
        ChartEvent(beat, SoundId.CRASH),
        ChartEvent(beat, SoundId.BASS_LONG, root),
    )

    /** Mango Chop's Am-F-C-G progression: (bass root, chord stab voicing) per bar. */
    private val MANGO_CHORDS: List<Pair<Double, List<Double>>> = listOf(
        45.0 to listOf(57.0, 60.0, 64.0),
        41.0 to listOf(57.0, 60.0, 65.0),
        36.0 to listOf(55.0, 60.0, 64.0),
        43.0 to listOf(55.0, 59.0, 62.0),
    )

    /** The bridge's F-G-Em-Am, a change of colour from the verse's loop that turns back home to Am. */
    private val MANGO_BRIDGE_CHORDS: List<Pair<Double, List<Double>>> = listOf(
        41.0 to listOf(57.0, 60.0, 65.0),
        43.0 to listOf(55.0, 59.0, 62.0),
        40.0 to listOf(55.0, 59.0, 64.0),
        45.0 to listOf(57.0, 60.0, 64.0),
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

    /** The bridge's slower, sparser pan line over [MANGO_BRIDGE_CHORDS], same 62–74 range. */
    private val MANGO_BRIDGE_MELODY: List<Pair<Double, Double>> = listOf(
        0.0 to 69.0, 1.5 to 72.0, 3.0 to 69.0,
        4.0 to 71.0, 5.5 to 74.0, 7.0 to 71.0,
        8.0 to 67.0, 9.5 to 71.0, 11.0 to 64.0,
        12.0 to 69.0, 13.0 to 72.0, 14.0 to 71.0, 14.5 to 67.0,
    )

    /**
     * Mango Chop's own song, a soca tune in A minor at [MANGO_CHOP_BPM] so it doesn't sound like
     * Snap Crabs' ukulele band: kick, rim, a 16th-note shaker, a syncopated bass, offbeat keyboard
     * stabs and a steel pan on the tune. Its sections, laid out by [MangoChopSong], line up with
     * the stage's fruit so each new fruit arrives with new music:
     * - **Intro** — keys, shaker and bass with a preview of the pan tune, then a bar of sticks over a
     *   four-on-the-floor kick to count in.
     * - **Verse** (mangoes) — the groove without the pan, so the first whistles are easy to hear.
     * - **Chorus** (limes) — a crash, and the pan comes in with the first half of the tune.
     * - **Bridge** (pineapples) — a new progression at half-time with its own pan line, building
     *   back up with a rim roll.
     * - **Final chorus** (everything) — the full tune, with a crash halfway through.
     * - **Outro** — a final Am hit with a crash and a rising pan flourish.
     */
    fun mangoChopBacking(): List<ChartEvent> {
        val events = mutableListOf<ChartEvent>()
        val verse = MangoChopSong.VERSE
        val chorus = MangoChopSong.CHORUS
        val bridge = MangoChopSong.BRIDGE
        val finalChorus = MangoChopSong.FINAL_CHORUS
        val outro = MangoChopSong.OUTRO

        // Intro: two bars (Am, F) of keys, shaker and bass under the tune's opening.
        for (bar in 0 until 2) {
            val b = bar * 4.0
            val (root, chord) = MANGO_CHORDS[bar]
            for (o in listOf(0.5, 1.5, 2.5, 3.5)) for (note in chord) events += ChartEvent(b + o, SoundId.KEYS, note)
            for (i in 0 until 8) events += ChartEvent(b + i * 0.5, SoundId.SHAKER)
            events += ChartEvent(b, SoundId.BASS_LONG, root)
            events += ChartEvent(b + 2, SoundId.BASS_LONG, root)
        }
        for ((offset, note) in MANGO_MELODY) if (offset < 8.0) events += ChartEvent(offset, SoundId.STEEL_PAN, note)
        // Count-in bar: sticks over a four-on-the-floor kick and the G chord, pulling into the verse.
        for (i in 8 until 12) {
            events += ChartEvent(i.toDouble(), SoundId.STICK)
            events += ChartEvent(i.toDouble(), SoundId.KICK)
            for (note in MANGO_CHORDS[3].second) events += ChartEvent(i + 0.5, SoundId.KEYS, note)
        }
        events += ChartEvent(8.0, SoundId.BASS_LONG, MANGO_CHORDS[3].first)

        var b = verse
        while (b < outro) {
            val inBridge = b >= bridge && b < finalChorus
            val sectionStart = if (inBridge) bridge else verse
            val chords = if (inBridge) MANGO_BRIDGE_CHORDS else MANGO_CHORDS
            val (root, chord) = chords[(((b - sectionStart) / 4).toInt()) % 4]
            val lastBridgeBar = b + 4 == finalChorus
            if (inBridge) {
                // Half-time: kick on 1 and 3, a rim on 4, 8th-note shaker, stabs on the beat.
                events += ChartEvent(b, SoundId.KICK)
                events += ChartEvent(b + 2, SoundId.KICK)
                if (lastBridgeBar) {
                    events += ChartEvent(b + 1, SoundId.KICK)
                    for (i in 0 until 4) events += ChartEvent(b + 3 + i * 0.25, SoundId.RIM)
                } else {
                    events += ChartEvent(b + 3, SoundId.RIM)
                }
                for (i in 0 until 8) events += ChartEvent(b + i * 0.5, SoundId.SHAKER)
                for (o in 0 until 4) for (note in chord) events += ChartEvent(b + o, SoundId.KEYS, note)
                events += ChartEvent(b, SoundId.BASS_LONG, root)
                events += ChartEvent(b + 2.5, SoundId.BASS_MED, root)
            } else {
                for (i in 0 until 4) events += ChartEvent(b + i, SoundId.KICK)
                events += ChartEvent(b + 1, SoundId.RIM)
                events += ChartEvent(b + 3, SoundId.RIM)
                // Every 16th except the downbeats, which the kick already covers.
                for (i in 0 until 16) if (i % 4 != 0) events += ChartEvent(b + i * 0.25, SoundId.SHAKER)
                for ((offset, interval, sound) in MANGO_BASS) events += ChartEvent(b + offset, sound, root + interval)
                for (o in listOf(0.5, 1.5, 2.5, 3.5)) for (note in chord) events += ChartEvent(b + o, SoundId.KEYS, note)
            }
            b += 4
        }
        for (crash in listOf(chorus, bridge, finalChorus, finalChorus + 16)) events += ChartEvent(crash, SoundId.CRASH)
        for ((offset, note) in MANGO_MELODY) {
            if (offset < 16.0) events += ChartEvent(chorus + offset, SoundId.STEEL_PAN, note)
            events += ChartEvent(finalChorus + offset, SoundId.STEEL_PAN, note)
        }
        for ((offset, note) in MANGO_BRIDGE_MELODY) events += ChartEvent(bridge + offset, SoundId.STEEL_PAN, note)

        // Outro: the final Am with a rising pan flourish.
        events += finalHit(outro, MANGO_CHORDS[0].first)
        for (note in MANGO_CHORDS[0].second) events += ChartEvent(outro, SoundId.KEYS, note)
        for ((i, note) in listOf(64.0, 69.0, 72.0).withIndex()) events += ChartEvent(outro + i * 0.25, SoundId.STEEL_PAN, note)
        return events.sortedBy { it.beat }
    }

    /** One bar of a Bongo Blitz progression: the bass root and a pad/marimba voicing. */
    private class BongoChord(val root: Double, val voicing: List<Double>)

    private val B_DM = BongoChord(38.0, listOf(62.0, 65.0, 69.0))
    private val B_BB = BongoChord(34.0, listOf(62.0, 65.0, 70.0))
    private val B_C = BongoChord(36.0, listOf(60.0, 64.0, 67.0))
    private val B_GM = BongoChord(43.0, listOf(62.0, 67.0, 70.0))
    private val B_A = BongoChord(33.0, listOf(61.0, 64.0, 69.0))
    private val B_EB = BongoChord(39.0, listOf(63.0, 67.0, 70.0))
    private val B_F = BongoChord(41.0, listOf(60.0, 65.0, 69.0))

    /** One chord per bar, eight bars (four call-and-response pairs) per section. */
    private val BONGO_VERSE_CHORDS = listOf(B_DM, B_DM, B_BB, B_C, B_DM, B_DM, B_GM, B_A)
    private val BONGO_CHORUS_CHORDS = listOf(B_BB, B_C, B_DM, B_DM, B_BB, B_C, B_GM, B_A)
    private val BONGO_BRIDGE_CHORDS = listOf(B_GM, B_GM, B_EB, B_EB, B_F, B_F, B_A, B_A)

    /**
     * The chorus's pan flute hook over [BONGO_CHORUS_CHORDS]: (beat offset in the section, MIDI note).
     * Each call bar gets a single note on its downbeat — where every call starts anyway, so it never
     * blurs the rhythm the player has to memorize — and the tune answers in the response bar.
     */
    private val BONGO_CHORUS_HOOK: List<Pair<Double, Double>> = listOf(
        0.0 to 74.0, 4.0 to 76.0, 4.5 to 77.0, 5.0 to 79.0, 6.0 to 77.0, 6.5 to 76.0, 7.0 to 72.0,
        8.0 to 74.0, 12.0 to 77.0, 13.0 to 76.0, 13.5 to 74.0, 14.0 to 72.0, 14.5 to 74.0, 15.5 to 69.0,
        16.0 to 74.0, 20.0 to 76.0, 20.5 to 77.0, 21.0 to 79.0, 22.0 to 81.0, 22.5 to 79.0, 23.0 to 77.0,
        24.0 to 79.0, 28.0 to 76.0, 28.5 to 73.0, 29.0 to 76.0, 30.0 to 81.0, 31.0 to 73.0,
    )

    /** The bridge's climbing line over [BONGO_BRIDGE_CHORDS], same call-bar/response-bar layout as the hook. */
    private val BONGO_BRIDGE_LINE: List<Pair<Double, Double>> = listOf(
        0.0 to 79.0, 4.0 to 74.0, 4.5 to 77.0, 5.0 to 79.0, 5.5 to 77.0, 6.0 to 74.0, 7.0 to 70.0,
        8.0 to 79.0, 12.0 to 75.0, 12.5 to 79.0, 13.0 to 82.0, 14.0 to 79.0, 14.5 to 75.0, 15.0 to 74.0,
        16.0 to 81.0, 20.0 to 77.0, 20.5 to 81.0, 21.0 to 84.0, 22.0 to 81.0, 22.5 to 77.0, 23.0 to 76.0,
        24.0 to 81.0, 28.0 to 73.0, 28.5 to 76.0, 29.0 to 79.0, 30.0 to 81.0, 30.5 to 79.0, 31.0 to 76.0, 31.5 to 73.0,
    )

    /** A 3-3-2 marimba figure through one bar of [chord]: root, fifth, octave, fifth, third, root. */
    private fun marimbaBar(beat: Double, chord: BongoChord): List<ChartEvent> {
        // The chord's root in the marimba's 60–71 octave, with a minor or major third from the voicing.
        val root = 60.0 + (chord.root.toInt() % 12)
        val pitchClasses = chord.voicing.map { it.toInt() % 12 }
        val third = if ((root.toInt() + 3) % 12 in pitchClasses) root + 3 else root + 4
        val fifth = root + 7
        return listOf(0.0 to root, 1.0 to fifth, 1.5 to root + 12, 2.5 to fifth, 3.0 to third, 3.5 to root)
            .map { (o, note) -> ChartEvent(beat + o, SoundId.MARIMBA, note) }
    }

    /**
     * Bongo Blitz's own song, a D minor jungle-exotica tune at [BONGO_BLITZ_BPM]: marimba, a breathy
     * pan flute, a soft pad, a walking bass and a kit with shaker and clave, laid out by
     * [BongoBlitzSong]. Its arrangement follows the call-and-response itself: in each **call bar**
     * the band holds back to a steady on-the-beat pulse, a pad chord and one downbeat note, so the
     * monkey's rhythm is the only syncopation to hear; in each **response bar** the band answers
     * along with the player — the melody, bass runs and fills all live there.
     * - **Intro** — the chorus hook on marimba over the pad, then a bar of sticks over a
     *   four-on-the-floor kick to count the fast tempo in.
     * - **Verse** (tap-only calls) — kit, bass and pad, with a 3-3-2 marimba figure answering in each
     *   response bar; a tom fill leads into the chorus.
     * - **Chorus** (the low drum joins) — a crash, offbeat hats and the pan flute hook.
     * - **Bridge** (the densest calls) — a new Gm–Eb–F–A progression over four-on-the-floor, the
     *   flute and marimba doubling a climbing line, clave and a rim-roll pickup into every response.
     * - **Outro** — a final D minor hit with a crash, pad and a flute run up to the top D.
     */
    fun bongoBlitzBacking(): List<ChartEvent> {
        val events = mutableListOf<ChartEvent>()
        val verse = BongoBlitzSong.VERSE
        val chorus = BongoBlitzSong.CHORUS
        val bridge = BongoBlitzSong.BRIDGE
        val outro = BongoBlitzSong.OUTRO

        // Intro: two bars of the hook's opening on marimba over pad, bass and shaker.
        for ((bar, chord) in listOf(B_BB, B_C).withIndex()) {
            val b = bar * 4.0
            for (note in chord.voicing) events += ChartEvent(b, SoundId.PAD, note)
            events += ChartEvent(b, SoundId.BASS_LONG, chord.root)
            events += ChartEvent(b + 2, SoundId.BASS_MED, chord.root + 7)
            for (i in 0 until 8) events += ChartEvent(b + i * 0.5, SoundId.SHAKER)
        }
        for ((offset, note) in BONGO_CHORUS_HOOK) if (offset < 8.0) events += ChartEvent(offset, SoundId.MARIMBA, note - 12)
        // Count-in bar: sticks over a four-on-the-floor kick on the A chord, pulling into the verse.
        for (i in 8 until 12) {
            events += ChartEvent(i.toDouble(), SoundId.STICK)
            events += ChartEvent(i.toDouble(), SoundId.KICK)
        }
        for (note in B_A.voicing) events += ChartEvent(8.0, SoundId.PAD, note)
        events += ChartEvent(8.0, SoundId.BASS_LONG, B_A.root)
        events += ChartEvent(10.0, SoundId.BASS_LONG, B_A.root + 12)

        // Verse, chorus and bridge: one bar per chord, alternating call and response bars.
        var b = verse
        while (b < outro) {
            val inBridge = b >= bridge
            val inChorus = b >= chorus && !inBridge
            val sectionStart = when {
                inBridge -> bridge
                inChorus -> chorus
                else -> verse
            }
            val barInSection = ((b - sectionStart) / 4).toInt()
            val chords = when {
                inBridge -> BONGO_BRIDGE_CHORDS
                inChorus -> BONGO_CHORUS_CHORDS
                else -> BONGO_VERSE_CHORDS
            }
            val chord = chords[barInSection % chords.size]
            val responseBar = barInSection % 2 == 1
            val lastBarOfSection = b + 4 == chorus || b + 4 == bridge

            // Kit: a steady pulse — kick on 1 and 3 (every beat in the bridge), rim backbeat, 8th
            // shaker — with a pushed kick on the "and" of 4 in response bars once the chorus starts.
            val kicks = if (inBridge) listOf(0.0, 1.0, 2.0, 3.0) else listOf(0.0, 2.0)
            for (o in kicks) events += ChartEvent(b + o, SoundId.KICK)
            if (responseBar && !inBridge && inChorus) events += ChartEvent(b + 3.5, SoundId.KICK)
            events += ChartEvent(b + 1, SoundId.RIM)
            if (!lastBarOfSection) events += ChartEvent(b + 3, SoundId.RIM)
            for (i in 0 until 8) events += ChartEvent(b + i * 0.5, SoundId.SHAKER)
            if (inChorus || inBridge) for (o in listOf(0.5, 1.5, 2.5, 3.5)) events += ChartEvent(b + o, SoundId.HAT)

            // Pad on every bar; the bass holds in call bars and walks in response bars.
            for (note in chord.voicing) events += ChartEvent(b, SoundId.PAD, note)
            if (responseBar) {
                events += ChartEvent(b, SoundId.BASS_MED, chord.root)
                events += ChartEvent(b + 1.5, SoundId.BASS_SHORT, chord.root + 12)
                events += ChartEvent(b + 2, SoundId.BASS_SHORT, chord.root + 7)
                events += ChartEvent(b + 3, SoundId.BASS_SHORT, chord.root + 10)
                events += ChartEvent(b + 3.5, SoundId.BASS_SHORT, chord.root + 12)
            } else {
                events += ChartEvent(b, SoundId.BASS_LONG, chord.root)
                events += ChartEvent(b + 2, SoundId.BASS_LONG, chord.root)
            }

            if (responseBar) {
                when {
                    inBridge -> {
                        for (o in listOf(0.0, 1.5, 3.0)) events += ChartEvent(b + o, SoundId.CLAVE)
                    }
                    !inChorus && !lastBarOfSection -> events += marimbaBar(b, chord)
                    else -> Unit
                }
            } else if (inBridge) {
                // A 32nd-note rim-roll pickup into the response bar, raising the tension under the
                // hardest calls — after the call's last possible note at 3.5, so it never blurs it.
                for (i in 1 until 4) events += ChartEvent(b + 3.5 + i * 0.125, SoundId.RIM)
            }
            if (lastBarOfSection) events += tomFill(b + 3)
            b += 4
        }
        for (crash in listOf(chorus, bridge)) events += ChartEvent(crash, SoundId.CRASH)
        for ((offset, note) in BONGO_CHORUS_HOOK) events += ChartEvent(chorus + offset, SoundId.PAN_FLUTE, note)
        for ((offset, note) in BONGO_BRIDGE_LINE) {
            events += ChartEvent(bridge + offset, SoundId.PAN_FLUTE, note)
            events += ChartEvent(bridge + offset, SoundId.MARIMBA, note - 12)
        }

        // Outro: the final D minor hit, with the pad ringing under a flute run up to the top D.
        events += finalHit(outro, B_DM.root)
        for (note in B_DM.voicing) events += ChartEvent(outro, SoundId.PAD, note)
        for ((i, note) in listOf(69.0, 72.0, 74.0, 77.0, 81.0, 86.0).withIndex()) {
            events += ChartEvent(outro + i * 0.25, SoundId.PAN_FLUTE, note)
        }
        events += ChartEvent(outro, SoundId.MARIMBA, 62.0)
        events += ChartEvent(outro, SoundId.MARIMBA, 74.0)
        return events.sortedBy { it.beat }
    }
}

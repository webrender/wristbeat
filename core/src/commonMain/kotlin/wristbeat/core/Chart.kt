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
}

/** [param] carries a sound-specific extra value (a MIDI note for BASS_*, MEL, STEEL_PAN, KEYS and TOM); other sounds leave it 0. */
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

    /** An 8-beat marimba riff, one per call-and-response pair, kept simple so it never competes with the calls. */
    private val BONGO_RIFF: List<Pair<Double, Double>> = listOf(
        0.0 to 62.0, 1.0 to 65.0, 1.5 to 67.0, 2.5 to 65.0, 3.0 to 62.0,
        4.0 to 60.0, 4.5 to 62.0, 5.5 to 65.0, 6.0 to 67.0, 7.0 to 70.0,
    )

    /** Low roots under the groove, cycling every four pairs (32 beats, one section). */
    private val BONGO_ROOTS = listOf(38.0, 41.0, 43.0, 38.0)

    /**
     * Bongo Blitz's own backing: a lean jungle percussion groove (kick, rim, shaker, a low bass
     * thump) that stays out of the way of the monkey's calls, laid out by [BongoBlitzSong]:
     * - **Intro** — the riff over a droning root and a light shaker, then a bar of sticks over a
     *   four-on-the-floor kick to count the fast tempo in.
     * - **Verse** — just kick, rim and shaker under the tap-only calls, so the fast tempo settles in
     *   before anything else is added.
     * - **Chorus** — an off-beat hat and the riff return once the low drum joins the calls.
     * - **Bridge** — a 32nd-note rim-roll pickup into every response bar, building tension under the
     *   densest patterns.
     * - **Outro** — a final low hit with a crash and a rising riff flourish.
     */
    fun bongoBlitzBacking(): List<ChartEvent> {
        val events = mutableListOf<ChartEvent>()
        val verse = BongoBlitzSong.VERSE
        val chorus = BongoBlitzSong.CHORUS
        val bridge = BongoBlitzSong.BRIDGE
        val outro = BongoBlitzSong.OUTRO

        // Intro: two bars of the riff over a droning root and a light shaker, then a bar of sticks
        // over a four-on-the-floor kick to count the fast tempo in.
        for (i in 0 until 16) if (i % 2 == 0) events += ChartEvent(i * 0.5, SoundId.SHAKER)
        for ((offset, note) in BONGO_RIFF) events += ChartEvent(offset, SoundId.KEYS, note)
        events += ChartEvent(0.0, SoundId.BASS_LONG, BONGO_ROOTS[0])
        events += ChartEvent(4.0, SoundId.BASS_LONG, BONGO_ROOTS[1])
        for (i in 8 until 12) {
            events += ChartEvent(i.toDouble(), SoundId.STICK)
            events += ChartEvent(i.toDouble(), SoundId.KICK)
        }

        // Verse, chorus and bridge: a two-bar (8-beat) groove under each call-and-response pair.
        var b = verse
        var pairIndex = 0
        while (b < outro) {
            val inBridge = b >= bridge
            val inChorus = b >= chorus && !inBridge
            val root = BONGO_ROOTS[pairIndex % BONGO_ROOTS.size]
            events += ChartEvent(b, SoundId.KICK)
            events += ChartEvent(b + 4, SoundId.KICK)
            events += ChartEvent(b + 2, SoundId.RIM)
            events += ChartEvent(b + 6, SoundId.RIM)
            for (i in 0 until 16) if (i % 2 == 1) events += ChartEvent(b + i * 0.5, SoundId.SHAKER)
            if (inChorus || inBridge) {
                for (i in 0 until 16) if (i % 4 == 3) events += ChartEvent(b + i * 0.5, SoundId.HAT)
                for ((offset, note) in BONGO_RIFF) events += ChartEvent(b + offset, SoundId.KEYS, note)
            }
            events += ChartEvent(b, SoundId.BASS_MED, root)
            events += ChartEvent(b + 4, SoundId.BASS_MED, root)
            if (inBridge) {
                // A rim-roll pickup into the response bar, raising the tension under the hardest calls.
                for (i in 0 until 4) events += ChartEvent(b + 3.5 + i * 0.125, SoundId.RIM)
            }
            b += 8
            pairIndex++
        }
        events += ChartEvent(chorus, SoundId.CRASH)
        events += ChartEvent(bridge, SoundId.CRASH)

        // Outro: the final low hit with a crash, and a rising riff flourish.
        events += finalHit(outro, BONGO_ROOTS[0])
        for ((i, note) in listOf(62.0, 67.0, 70.0, 74.0).withIndex()) events += ChartEvent(outro + i * 0.25, SoundId.KEYS, note)
        return events.sortedBy { it.beat }
    }
}

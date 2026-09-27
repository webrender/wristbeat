package wristbeat.core

import kotlin.math.abs

/** The player's two inputs on the bongo: a tap hits the high drum, a swipe hits the low drum. */
enum class DrumAction { TAP, SWIPE }

/** One note in a call or response pattern: [offset] beats into its bar, on the high or low drum. */
data class BongoNote(val offset: Double, val action: DrumAction)

/** A call or response note placed on the song timeline. */
data class BongoTarget(val beat: Double, val action: DrumAction)

data class BongoOutcome(val grade: Grade?, val errorMs: Double?)

/**
 * The monkey's call-and-response patterns, in order, each note on the high (tap) or low (swipe)
 * drum. The first four (the verse) are tap-only, to introduce the pattern-memory task on its own;
 * the chorus mixes in the low drum; the bridge asks for longer, faster runs of eighth-note taps and
 * more syncopation than either other section — the hardest material in the game.
 *
 * No two notes ever sit closer than half a beat (an eighth note) apart — even that is a fast,
 * deliberate click or tap repeated many times over a run, so nothing here asks for anything tighter.
 * A swipe additionally takes real time to execute (drag the pointer clear of the tap distance, then
 * lift and re-touch for whatever comes next) in a way a tap doesn't, so every swipe sits at least a
 * full beat away from the note before and after it in the same pattern, and never more than one
 * appears per pattern — `everySwipeLeavesEnoughTimeToRecoverBeforeTheNextNote` in
 * `BongoBlitzStageTest` checks this holds for the actual response timeline. The difficulty ramp
 * comes from tap speed and syncopation, which a player can sustain much faster than a swipe.
 */
private val BONGO_PATTERNS: List<List<BongoNote>> = listOf(
    // Verse: tap only, settling the player into the fast tempo before anything else is added.
    listOf(0.0, 1.0, 2.0).map { BongoNote(it, DrumAction.TAP) },
    listOf(0.0, 0.5, 1.0, 1.5).map { BongoNote(it, DrumAction.TAP) },
    listOf(0.0, 1.0, 1.5, 2.5).map { BongoNote(it, DrumAction.TAP) },
    listOf(0.0, 0.5, 1.0, 2.0, 2.5).map { BongoNote(it, DrumAction.TAP) },
    // Chorus: the low drum joins in, always with a full beat of breathing room on either side, while
    // the tap runs between swipes grow by one eighth note each pattern.
    listOf(BongoNote(0.0, DrumAction.TAP), BongoNote(1.0, DrumAction.SWIPE), BongoNote(2.0, DrumAction.TAP)),
    listOf(
        BongoNote(0.0, DrumAction.TAP), BongoNote(0.5, DrumAction.TAP),
        BongoNote(1.5, DrumAction.SWIPE), BongoNote(2.5, DrumAction.TAP),
    ),
    listOf(
        BongoNote(0.0, DrumAction.TAP), BongoNote(0.5, DrumAction.TAP), BongoNote(1.0, DrumAction.TAP),
        BongoNote(2.0, DrumAction.SWIPE), BongoNote(3.0, DrumAction.TAP),
    ),
    listOf(
        BongoNote(0.0, DrumAction.TAP), BongoNote(0.5, DrumAction.TAP), BongoNote(1.0, DrumAction.TAP),
        BongoNote(1.5, DrumAction.TAP), BongoNote(2.5, DrumAction.SWIPE), BongoNote(3.5, DrumAction.TAP),
    ),
    // Bridge: longer eighth-note runs and syncopation (skipped beats) push the difficulty further,
    // but still just one well-spaced swipe per pattern, same as the chorus.
    listOf(
        BongoNote(0.0, DrumAction.TAP), BongoNote(0.5, DrumAction.TAP), BongoNote(1.5, DrumAction.TAP),
        BongoNote(2.0, DrumAction.TAP), BongoNote(3.0, DrumAction.SWIPE),
    ),
    listOf(
        BongoNote(0.0, DrumAction.TAP), BongoNote(0.5, DrumAction.TAP), BongoNote(1.0, DrumAction.TAP),
        BongoNote(2.0, DrumAction.SWIPE), BongoNote(3.0, DrumAction.TAP), BongoNote(3.5, DrumAction.TAP),
    ),
    listOf(
        BongoNote(0.0, DrumAction.TAP), BongoNote(0.5, DrumAction.TAP), BongoNote(1.5, DrumAction.TAP),
        BongoNote(2.0, DrumAction.TAP), BongoNote(2.5, DrumAction.TAP), BongoNote(3.5, DrumAction.SWIPE),
    ),
    listOf(
        BongoNote(0.0, DrumAction.TAP), BongoNote(0.5, DrumAction.TAP), BongoNote(1.0, DrumAction.TAP),
        BongoNote(1.5, DrumAction.TAP), BongoNote(2.0, DrumAction.TAP), BongoNote(3.0, DrumAction.SWIPE),
    ),
)

const val BONGO_BLITZ_END_BEATS = BongoBlitzSong.END

/**
 * Bongo Blitz: the monkey claps a pattern on the high (tap) and low (swipe) drum in bar N, and the
 * player repeats it exactly — both the rhythm and which drum — in bar N+1. It runs at
 * [BONGO_BLITZ_BPM], faster than either other stage, and its patterns pack in longer, more
 * syncopated eighth-note runs than [SnapCrabsStage] ever asks a player to reproduce, while also
 * demanding [MangoChopStage]'s on-the-fly tap/swipe discrimination — the hardest stage in the game.
 *
 * An action is matched to the nearest un-judged target that wants *that* action, exactly like
 * [MangoChopStage.recordAction]: the wrong drum is a stray that consumes nothing, so a player who
 * catches their mistake can still correct it inside the window.
 *
 * [inputOffsetMs] is Calibrate's measured offset, applied the same way as in [SnapCrabsStage].
 */
class BongoBlitzStage(inputOffsetMs: Double = 0.0) {
    val secondsPerBeat = 60.0 / BONGO_BLITZ_BPM
    private val offsetBeats = inputOffsetMs / 1000.0 / secondsPerBeat

    val end = BONGO_BLITZ_END_BEATS
    val leadCues: List<BongoTarget>
    val targets: List<BongoTarget>
    val chart: List<ChartEvent>

    init {
        val lead = mutableListOf<BongoTarget>()
        val targ = mutableListOf<BongoTarget>()
        val events = Charts.bongoBlitzBacking().toMutableList()
        for ((i, pattern) in BONGO_PATTERNS.withIndex()) {
            val b = BongoBlitzSong.VERSE + i * 8
            for (note in pattern) {
                lead += BongoTarget(b + note.offset, note.action)
                events += ChartEvent(
                    b + note.offset,
                    if (note.action == DrumAction.TAP) SoundId.LEAD_BONGO_HI else SoundId.LEAD_BONGO_LO,
                )
                targ += BongoTarget(b + 4 + note.offset, note.action)
            }
        }
        events.sortBy { it.beat }
        chart = events
        leadCues = lead.sortedBy { it.beat }
        targets = targ.sortedBy { it.beat }
    }

    private val judged = BooleanArray(targets.size)
    private var perfect = 0
    private var ok = 0
    private var miss = 0

    /** Matches [action] to the nearest un-judged target that wants it. Null means a stray (wrong drum, or no target left nearby). */
    fun recordAction(action: DrumAction, rawBeat: Double): BongoOutcome {
        val beat = rawBeat - offsetBeats
        var bestIndex = -1
        var bestDelta = Double.MAX_VALUE
        for (i in targets.indices) {
            if (judged[i] || targets[i].action != action) continue
            val delta = abs(targets[i].beat - beat)
            if (delta < bestDelta) {
                bestDelta = delta
                bestIndex = i
            }
        }
        if (bestIndex == -1) return BongoOutcome(null, null)
        val errorMs = (beat - targets[bestIndex].beat) * secondsPerBeat * 1000.0
        val grade = judge(errorMs) ?: return BongoOutcome(null, errorMs)
        judged[bestIndex] = true
        if (grade == Grade.PERFECT) perfect++ else ok++
        return BongoOutcome(grade, errorMs)
    }

    /**
     * The drum the nearest still-open target wants, for touch input to decide tap vs. swipe the
     * instant the finger lands, matching [MangoChopStage.expectedAction].
     */
    fun expectedAction(rawBeat: Double): DrumAction? {
        val beat = rawBeat - offsetBeats
        return targets.indices
            .filter { !judged[it] }
            .minByOrNull { abs(targets[it].beat - beat) }
            ?.let { targets[it].action }
    }

    /** Marks any un-judged target whose OK window has fully passed as a miss. Call once per frame. */
    fun updateMisses(rawBeat: Double) {
        val beat = rawBeat - offsetBeats
        for (i in targets.indices) {
            if (!judged[i] && isMissed(beat, targets[i].beat, secondsPerBeat)) {
                judged[i] = true
                miss++
            }
        }
    }

    /** The raw audio-clock beat minus the calibrated offset; see [SnapCrabsStage.perceivedBeat]. */
    fun perceivedBeat(rawBeat: Double): Double = rawBeat - offsetBeats

    fun isFinished(currentBeat: Double): Boolean = currentBeat >= end

    fun tally(): ScoreTally = ScoreTally(perfect, ok, miss)
}

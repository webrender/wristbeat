package wristbeat.core

import kotlin.math.abs

/** The earlier stage whose scene, cues and rules a stretch of a remix borrows. */
enum class RemixScene { SNAP_CRABS, MANGO_CHOP, BONGO_BLITZ }

/**
 * A remix's two inputs, whatever the scene calls them: a tap snaps (crabs), chops (mangoes and
 * limes) or hits the high drum (monkeys); a swipe slices (pineapples) or hits the low drum.
 */
enum class RemixAction { TAP, SWIPE }

/** One scene's stretch of the song, from [start] (inclusive) to [end] (exclusive), in remix beats. */
data class RemixSegment(val scene: RemixScene, val start: Double, val end: Double)

/**
 * A beat the player has to act on. [toss] is set for the mango third, where each target is a
 * fruit landing, so the screen can draw it flying in, cut or bouncing off.
 */
data class RemixTarget(val beat: Double, val action: RemixAction, val scene: RemixScene, val toss: Toss? = null)

/** A lead character's call: a crab snap or a monkey's drum hit (mango tosses are [Toss]es instead). */
data class RemixCue(val beat: Double, val action: RemixAction, val scene: RemixScene)

/** [targetIndex] is the target a graded action hit; null for a stray. */
data class RemixOutcome(val grade: Grade?, val errorMs: Double?, val targetIndex: Int?)

/**
 * The crab third's calls, one per 8-beat call-and-response pair. None of them repeat a Snap Crabs
 * pattern; each still sits inside beats 0–2.5 of its bar, so the last response is over before the
 * scene wipes to the mango stall.
 */
private val REMIX_CRAB_PATTERNS: List<List<Double>> = listOf(
    listOf(0.0, 1.0, 1.5),
    listOf(0.5, 1.0, 2.0),
    listOf(0.0, 0.5, 1.5, 2.0),
    listOf(0.0, 0.5, 1.0, 2.0, 2.5),
)

/**
 * The mango third's tosses (whistle beat, fruit), all new. Landings sit at least a beat apart —
 * a pineapple's slice at least a beat from anything else — and the last one lands before the
 * scene wipes to the jungle.
 */
private val REMIX_TOSSES: List<Toss> = listOf(
    // Mangoes and limes over the groove alone.
    44.0 to Fruit.MANGO, 48.0 to Fruit.MANGO, 52.0 to Fruit.LIME, 54.0 to Fruit.LIME, 56.0 to Fruit.MANGO,
    // The pan theme comes in with the pineapples.
    60.0 to Fruit.PINEAPPLE, 64.0 to Fruit.PINEAPPLE, 66.0 to Fruit.LIME, 68.0 to Fruit.MANGO,
    71.0 to Fruit.LIME, 72.0 to Fruit.MANGO,
).map { (beat, fruit) -> Toss(beat, fruit) }

private fun tap(offset: Double) = offset to RemixAction.TAP
private fun swipe(offset: Double) = offset to RemixAction.SWIPE

/**
 * The monkey third's calls, all new, following Bongo Blitz's rules: notes at least an eighth apart,
 * at most one low-drum swipe per pattern, and every swipe a full beat clear of its neighbours.
 * Mango Chop has already taught the swipe by now, so it's in from the first pattern.
 */
private val REMIX_BONGO_PATTERNS: List<List<Pair<Double, RemixAction>>> = listOf(
    listOf(tap(0.0), tap(1.0), swipe(2.0)),
    listOf(swipe(0.0), tap(1.0), tap(1.5), tap(2.5)),
    listOf(tap(0.0), tap(0.5), swipe(1.5), tap(2.5), tap(3.0)),
    listOf(tap(0.0), tap(0.5), tap(1.0), tap(1.5), swipe(2.5), tap(3.5)),
)

private val REMIX_1_SEGMENTS = listOf(
    RemixSegment(RemixScene.SNAP_CRABS, 0.0, Remix1Song.MANGO),
    RemixSegment(RemixScene.MANGO_CHOP, Remix1Song.MANGO, Remix1Song.BONGO),
    RemixSegment(RemixScene.BONGO_BLITZ, Remix1Song.BONGO, Remix1Song.END),
)

const val REMIX_1_END_BEATS = Remix1Song.END

/**
 * Remix 1: one new song ([Charts.remix1Backing]) that plays Snap Crabs, then Mango Chop, then
 * Bongo Blitz, a third of the song each, with new patterns for every one of them. Each third
 * keeps its stage's own rules and cues — the lead crab's snaps to repeat a bar later, whistled
 * tosses to chop or slice as they land, the monkey's high/low calls to copy — all at the remix's
 * single tempo, [REMIX_1_BPM].
 *
 * Judging works like [BongoBlitzStage.recordAction] throughout: an action is matched to the
 * nearest un-judged target that wants *that* action, and the wrong one is a stray that consumes
 * nothing. (The crab third is tap-only, so there it behaves exactly like [SnapCrabsStage].)
 *
 * [inputOffsetMs] is Calibrate's measured offset, applied the same way as in [SnapCrabsStage],
 * including [perceivedBeat] for the visuals.
 */
class Remix1Stage(inputOffsetMs: Double = 0.0) {
    val secondsPerBeat = 60.0 / REMIX_1_BPM
    private val offsetBeats = inputOffsetMs / 1000.0 / secondsPerBeat

    val end = REMIX_1_END_BEATS
    val segments: List<RemixSegment> = REMIX_1_SEGMENTS
    val leadCues: List<RemixCue>
    val tosses: List<Toss> = REMIX_TOSSES
    val targets: List<RemixTarget>
    val chart: List<ChartEvent>

    /** For each of [tosses], its index in [targets]. */
    private val tossTargets: IntArray

    init {
        val lead = mutableListOf<RemixCue>()
        val targ = mutableListOf<RemixTarget>()
        val events = Charts.remix1Backing().toMutableList()
        for ((i, pattern) in REMIX_CRAB_PATTERNS.withIndex()) {
            val b = Remix1Song.CRABS + i * 8
            for (o in pattern) {
                lead += RemixCue(b + o, RemixAction.TAP, RemixScene.SNAP_CRABS)
                events += ChartEvent(b + o, SoundId.LEAD_SNAP)
                targ += RemixTarget(b + 4 + o, RemixAction.TAP, RemixScene.SNAP_CRABS)
            }
        }
        for (toss in REMIX_TOSSES) {
            events += ChartEvent(toss.beat, toss.fruit.cue)
            val action = if (toss.fruit.action == ChopAction.SLICE) RemixAction.SWIPE else RemixAction.TAP
            targ += RemixTarget(toss.landBeat, action, RemixScene.MANGO_CHOP, toss)
        }
        for ((i, pattern) in REMIX_BONGO_PATTERNS.withIndex()) {
            val b = Remix1Song.BONGO + i * 8
            for ((o, action) in pattern) {
                lead += RemixCue(b + o, action, RemixScene.BONGO_BLITZ)
                events += ChartEvent(b + o, if (action == RemixAction.TAP) SoundId.LEAD_BONGO_HI else SoundId.LEAD_BONGO_LO)
                targ += RemixTarget(b + 4 + o, action, RemixScene.BONGO_BLITZ)
            }
        }
        events.sortBy { it.beat }
        chart = events
        leadCues = lead.sortedBy { it.beat }
        targets = targ.sortedBy { it.beat }
        tossTargets = IntArray(REMIX_TOSSES.size) { i -> targets.indexOfFirst { it.toss === REMIX_TOSSES[i] } }
    }

    private val results = arrayOfNulls<TossResult>(targets.size)
    private val score = Scorekeeper()

    /** The scene on stage at [beat]: the intro belongs to the crabs and the outro to the monkeys. */
    fun sceneAt(beat: Double): RemixScene = segments.lastOrNull { beat >= it.start }?.scene ?: segments.first().scene

    /** How the target at [targetIndex] was resolved, or null while it's still open. */
    fun resultOf(targetIndex: Int): TossResult? = results[targetIndex]

    /** How the toss at [tossIndex] (into [tosses]) was resolved, for drawing it cut or bouncing off. */
    fun tossResult(tossIndex: Int): TossResult? = results[tossTargets[tossIndex]]

    /**
     * The action the nearest still-open target wants, or null once none are left — for touch input
     * to decide tap vs. swipe the instant the finger lands, as in [MangoChopStage.expectedAction].
     */
    fun expectedAction(rawBeat: Double): RemixAction? {
        val beat = rawBeat - offsetBeats
        return targets.indices
            .filter { results[it] == null }
            .minByOrNull { abs(targets[it].beat - beat) }
            ?.let { targets[it].action }
    }

    /** Judges [action]; a stray (wrong action, or nothing in reach) or a grade short of Perfect breaks the Perfect streak. */
    fun recordAction(action: RemixAction, rawBeat: Double): RemixOutcome =
        matchAction(action, rawBeat).also { if (it.grade == null) score.stray() }

    private fun matchAction(action: RemixAction, rawBeat: Double): RemixOutcome {
        val beat = rawBeat - offsetBeats
        val index = targets.indices
            .filter { results[it] == null && targets[it].action == action }
            .minByOrNull { abs(targets[it].beat - beat) }
            ?: return RemixOutcome(null, null, null)
        val errorMs = (beat - targets[index].beat) * secondsPerBeat * 1000.0
        val grade = judge(errorMs) ?: return RemixOutcome(null, errorMs, null)
        results[index] = TossResult(grade, beat)
        score.hit(grade)
        return RemixOutcome(grade, errorMs, index)
    }

    /** Marks targets whose OK window has passed as misses. Returns the newly missed target indices. Call once per frame. */
    fun updateMisses(rawBeat: Double): List<Int> {
        val beat = rawBeat - offsetBeats
        val newlyMissed = mutableListOf<Int>()
        for (i in targets.indices) {
            if (results[i] == null && isMissed(beat, targets[i].beat, secondsPerBeat)) {
                results[i] = TossResult(null, beat)
                score.miss()
                newlyMissed += i
            }
        }
        return newlyMissed
    }

    /** The raw audio-clock beat minus the calibrated offset; see [SnapCrabsStage.perceivedBeat]. */
    fun perceivedBeat(rawBeat: Double): Double = rawBeat - offsetBeats

    fun isFinished(currentBeat: Double): Boolean = currentBeat >= end

    fun tally(): ScoreTally = score.tally()
}

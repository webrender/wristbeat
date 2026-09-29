package wristbeat.core

import kotlin.math.abs

/** The player's two inputs at the wheel: a tap boosts through a gate, a swipe drifts through a corner. */
enum class DriveAction { BOOST, DRIFT }

/** A gate or corner on the road, reaching the car on [beat]. */
data class DriveTarget(val beat: Double, val action: DriveAction)

/** [targetIndex] is the target a graded action hit; null for a stray. */
data class DriveOutcome(val grade: Grade?, val errorMs: Double?, val targetIndex: Int?)

/** How many beats ahead the navigator calls each drift corner, the way Mango Chop's whistles lead each toss. */
const val CORNER_CALL_BEATS = 2.0

private fun boost(vararg offsets: Double) = offsets.map { DriveTarget(it, DriveAction.BOOST) }
private fun drift(offset: Double) = DriveTarget(offset, DriveAction.DRIFT)

/**
 * The road, one bar (4 beats) per entry, each target at its offset into the bar. There's
 * something to hit on nearly every beat, and eighth-note pairs and runs on top, so the song is
 * full of notes — but never faster than that:
 * - no two notes closer than half a beat (an eighth note),
 * - no run of more than four eighth notes in a row, so there's always a quarter-note breath soon,
 * - every drift a full beat clear of the notes either side of it, since a swipe takes real time to
 *   drag out and re-touch in a way a tap doesn't (the same rule as Bongo Blitz).
 * `NightDriftStageTest` checks all three across section boundaries too.
 */
private val VERSE_ROAD: List<List<DriveTarget>> = listOf(
    // The first gates arrive two beats in, so the count-in bar stays clear.
    boost(2.0, 3.0),
    boost(0.0, 1.0, 2.0, 3.0),
    boost(0.0, 1.0, 1.5, 2.0, 3.0),
    boost(0.0, 1.0, 2.0, 2.5, 3.0),
    boost(0.0, 0.5, 1.0, 2.0, 3.0),
    boost(0.0, 1.0, 2.0, 3.0, 3.5),
    boost(0.0, 1.0, 1.5, 2.0, 3.0, 3.5),
    boost(0.0, 1.0, 2.0),
)

private val PRE_CHORUS_ROAD: List<List<DriveTarget>> = listOf(
    boost(0.0, 1.0) + drift(2.0) + boost(3.0),
    boost(0.0, 1.0, 1.5) + drift(2.5),
    boost(0.0, 1.0) + drift(2.0) + boost(3.0, 3.5),
    boost(0.0, 1.0) + drift(2.0) + boost(3.0),
    listOf(drift(0.0)) + boost(1.0, 1.5, 2.0, 3.0),
    boost(0.0, 0.5, 1.0) + drift(2.0) + boost(3.0, 3.5),
    boost(0.0, 1.0, 1.5) + drift(2.5),
    boost(0.0, 1.0, 2.0) + drift(3.0),
)

private val CHORUS_ROAD: List<List<DriveTarget>> = listOf(
    boost(0.0, 0.5, 1.0) + drift(2.0) + boost(3.0, 3.5),
    boost(0.0, 1.0, 1.5, 2.0) + drift(3.0),
    boost(0.0, 0.5, 1.0, 1.5) + drift(2.5),
    boost(0.0, 1.0, 2.0, 2.5, 3.0),
    listOf(drift(0.0)) + boost(1.0, 1.5, 2.0, 3.0, 3.5),
    boost(0.0, 0.5) + drift(1.5) + boost(2.5, 3.0),
    boost(0.0, 1.0, 1.5) + drift(2.5) + boost(3.5),
    boost(0.0, 1.0, 2.0),
)

private val VERSE_2_ROAD: List<List<DriveTarget>> = listOf(
    boost(0.0, 1.0, 1.5, 2.0, 3.0),
    boost(0.0, 0.5, 1.0, 2.0, 3.0, 3.5),
    boost(0.0, 1.0, 2.0, 2.5, 3.0),
    boost(0.0, 1.0, 2.0),
)

private val PRE_CHORUS_2_ROAD: List<List<DriveTarget>> = listOf(
    boost(0.0, 1.0) + drift(2.0) + boost(3.0, 3.5),
    boost(0.0, 0.5) + drift(1.5) + boost(2.5, 3.0),
    listOf(drift(0.0)) + boost(1.0, 1.5, 2.0) + drift(3.0),
    boost(0.0, 1.0, 1.5) + drift(2.5),
)

/** The final chorus follows the first, with a new last line that ends on one more drift. */
private val FINAL_CHORUS_ROAD: List<List<DriveTarget>> = CHORUS_ROAD.take(3) + listOf(
    boost(0.0, 0.5, 1.0, 2.0, 3.0),
    listOf(drift(0.0)) + boost(1.0, 1.5, 2.0, 3.0, 3.5),
    boost(0.0, 0.5) + drift(1.5) + boost(2.5, 3.0),
    boost(0.0, 0.5) + drift(1.5) + boost(2.5, 3.5),
    boost(0.0, 0.5, 1.0) + drift(2.0),
)

private val ROAD: List<Pair<Double, List<List<DriveTarget>>>> = listOf(
    NightDriftSong.VERSE to VERSE_ROAD,
    NightDriftSong.PRE_CHORUS to PRE_CHORUS_ROAD,
    NightDriftSong.CHORUS to CHORUS_ROAD,
    NightDriftSong.VERSE_2 to VERSE_2_ROAD,
    NightDriftSong.PRE_CHORUS_2 to PRE_CHORUS_2_ROAD,
    NightDriftSong.FINAL_CHORUS to FINAL_CHORUS_ROAD,
)

const val NIGHT_DRIFT_END_BEATS = NightDriftSong.END

/**
 * Night Drift: a car races down a neon mountain pass to a Eurobeat song. Neon gates come up the
 * road toward it on nearly every beat — the player taps to boost through each one as it reaches
 * the car — and from the pre-chorus on, drift corners too, which take a swipe. The navigator calls
 * every corner [CORNER_CALL_BEATS] ahead with a two-tone beep, so a drift can be heard coming as
 * well as seen. It runs at [NIGHT_DRIFT_BPM] and packs in more notes than any other stage, while
 * keeping every note playable (see [VERSE_ROAD]).
 *
 * An action is matched to the nearest un-judged target that wants *that* action, like
 * [BongoBlitzStage.recordAction]: the wrong one is a stray that consumes nothing, so a player who
 * catches their mistake can still correct it inside the window.
 *
 * [inputOffsetMs] is Calibrate's measured offset, applied the same way as in [SnapCrabsStage],
 * including [perceivedBeat] for the visuals.
 */
class NightDriftStage(inputOffsetMs: Double = 0.0) {
    val secondsPerBeat = 60.0 / NIGHT_DRIFT_BPM
    private val offsetBeats = inputOffsetMs / 1000.0 / secondsPerBeat

    val end = NIGHT_DRIFT_END_BEATS
    val targets: List<DriveTarget> = ROAD.flatMap { (start, bars) ->
        bars.flatMapIndexed { bar, notes -> notes.map { it.copy(beat = start + bar * 4 + it.beat) } }
    }.sortedBy { it.beat }

    /** The beats the navigator calls a corner on, [CORNER_CALL_BEATS] before each drift. */
    val cornerCalls: List<Double> = targets.filter { it.action == DriveAction.DRIFT }.map { it.beat - CORNER_CALL_BEATS }

    val chart: List<ChartEvent> =
        (Charts.nightDriftBacking() + cornerCalls.map { ChartEvent(it, SoundId.CORNER_CALL) }).sortedBy { it.beat }

    private val results = arrayOfNulls<TossResult>(targets.size)
    private val score = Scorekeeper()

    /**
     * How the target at [targetIndex] was resolved — a graded hit or a miss, with the perceived beat
     * it happened on, the same shape as Mango Chop's tosses — or null while it's still open.
     */
    fun resultOf(targetIndex: Int): TossResult? = results[targetIndex]

    /** Judges [action]; a stray (wrong action, or nothing in reach) or a grade short of Perfect breaks the Perfect streak. */
    fun recordAction(action: DriveAction, rawBeat: Double): DriveOutcome =
        matchAction(action, rawBeat).also { if (it.grade == null) score.stray() }

    private fun matchAction(action: DriveAction, rawBeat: Double): DriveOutcome {
        val beat = rawBeat - offsetBeats
        val index = targets.indices
            .filter { results[it] == null && targets[it].action == action }
            .minByOrNull { abs(targets[it].beat - beat) }
            ?: return DriveOutcome(null, null, null)
        val errorMs = (beat - targets[index].beat) * secondsPerBeat * 1000.0
        val grade = judge(errorMs) ?: return DriveOutcome(null, errorMs, null)
        results[index] = TossResult(grade, beat)
        score.hit(grade)
        return DriveOutcome(grade, errorMs, index)
    }

    /**
     * The action the nearest still-open target wants, for touch input to decide tap vs. swipe the
     * instant the finger lands, matching [MangoChopStage.expectedAction].
     */
    fun expectedAction(rawBeat: Double): DriveAction? {
        val beat = rawBeat - offsetBeats
        return targets.indices
            .filter { results[it] == null }
            .minByOrNull { abs(targets[it].beat - beat) }
            ?.let { targets[it].action }
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

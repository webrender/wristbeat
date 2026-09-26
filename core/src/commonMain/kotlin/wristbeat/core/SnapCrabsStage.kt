package wristbeat.core

import kotlin.math.abs

/** The lead crab's call-and-response patterns, in order, matching the prototype's GAMES.crabs.build(). */
private val SNAP_PATTERNS: List<List<Double>> = listOf(
    listOf(0.0, 1.0, 2.0), // a
    listOf(0.0, 1.0, 2.0), // a
    listOf(0.0, 0.5, 1.0), // b
    listOf(0.0, 1.0, 2.0), // a
    listOf(0.0, 1.0, 1.5, 2.0), // c
    listOf(0.5, 1.5, 2.0), // e
    listOf(0.0, 1.0, 1.5, 2.0), // c
    listOf(0.0, 0.5, 1.0, 1.5, 2.0), // d
)

const val SNAP_CRABS_END_BEATS = 68.0

data class TapOutcome(val grade: Grade?, val errorMs: Double)

/**
 * Snap Crabs: the lead crab snaps a pattern in bar N, the player repeats it in bar N+1.
 * A tap is matched to the nearest un-judged target beat, then graded by [judge]; a tap that lands
 * outside the OK window (or with no target left nearby) is a stray and consumes nothing.
 */
class SnapCrabsStage {
    val end = SNAP_CRABS_END_BEATS
    val chart: List<ChartEvent>
    val leadCues: List<Double>
    val targets: List<Double>

    init {
        val lead = mutableListOf<Double>()
        val targ = mutableListOf<Double>()
        val events = Charts.snapCrabsBacking(end).toMutableList()
        for ((i, pattern) in SNAP_PATTERNS.withIndex()) {
            val b = 4.0 + i * 8
            for (o in pattern) {
                lead += b + o
                events += ChartEvent(b + o, SoundId.LEAD_SNAP)
                targ += b + 4 + o
            }
        }
        events.sortBy { it.beat }
        chart = events
        leadCues = lead.sorted()
        targets = targ.sorted()
    }

    private val judged = BooleanArray(targets.size)
    private var perfect = 0
    private var ok = 0
    private var miss = 0

    /** Matches [beat] to the nearest un-judged target and grades it. Null means a stray tap (no grade, nothing consumed). */
    fun recordTap(beat: Double): TapOutcome? {
        var bestIndex = -1
        var bestDelta = Double.MAX_VALUE
        for (i in targets.indices) {
            if (judged[i]) continue
            val delta = abs(targets[i] - beat)
            if (delta < bestDelta) {
                bestDelta = delta
                bestIndex = i
            }
        }
        if (bestIndex == -1) return null
        val errorMs = (beat - targets[bestIndex]) * SECONDS_PER_BEAT * 1000.0
        val grade = judge(errorMs) ?: return TapOutcome(null, errorMs)
        judged[bestIndex] = true
        if (grade == Grade.PERFECT) perfect++ else ok++
        return TapOutcome(grade, errorMs)
    }

    /** Marks any un-judged target whose OK window has fully passed as a miss. Call once per frame. */
    fun updateMisses(currentBeat: Double) {
        for (i in targets.indices) {
            if (!judged[i] && isMissed(currentBeat, targets[i])) {
                judged[i] = true
                miss++
            }
        }
    }

    fun isFinished(currentBeat: Double): Boolean = currentBeat >= end

    fun tally(): ScoreTally = ScoreTally(perfect, ok, miss)
}

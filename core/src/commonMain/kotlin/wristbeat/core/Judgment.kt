package wristbeat.core

import kotlin.math.abs
import kotlin.math.roundToInt

const val PERFECT_WINDOW_MS = 45.0
const val OK_WINDOW_MS = 120.0

enum class Grade { PERFECT, OK }

/** Grades a hit by its signed timing error in ms. Returns null if the error is outside the OK window (a stray tap). */
fun judge(errorMs: Double): Grade? {
    val magnitude = abs(errorMs)
    return when {
        magnitude <= PERFECT_WINDOW_MS -> Grade.PERFECT
        magnitude <= OK_WINDOW_MS -> Grade.OK
        else -> null
    }
}

/**
 * True once the song position has passed the target's OK window without a hit being recorded.
 * [secondsPerBeat] defaults to the shared 116 BPM tempo; stages with their own tempo pass theirs.
 */
fun isMissed(currentBeat: Double, targetBeat: Double, secondsPerBeat: Double = SECONDS_PER_BEAT): Boolean =
    (currentBeat - targetBeat) * secondsPerBeat * 1000.0 > OK_WINDOW_MS

enum class Rank { SUPERB, OK, TRY_AGAIN }

/**
 * A run's Perfect/OK/Miss counts so far. [streak] is the current run of back-to-back Perfects,
 * for the on-screen streak counter; it doesn't affect [score].
 */
data class ScoreTally(val perfect: Int = 0, val ok: Int = 0, val miss: Int = 0, val streak: Int = 0) {
    val total: Int get() = perfect + ok + miss
    val score: Double get() = if (total == 0) 0.0 else (perfect + 0.6 * ok) / total

    /** The score as the whole percentage the results screen shows and records are kept in. */
    val percent: Int get() = (score * 100).roundToInt()
    val rank: Rank
        get() = when {
            score >= 0.85 -> Rank.SUPERB
            score >= 0.60 -> Rank.OK
            else -> Rank.TRY_AGAIN
        }
}

/**
 * Keeps a run's [ScoreTally], shared by every scored stage. Anything short of a Perfect — an OK, a
 * miss, or a [stray] input — breaks the Perfect streak.
 */
class Scorekeeper {
    private var perfect = 0
    private var ok = 0
    private var miss = 0
    private var streak = 0

    fun hit(grade: Grade) {
        if (grade == Grade.PERFECT) {
            perfect++
            streak++
        } else {
            ok++
            streak = 0
        }
    }

    fun miss() {
        miss++
        streak = 0
    }

    /** An input that hit nothing (off the beat, or the wrong action): it scores nothing, but breaks the streak. */
    fun stray() {
        streak = 0
    }

    fun tally(): ScoreTally = ScoreTally(perfect, ok, miss, streak)
}

fun median(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
}

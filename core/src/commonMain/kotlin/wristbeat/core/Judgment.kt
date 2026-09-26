package wristbeat.core

import kotlin.math.abs

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

/** True once the song position has passed the target's OK window without a hit being recorded. */
fun isMissed(currentBeat: Double, targetBeat: Double): Boolean =
    (currentBeat - targetBeat) * SECONDS_PER_BEAT * 1000.0 > OK_WINDOW_MS

enum class Rank { SUPERB, OK, TRY_AGAIN }

data class ScoreTally(val perfect: Int = 0, val ok: Int = 0, val miss: Int = 0) {
    val total: Int get() = perfect + ok + miss
    val score: Double get() = if (total == 0) 0.0 else (perfect + 0.6 * ok) / total
    val rank: Rank
        get() = when {
            score >= 0.85 -> Rank.SUPERB
            score >= 0.60 -> Rank.OK
            else -> Rank.TRY_AGAIN
        }
}

fun median(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
}

package wristbeat.core

import kotlin.math.abs

/** The player's two inputs: a tap chops, a swipe slices. */
enum class ChopAction { CHOP, SLICE }

/**
 * What gets tossed onto the board. [airBeats] is how long after its whistle the fruit lands (the
 * target beat); [action] is the input that cuts it. Pineapples are tossed from the opposite side,
 * with their own falling double whistle, so they read as "swipe" by sight and by ear.
 */
enum class Fruit(val airBeats: Double, val action: ChopAction, val cue: SoundId) {
    MANGO(2.0, ChopAction.CHOP, SoundId.WHISTLE_MANGO),
    LIME(1.0, ChopAction.CHOP, SoundId.WHISTLE_LIME),
    PINEAPPLE(2.0, ChopAction.SLICE, SoundId.WHISTLE_PINEAPPLE),
}

data class Toss(val beat: Double, val fruit: Fruit) {
    val landBeat: Double get() = beat + fruit.airBeats
}

/**
 * How a toss was resolved: a graded hit or a miss. [beat] is when it happened, as a
 * [MangoChopStage.perceivedBeat], so the cut/bounce animation starts in step with the visuals.
 */
data class TossResult(val grade: Grade?, val beat: Double) {
    val hit: Boolean get() = grade != null
}

/** [tossIndex] is the toss a graded action cut; null for a stray (nothing matching in reach). */
data class ChopOutcome(val grade: Grade?, val errorMs: Double?, val tossIndex: Int?)

/**
 * Mangoes (2 beats) and limes (1 beat) introduce the chop; pineapples arrive with the song's bridge
 * and need a slice. The final chorus mixes all three. Each group starts on a section of
 * [MangoChopSong], so new fruit comes in with new music. Toss beats are in [MANGO_CHOP_BPM] beats.
 */
private val MANGO_CHOP_TOSSES: List<Toss> = listOf(
    // Verse
    12.0 to Fruit.MANGO, 16.0 to Fruit.MANGO, 20.0 to Fruit.MANGO, 24.0 to Fruit.MANGO,
    // Chorus
    28.0 to Fruit.LIME, 30.0 to Fruit.LIME, 32.0 to Fruit.MANGO, 36.0 to Fruit.MANGO,
    // Bridge
    44.0 to Fruit.PINEAPPLE, 48.0 to Fruit.PINEAPPLE, 52.0 to Fruit.MANGO, 56.0 to Fruit.PINEAPPLE,
    // Final chorus
    60.0 to Fruit.MANGO, 62.0 to Fruit.LIME, 64.0 to Fruit.PINEAPPLE, 66.0 to Fruit.LIME,
    68.0 to Fruit.MANGO, 70.0 to Fruit.PINEAPPLE,
    74.0 to Fruit.LIME, 75.0 to Fruit.LIME, 76.0 to Fruit.PINEAPPLE, 78.0 to Fruit.MANGO,
    80.0 to Fruit.LIME, 81.0 to Fruit.PINEAPPLE, 84.0 to Fruit.MANGO,
    86.0 to Fruit.LIME, 87.0 to Fruit.LIME, 88.0 to Fruit.MANGO,
).map { (beat, fruit) -> Toss(beat, fruit) }

const val MANGO_CHOP_END_BEATS = MangoChopSong.END

/**
 * Mango Chop: a whistle marks each toss, and the fruit lands [Fruit.airBeats] later. The player
 * chops (tap) mangoes and limes and slices (swipe) pineapples as they land.
 *
 * An action is matched to the nearest un-judged toss that takes *that* action. The wrong action
 * on a fruit is a stray: it consumes nothing, so the player can still correct it inside the
 * window, and otherwise the fruit bounces off as a miss.
 *
 * [inputOffsetMs] is Calibrate's measured offset, applied the same way as in [SnapCrabsStage],
 * including [perceivedBeat] for the visuals.
 */
class MangoChopStage(inputOffsetMs: Double = 0.0) {
    val secondsPerBeat = 60.0 / MANGO_CHOP_BPM
    private val offsetBeats = inputOffsetMs / 1000.0 / secondsPerBeat

    val end = MANGO_CHOP_END_BEATS
    val tosses: List<Toss> = MANGO_CHOP_TOSSES
    val chart: List<ChartEvent> =
        (Charts.mangoChopBacking() + tosses.map { ChartEvent(it.beat, it.fruit.cue) }).sortedBy { it.beat }

    private val results = arrayOfNulls<TossResult>(tosses.size)
    private var perfect = 0
    private var ok = 0
    private var miss = 0

    fun resultOf(tossIndex: Int): TossResult? = results[tossIndex]

    /**
     * The action the nearest still-open toss wants, or null once none are left. Lets touch input
     * fire a chop the instant the finger lands when a chop is what's coming, instead of waiting to
     * see whether the press turns into a swipe.
     */
    fun expectedAction(rawBeat: Double): ChopAction? {
        val beat = rawBeat - offsetBeats
        return tosses.indices
            .filter { results[it] == null }
            .minByOrNull { abs(tosses[it].landBeat - beat) }
            ?.let { tosses[it].fruit.action }
    }

    fun recordAction(action: ChopAction, rawBeat: Double): ChopOutcome {
        val beat = rawBeat - offsetBeats
        val index = tosses.indices
            .filter { results[it] == null && tosses[it].fruit.action == action }
            .minByOrNull { abs(tosses[it].landBeat - beat) }
            ?: return ChopOutcome(null, null, null)
        val errorMs = (beat - tosses[index].landBeat) * secondsPerBeat * 1000.0
        val grade = judge(errorMs) ?: return ChopOutcome(null, errorMs, null)
        results[index] = TossResult(grade, beat)
        if (grade == Grade.PERFECT) perfect++ else ok++
        return ChopOutcome(grade, errorMs, index)
    }

    /** Marks tosses whose OK window has passed as misses. Returns the newly missed toss indices. Call once per frame. */
    fun updateMisses(rawBeat: Double): List<Int> {
        val beat = rawBeat - offsetBeats
        val newlyMissed = mutableListOf<Int>()
        for (i in tosses.indices) {
            if (results[i] == null && isMissed(beat, tosses[i].landBeat, secondsPerBeat)) {
                results[i] = TossResult(null, beat)
                miss++
                newlyMissed += i
            }
        }
        return newlyMissed
    }

    /** The raw audio-clock beat minus the calibrated offset; see [SnapCrabsStage.perceivedBeat]. */
    fun perceivedBeat(rawBeat: Double): Double = rawBeat - offsetBeats

    fun isFinished(currentBeat: Double): Boolean = currentBeat >= end

    fun tally(): ScoreTally = ScoreTally(perfect, ok, miss)
}

package wristbeat.app

/** The best score (a whole percentage) saved for the stage keyed [stageId], or null if it has never been played to the end. */
internal expect fun loadBestScore(stageId: String): Int?

internal expect fun saveBestScore(stageId: String, percent: Int)

/**
 * How a finished run's score compared with [stage]'s saved record. [previousBest] is the record
 * before this run (null the first time a song is finished); [isNew] is true when this run beat it,
 * or set the first record with a score above zero.
 */
internal data class RecordResult(val score: Int, val previousBest: Int?, val isNew: Boolean) {
    /** The record as it stands now, including this run. */
    val best: Int get() = if (isNew) score else previousBest ?: score
}

/** Checks a finished run's [percent] against [stage]'s saved record, saving it if it's a new one. */
internal fun submitScore(stage: Stage, percent: Int): RecordResult {
    val previous = loadBestScore(stage.name)
    val isNew = percent > (previous ?: 0)
    if (isNew) saveBestScore(stage.name, percent)
    return RecordResult(percent, previous, isNew)
}

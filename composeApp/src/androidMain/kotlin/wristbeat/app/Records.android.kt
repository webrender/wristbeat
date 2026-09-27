package wristbeat.app

private const val KEY_PREFIX = "best."

internal actual fun loadBestScore(stageId: String): Int? {
    val p = prefs() ?: return null
    val key = KEY_PREFIX + stageId
    return if (p.contains(key)) p.getInt(key, 0) else null
}

internal actual fun saveBestScore(stageId: String, percent: Int) {
    prefs()?.edit()?.putInt(KEY_PREFIX + stageId, percent)?.apply()
}

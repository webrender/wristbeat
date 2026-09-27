package wristbeat.app

private const val KEY_PREFIX = "wristbeat.best."

internal actual fun loadBestScore(stageId: String): Int? = jsStorageGet(KEY_PREFIX + stageId)?.toIntOrNull()

internal actual fun saveBestScore(stageId: String, percent: Int) {
    jsStorageSet(KEY_PREFIX + stageId, percent.toString())
}

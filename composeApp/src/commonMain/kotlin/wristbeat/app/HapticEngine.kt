package wristbeat.app

/** Confirms the player's own tap. Deliberately never used for the click track itself (see CalibrateStage). */
expect class HapticEngine() {
    fun pulse()
}

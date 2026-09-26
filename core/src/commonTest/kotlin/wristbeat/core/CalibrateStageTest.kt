package wristbeat.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalibrateStageTest {
    @Test fun ignoresCountInTaps() {
        val stage = CalibrateStage()
        assertNull(stage.recordTap(1.0))
        assertEquals(0, stage.tapCount)
    }

    @Test fun ignoresTapsAtOrAfterTotalBeats() {
        val stage = CalibrateStage(totalBeats = 36, countInBeats = 4)
        assertNull(stage.recordTap(36.0))
    }

    @Test fun needsMinimumTapsForOkResult() {
        val stage = CalibrateStage()
        repeat(5) { i -> stage.recordTap(4.0 + i) }
        val result = stage.finish()
        assertFalse(result.ok)
        assertEquals(5, result.tapCount)
    }

    @Test fun computesMedianOffsetFromSixTaps() {
        val stage = CalibrateStage()
        val lateBeats = 0.02 / SECONDS_PER_BEAT // consistently 20ms late
        repeat(6) { i -> stage.recordTap(4.0 + i + lateBeats) }
        val result = stage.finish()
        assertTrue(result.ok)
        assertEquals(20.0, result.offsetMs, 0.001)
    }
}

package wristbeat.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SnapCrabsStageTest {
    @Test fun firstTargetIsFourBeatsAfterFirstLeadCue() {
        val stage = SnapCrabsStage()
        assertEquals(stage.leadCues.first() + 4.0, stage.targets.first())
    }

    @Test fun tapExactlyOnTargetIsPerfect() {
        val stage = SnapCrabsStage()
        val outcome = stage.recordTap(stage.targets.first())
        assertEquals(Grade.PERFECT, outcome?.grade)
        assertEquals(1, stage.tally().perfect)
    }

    @Test fun tapFarFromAnyTargetIsStray() {
        val stage = SnapCrabsStage()
        val outcome = stage.recordTap(stage.targets.first() - 10.0)
        assertNull(outcome?.grade)
        assertEquals(ScoreTally(), stage.tally())
    }

    @Test fun secondTapOnSameSpotIsStrayOnceTheFirstTargetIsTaken() {
        val stage = SnapCrabsStage()
        stage.recordTap(stage.targets.first())
        val second = stage.recordTap(stage.targets.first())
        assertNull(second?.grade)
        assertEquals(1, stage.tally().perfect)
    }

    @Test fun uncaughtTargetBecomesMissAfterWindowPasses() {
        val stage = SnapCrabsStage()
        val target = stage.targets.first()
        val justPastWindow = target + (OK_WINDOW_MS / 1000.0 + 0.001) / SECONDS_PER_BEAT
        stage.updateMisses(justPastWindow)
        assertEquals(1, stage.tally().miss)
    }

    @Test fun tallyRankFollowsScoreFormula() {
        val stage = SnapCrabsStage()
        for (t in stage.targets) stage.recordTap(t)
        assertEquals(stage.targets.size, stage.tally().perfect)
        assertEquals(Rank.SUPERB, stage.tally().rank)
    }
}

package wristbeat.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BongoBlitzStageTest {
    @Test fun firstTargetIsFourBeatsAfterFirstLeadCue() {
        val stage = BongoBlitzStage()
        assertEquals(stage.leadCues.first().beat + 4.0, stage.targets.first().beat)
        assertEquals(stage.leadCues.first().action, stage.targets.first().action)
    }

    @Test fun runsFasterThanMangoChop() {
        assertTrue(BongoBlitzStage().secondsPerBeat < MangoChopStage().secondsPerBeat)
    }

    @Test fun matchingActionExactlyOnTargetIsPerfect() {
        val stage = BongoBlitzStage()
        val target = stage.targets.first()
        val outcome = stage.recordAction(target.action, target.beat)
        assertEquals(Grade.PERFECT, outcome.grade)
        assertEquals(1, stage.tally().perfect)
    }

    @Test fun wrongDrumIsStrayAndLeavesTheTargetOpen() {
        val stage = BongoBlitzStage()
        val swipeTarget = stage.targets.first { it.action == DrumAction.SWIPE }
        assertNull(stage.recordAction(DrumAction.TAP, swipeTarget.beat).grade)
        assertEquals(Grade.PERFECT, stage.recordAction(DrumAction.SWIPE, swipeTarget.beat).grade)
    }

    @Test fun expectedActionFollowsTheNearestOpenTarget() {
        val stage = BongoBlitzStage()
        val swipeTarget = stage.targets.first { it.action == DrumAction.SWIPE }
        assertEquals(DrumAction.TAP, stage.expectedAction(stage.targets.first().beat))
        assertEquals(DrumAction.SWIPE, stage.expectedAction(swipeTarget.beat))
    }

    @Test fun uncaughtTargetBecomesMissAfterWindowPasses() {
        val stage = BongoBlitzStage()
        val target = stage.targets.first()
        val justPastWindow = target.beat + (OK_WINDOW_MS / 1000.0 + 0.001) / stage.secondsPerBeat
        stage.updateMisses(justPastWindow)
        assertEquals(1, stage.tally().miss)
    }

    @Test fun tallyRankFollowsScoreFormula() {
        val stage = BongoBlitzStage()
        for (target in stage.targets) stage.recordAction(target.action, target.beat)
        assertEquals(stage.targets.size, stage.tally().perfect)
        assertEquals(Rank.SUPERB, stage.tally().rank)
    }

    @Test fun calibratedOffsetShiftsJudgment() {
        val stage = BongoBlitzStage(inputOffsetMs = 80.0)
        val target = stage.targets.first()
        val outcome = stage.recordAction(target.action, target.beat + 0.080 / stage.secondsPerBeat)
        assertEquals(Grade.PERFECT, outcome.grade)
        assertEquals(0.0, outcome.errorMs!!, 0.001)
    }

    @Test fun perceivedBeatSubtractsTheOffset() {
        val stage = BongoBlitzStage(inputOffsetMs = 200.0)
        assertEquals(10.0 - 0.2 / stage.secondsPerBeat, stage.perceivedBeat(10.0), 1e-9)
        assertEquals(10.0, BongoBlitzStage().perceivedBeat(10.0))
    }

    @Test fun verseIsTapOnlyAndTheChorusIntroducesSwipes() {
        val stage = BongoBlitzStage()
        val verseTargets = stage.targets.filter { it.beat < BongoBlitzSong.CHORUS }
        assertTrue(verseTargets.all { it.action == DrumAction.TAP })
        assertTrue(stage.targets.any { it.beat >= BongoBlitzSong.CHORUS && it.action == DrumAction.SWIPE })
    }

    @Test fun noTwoNotesAreEverCloserThanAnEighthNoteApart() {
        // Even a deliberate, well-practiced click or tap can't reliably beat half a beat's notice
        // repeated many times over a run — this caught patterns with 16th-note taps packed in.
        val stage = BongoBlitzStage()
        val sorted = stage.targets.sortedBy { it.beat }
        for (i in 0 until sorted.size - 1) {
            val gap = sorted[i + 1].beat - sorted[i].beat
            assertTrue(gap >= 0.5 - 1e-9, "notes at beats ${sorted[i].beat} and ${sorted[i + 1].beat} are only $gap beats apart")
        }
    }

    @Test fun everySwipeLeavesEnoughTimeToRecoverBeforeTheNextNote() {
        // A swipe takes real time to drag out and re-touch for whatever comes next, unlike a tap, so
        // it needs at least a full beat before the following note in the response timeline — this
        // caught patterns that packed swipes as little as a 16th note apart, which no one can play.
        val stage = BongoBlitzStage()
        val sorted = stage.targets.sortedBy { it.beat }
        for (i in sorted.indices) {
            if (sorted[i].action != DrumAction.SWIPE) continue
            val next = sorted.getOrNull(i + 1) ?: continue
            assertTrue(
                next.beat - sorted[i].beat >= 1.0,
                "swipe at beat ${sorted[i].beat} only has ${next.beat - sorted[i].beat} beats before the next note",
            )
        }
    }

    @Test fun bridgeIsDenserThanTheVerse() {
        val stage = BongoBlitzStage()
        val verseNoteCount = stage.targets.count { it.beat < BongoBlitzSong.CHORUS }
        val bridgeNoteCount = stage.targets.count { it.beat >= BongoBlitzSong.BRIDGE }
        assertTrue(bridgeNoteCount > verseNoteCount)
    }

    @Test fun songHasAnIntroBeforeTheFirstCallAndAnOutroAfterTheLastTarget() {
        val stage = BongoBlitzStage()
        assertEquals(BongoBlitzSong.VERSE, stage.leadCues.first().beat)
        assertTrue(stage.chart.any { it.beat < BongoBlitzSong.VERSE - 4 && it.sound == SoundId.MARIMBA })
        assertTrue(stage.targets.last().beat < BongoBlitzSong.OUTRO)
        assertTrue(stage.chart.any { it.beat == BongoBlitzSong.OUTRO && it.sound == SoundId.CRASH })
        assertTrue(stage.chart.all { it.beat < stage.end })
    }

    @Test fun chartCarriesACallForEveryLeadCue() {
        val stage = BongoBlitzStage()
        for (cue in stage.leadCues) {
            val expected = if (cue.action == DrumAction.TAP) SoundId.LEAD_BONGO_HI else SoundId.LEAD_BONGO_LO
            assertTrue(stage.chart.any { it.beat == cue.beat && it.sound == expected })
        }
    }
}

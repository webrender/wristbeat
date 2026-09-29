package wristbeat.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NightDriftStageTest {
    @Test fun runsFasterThanMangoChopButBelowBongoBlitz() {
        val spb = NightDriftStage().secondsPerBeat
        assertTrue(spb < MangoChopStage().secondsPerBeat)
        assertTrue(spb > BongoBlitzStage().secondsPerBeat)
    }

    @Test fun packsInMoreNotesPerSecondThanAnyOtherStage() {
        fun rate(beats: List<Double>, secondsPerBeat: Double) = beats.size / ((beats.max() - beats.min()) * secondsPerBeat)
        val drift = NightDriftStage().let { s -> rate(s.targets.map { it.beat }, s.secondsPerBeat) }
        val others = mapOf(
            "Snap Crabs" to rate(SnapCrabsStage().targets, SECONDS_PER_BEAT),
            "Mango Chop" to MangoChopStage().let { s -> rate(s.tosses.map { it.landBeat }, s.secondsPerBeat) },
            "Bongo Blitz" to BongoBlitzStage().let { s -> rate(s.targets.map { it.beat }, s.secondsPerBeat) },
            "Remix 1" to Remix1Stage().let { s -> rate(s.targets.map { it.beat }, s.secondsPerBeat) },
        )
        for ((name, r) in others) assertTrue(drift > r, "Night Drift ($drift notes/s) should outpace $name ($r notes/s)")
    }

    @Test fun everyBarFromTheVerseToTheOutroHasSomethingToPlay() {
        val stage = NightDriftStage()
        var bar = NightDriftSong.VERSE
        while (bar < NightDriftSong.OUTRO) {
            val count = stage.targets.count { it.beat >= bar && it.beat < bar + 4 }
            assertTrue(count >= 2, "bar at beat $bar has only $count notes")
            bar += 4
        }
    }

    @Test fun noTwoNotesAreEverCloserThanAnEighthNoteApart() {
        val sorted = NightDriftStage().targets
        for (i in 0 until sorted.size - 1) {
            val gap = sorted[i + 1].beat - sorted[i].beat
            assertTrue(gap >= 0.5 - 1e-9, "notes at beats ${sorted[i].beat} and ${sorted[i + 1].beat} are only $gap beats apart")
        }
    }

    @Test fun eighthNoteRunsNeverGoPastFourNotes() {
        // Dense is fine, relentless isn't: a quarter-note gap always comes along within four notes.
        val sorted = NightDriftStage().targets
        var run = 1
        for (i in 1 until sorted.size) {
            run = if (sorted[i].beat - sorted[i - 1].beat < 1.0 - 1e-9) run + 1 else 1
            assertTrue(run <= 4, "a run of $run eighth notes ends at beat ${sorted[i].beat}")
        }
    }

    @Test fun everyDriftHasAFullBeatClearOnEitherSide() {
        // Same reasoning as BongoBlitzStageTest: a swipe takes real time to drag out and re-touch.
        val sorted = NightDriftStage().targets
        for (i in sorted.indices) {
            if (sorted[i].action != DriveAction.DRIFT) continue
            sorted.getOrNull(i - 1)?.let { assertTrue(sorted[i].beat - it.beat >= 1.0, "drift at ${sorted[i].beat} follows ${it.beat}") }
            sorted.getOrNull(i + 1)?.let { assertTrue(it.beat - sorted[i].beat >= 1.0, "drift at ${sorted[i].beat} precedes ${it.beat}") }
        }
    }

    @Test fun theVerseIsBoostOnlyAndDriftsArriveWithThePreChorus() {
        val stage = NightDriftStage()
        assertTrue(stage.targets.filter { it.beat < NightDriftSong.PRE_CHORUS }.all { it.action == DriveAction.BOOST })
        assertTrue(stage.targets.any { it.beat < NightDriftSong.PRE_CHORUS + 4 && it.action == DriveAction.DRIFT })
    }

    @Test fun theNavigatorCallsEveryCornerTwoBeatsAhead() {
        val stage = NightDriftStage()
        val drifts = stage.targets.filter { it.action == DriveAction.DRIFT }
        assertEquals(drifts.size, stage.cornerCalls.size)
        for (drift in drifts) {
            assertTrue(stage.chart.any { it.sound == SoundId.CORNER_CALL && it.beat == drift.beat - CORNER_CALL_BEATS }, "drift at ${drift.beat}")
        }
        // Every call comes after the count-in, so it can't be mistaken for part of it.
        assertTrue(stage.cornerCalls.all { it >= NightDriftSong.VERSE })
    }

    @Test fun songHasAnIntroBeforeTheFirstGateAndAnOutroAfterTheLastTarget() {
        val stage = NightDriftStage()
        assertTrue(stage.targets.first().beat >= NightDriftSong.VERSE)
        assertTrue(stage.chart.any { it.beat < NightDriftSong.VERSE - 4 && it.sound == SoundId.SAW_LEAD })
        assertTrue(stage.chart.any { it.beat < NightDriftSong.VERSE && it.sound == SoundId.STICK })
        assertTrue(stage.targets.last().beat < NightDriftSong.OUTRO)
        assertTrue(stage.chart.any { it.beat == NightDriftSong.OUTRO && it.sound == SoundId.CRASH })
        assertTrue(stage.chart.all { it.beat < stage.end })
    }

    @Test fun theFinalChorusChangesKeyUpATone() {
        val chart = NightDriftStage().chart
        fun lead(from: Double) = chart.filter { it.sound == SoundId.SAW_LEAD && it.beat >= from && it.beat < from + 32 }
            .map { it.beat - from to it.param }
        assertEquals(lead(NightDriftSong.CHORUS).map { (o, n) -> o to n + 2 }, lead(NightDriftSong.FINAL_CHORUS))
    }

    @Test fun matchingActionExactlyOnTargetIsPerfect() {
        val stage = NightDriftStage()
        for (target in stage.targets) assertEquals(Grade.PERFECT, stage.recordAction(target.action, target.beat).grade, "target at ${target.beat}")
        assertEquals(stage.targets.size, stage.tally().perfect)
        assertEquals(Rank.SUPERB, stage.tally().rank)
    }

    @Test fun wrongActionIsAStrayAndLeavesTheTargetOpen() {
        val stage = NightDriftStage()
        val i = stage.targets.indexOfFirst { it.action == DriveAction.DRIFT }
        val beat = stage.targets[i].beat
        assertNull(stage.recordAction(DriveAction.BOOST, beat).grade)
        assertNull(stage.resultOf(i))
        assertEquals(Grade.PERFECT, stage.recordAction(DriveAction.DRIFT, beat).grade)
        assertEquals(Grade.PERFECT, stage.resultOf(i)?.grade)
    }

    @Test fun aStrayBreaksTheStreak() {
        val stage = NightDriftStage()
        val first = stage.targets.first()
        stage.recordAction(first.action, first.beat)
        assertEquals(1, stage.tally().streak)
        stage.recordAction(DriveAction.BOOST, first.beat + 0.25)
        assertEquals(0, stage.tally().streak)
    }

    @Test fun expectedActionFollowsTheNearestOpenTarget() {
        val stage = NightDriftStage()
        val drift = stage.targets.first { it.action == DriveAction.DRIFT }
        assertEquals(DriveAction.BOOST, stage.expectedAction(stage.targets.first().beat))
        assertEquals(DriveAction.DRIFT, stage.expectedAction(drift.beat))
    }

    @Test fun uncaughtTargetBecomesMissAfterWindowPasses() {
        val stage = NightDriftStage()
        val target = stage.targets.first()
        val justInside = target.beat + (OK_WINDOW_MS / 1000.0 - 0.005) / stage.secondsPerBeat
        assertTrue(stage.updateMisses(justInside).isEmpty())
        val justPast = target.beat + (OK_WINDOW_MS / 1000.0 + 0.005) / stage.secondsPerBeat
        assertEquals(listOf(0), stage.updateMisses(justPast))
        assertEquals(1, stage.tally().miss)
    }

    @Test fun calibratedOffsetShiftsJudgmentAndVisuals() {
        val stage = NightDriftStage(inputOffsetMs = 80.0)
        val target = stage.targets.first()
        val outcome = stage.recordAction(target.action, target.beat + 0.080 / stage.secondsPerBeat)
        assertEquals(Grade.PERFECT, outcome.grade)
        assertEquals(0.0, outcome.errorMs!!, 0.001)
        assertEquals(10.0 - 0.08 / stage.secondsPerBeat, stage.perceivedBeat(10.0), 1e-9)
    }
}
